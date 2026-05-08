/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Process viewer for the on-device VlogPilot pipeline. The screen is built as
 * a compact editing console: input assets, agent outputs, timeline decisions,
 * critique notes, and the rendered candidate stay in one inspectable flow.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.PromptStrings
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.EventScoutAgent
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.VlmAnnotator
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.MediaLoader
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.GenerationIntent
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.PowerProfile
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.VlogPilotRunConfig
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.VlmTags
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventCandidateSnapshot
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionManifest
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionStatus
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.StagePerf
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AssetStrip(assets: List<Asset>, totalCount: Int) {
  LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    items(assets, key = { it.id }) { asset ->
      AssetThumb(asset = asset)
    }
    if (totalCount > assets.size) {
      item {
        Surface(
          modifier = Modifier
            .width(68.dp)
            .height(88.dp),
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
          contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text("+${totalCount - assets.size}", style = MaterialTheme.typography.titleSmall)
          }
        }
      }
    }
  }
}

@Composable
internal fun AssetThumb(
  asset: Asset,
  modifier: Modifier = Modifier
    .width(68.dp)
    .height(88.dp),
  showType: Boolean = true,
) {
  val context = LocalContext.current
  val bitmap by produceState<Bitmap?>(initialValue = null, asset.id, asset.contentUri) {
    value = withContext(Dispatchers.IO) {
      MediaLoader.loadImage(context, asset, maxSide = 180)
    }
  }

  Box(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(12.dp)),
    contentAlignment = Alignment.Center,
  ) {
    val bmp = bitmap
    if (bmp != null) {
      Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = asset.displayName,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    } else {
      Icon(
        Icons.Outlined.PhotoLibrary,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
      )
    }
    if (showType) {
      Surface(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(5.dp),
        shape = RoundedCornerShape(999.dp),
        color = Color.Black.copy(alpha = 0.58f),
        contentColor = Color.White,
      ) {
        Text(
          if (asset.mediaType == MediaType.VIDEO) "VID" else "IMG",
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}

@Composable
internal fun VideoPreview(mp4Path: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = Color.Black,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.Black)
        .padding(vertical = 10.dp),
      contentAlignment = Alignment.Center,
    ) {
      AndroidView(
        factory = { ctx ->
          VideoView(ctx).apply {
            tag = mp4Path
            setVideoPath(mp4Path)
            setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
            setOnPreparedListener { player ->
              player.isLooping = true
              seekTo(1)
            }
            seekTo(1)
          }
        },
        update = { view ->
          if (view.tag != mp4Path) {
            view.tag = mp4Path
            view.setVideoPath(mp4Path)
            view.seekTo(1)
          }
        },
        modifier = Modifier
          .fillMaxWidth(0.72f)
          .widthIn(max = 360.dp)
          .aspectRatio(9f / 16f)
          .clip(RoundedCornerShape(14.dp)),
      )
    }
  }
}

@Composable
internal fun DecisionSection(
  icon: ImageVector,
  title: String,
  subtitle: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SectionHeader(icon = icon, title = title, subtitle = subtitle)
      content()
    }
  }
}

@Composable
internal fun SectionHeader(icon: ImageVector, title: String, subtitle: String? = null) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    Column {
      Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
      if (!subtitle.isNullOrBlank()) {
        Text(
          subtitle,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
internal fun KeyValue(label: String, value: String) {
  if (value.isBlank()) return
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
    Text(
      label,
      modifier = Modifier.width(64.dp),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.outline,
    )
    Text(
      value,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
internal fun PerfGrid(perf: StagePerf) {
  val rows = listOf(
    "总耗时" to formatMs(perf.totalMs),
    "感知" to "${formatMs(perf.perceptionMs)} / ${perf.perceptionAssetCount} 张 / 缓存 ${perf.perceptionCacheHits}",
    "browse" to formatMs(perf.browseMs),
    "audience" to formatMs(perf.audienceMs),
    "director" to formatMs(perf.directorMs),
    "editor" to formatMs(perf.editorMs),
    "critic" to formatMs(perf.criticMs),
    "render" to formatMs(perf.renderMs),
  )
  rows.forEach { (label, value) -> KeyValue(label, value) }
}

internal data class MetricDatum(
  val label: String,
  val value: String,
  val accent: Boolean = false,
)

@Composable
internal fun PanelCard(
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.surface,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = color,
    tonalElevation = 3.dp,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)),
    content = content,
  )
}

@Composable
internal fun MetricGrid(
  items: List<MetricDatum>,
  columns: Int = 3,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    items.chunked(columns).forEach { rowItems ->
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rowItems.forEach { item ->
          MetricBadge(
            label = item.label,
            value = item.value,
            accent = item.accent,
            modifier = Modifier.weight(1f),
          )
        }
        repeat(columns - rowItems.size) {
          Spacer(modifier = Modifier.weight(1f))
        }
      }
    }
  }
}

@Composable
internal fun MetricBadge(
  label: String,
  value: String,
  accent: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val container = if (accent) {
    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.78f)
  } else {
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
  }
  val content = if (accent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(10.dp),
    color = container,
    contentColor = content,
  ) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
      Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (accent) content.copy(alpha = 0.75f) else MaterialTheme.colorScheme.outline,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        value,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
internal fun StatusBadge(text: String, status: EventSelectionStatus) {
  val icon = when (status) {
    EventSelectionStatus.EXCLUDED -> Icons.Outlined.ErrorOutline
    EventSelectionStatus.SELECTED,
    EventSelectionStatus.COMPLETED,
    EventSelectionStatus.RESUME -> Icons.Outlined.CheckCircle
    else -> Icons.Outlined.Timer
  }
  val container = when (status) {
    EventSelectionStatus.SELECTED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f)
    EventSelectionStatus.EXCLUDED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.82f)
    EventSelectionStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.70f)
    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
  }
  val content = when (status) {
    EventSelectionStatus.SELECTED -> MaterialTheme.colorScheme.onPrimaryContainer
    EventSelectionStatus.EXCLUDED -> MaterialTheme.colorScheme.onErrorContainer
    EventSelectionStatus.COMPLETED -> MaterialTheme.colorScheme.onSecondaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  Surface(shape = RoundedCornerShape(9.dp), color = container, contentColor = content) {
    Row(
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
      Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
internal fun SignalTag(text: String, icon: ImageVector, accent: Boolean = false) {
  val container = if (accent) {
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
  } else {
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f)
  }
  val content = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
  Surface(shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
    Row(
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
      Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
  }
}

@Composable
internal fun ScoreMeter(label: String, score: Float, accent: Boolean = false) {
  val value = (score.coerceIn(0f, 1f) * 100).toInt()
  Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
      )
      Text(
        value.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        fontWeight = FontWeight.SemiBold,
      )
    }
    LinearProgressIndicator(
      progress = { score.coerceIn(0f, 1f) },
      modifier = Modifier
        .fillMaxWidth()
        .height(5.dp)
        .clip(RoundedCornerShape(999.dp)),
    )
  }
}

