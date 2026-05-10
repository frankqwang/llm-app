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
  // Synchronous peek lets a cache hit render in the same frame (no flicker
  // when scrolling back), and the produceState only fires when we need a
  // real decode. Bitmaps live in ThumbnailCache; we don't recycle them here.
  val initialBitmap = remember(asset.id) {
    com.google.ai.edge.gallery.customtasks.vlogpilot.perception.ThumbnailCache.peek(asset, THUMB_MAX_SIDE)
  }
  val bitmap by produceState<Bitmap?>(initialValue = initialBitmap, asset.id) {
    if (value != null) return@produceState
    value = com.google.ai.edge.gallery.customtasks.vlogpilot.perception.ThumbnailCache
      .loadOrDecode(context, asset, maxSide = THUMB_MAX_SIDE)
  }

  Box(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
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
    if (showType && asset.mediaType == MediaType.VIDEO) {
      // Apple Photos pattern: only show duration pill on videos, not "IMG"
      // on every photo. Less visual noise in dense grids.
      Surface(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(4.dp),
        shape = RoundedCornerShape(6.dp),
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
      ) {
        Text(
          if (asset.durationMs > 0) formatSec(asset.durationMs / 1000.0) else "VID",
          modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
          fontSize = 10.sp,
          fontWeight = FontWeight.Medium,
        )
      }
    }
  }
}

private const val THUMB_MAX_SIDE = 240

@Composable
internal fun VideoPreview(mp4Path: String) {
  // 9:16 preview, capped to a compact size so the action buttons + shot rail
  // below stay visible without scrolling. The previous version filled the
  // full screen width and topped out at 540dp tall — on a phone that meant
  // the video dominated 60% of the screen and Android's MediaController bar
  // bled into the Save/Share buttons below it. We now:
  //   - cap width to ~220dp (height ≈ 390dp at 9:16) and center the player
  //   - tap-to-toggle play/pause via custom logic (no MediaController bar)
  //   - overlay a play/pause icon when paused so the affordance stays clear
  val playing = remember { mutableStateOf(false) }
  val failed = remember(mp4Path) { mutableStateOf(false) }
  Box(
    modifier = Modifier.fillMaxWidth(),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .widthIn(max = 220.dp)
        .aspectRatio(9f / 16f)
        .clip(RoundedCornerShape(20.dp))
        .background(Color.Black),
      contentAlignment = Alignment.Center,
    ) {
      AndroidView(
        factory = { ctx ->
          VideoView(ctx).apply {
            tag = mp4Path
            setVideoPath(mp4Path)
            setOnPreparedListener { player ->
              failed.value = false
              player.isLooping = true
              seekTo(1)
            }
            setOnErrorListener { _, _, _ ->
              failed.value = true
              playing.value = false
              true
            }
            seekTo(1)
            setOnClickListener {
              if (failed.value) return@setOnClickListener
              if (isPlaying) { pause(); playing.value = false }
              else { start(); playing.value = true }
            }
          }
        },
        update = { view ->
          if (view.tag != mp4Path) {
            view.tag = mp4Path
            failed.value = false
            view.setVideoPath(mp4Path)
            view.seekTo(1)
            playing.value = false
          }
        },
        modifier = Modifier.fillMaxSize(),
      )
      if (failed.value) {
        Text(
          "视频文件不可播放或已损坏",
          modifier = Modifier.padding(16.dp),
          style = MaterialTheme.typography.bodyMedium,
          color = Color.White.copy(alpha = 0.82f),
          textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
      } else if (!playing.value) {
        Box(
          modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black.copy(alpha = 0.45f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = androidx.compose.material.icons.Icons.Outlined.PlayArrow,
            contentDescription = "播放",
            tint = Color.White,
            modifier = Modifier.size(32.dp),
          )
        }
      }
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
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(tokens.spacing.lg),
      verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
    ) {
      SectionHeader(icon = icon, title = title, subtitle = subtitle)
      content()
    }
  }
}

@Composable
internal fun SectionHeader(icon: ImageVector, title: String, subtitle: String? = null) {
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  Row(
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(28.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(tokens.colors.accentTint),
      contentAlignment = Alignment.Center,
    ) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tokens.colors.accent)
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (!subtitle.isNullOrBlank()) {
        Text(
          subtitle,
          style = MaterialTheme.typography.labelMedium,
          color = tokens.colors.secondaryLabel,
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
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  // iOS Settings-style row: light label on the left, sharp on-surface value
  // on the right; both readable, with proper vertical breathing room.
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      label,
      modifier = Modifier.width(80.dp),
      style = MaterialTheme.typography.bodyMedium,
      color = tokens.colors.secondaryLabel,
    )
    Text(
      value,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
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
    shape = RoundedCornerShape(20.dp),
    color = color,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
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
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  val container = if (accent) tokens.colors.accentTint
                  else if (tokens.colors.isDark) tokens.colors.groupedSurfaceRaised
                  else MaterialTheme.colorScheme.surfaceVariant
  val accentText = if (accent) tokens.colors.accent else MaterialTheme.colorScheme.onSurface
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    color = container,
    tonalElevation = 0.dp,
  ) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = tokens.colors.secondaryLabel,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.size(2.dp))
      Text(
        value,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = accentText,
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

