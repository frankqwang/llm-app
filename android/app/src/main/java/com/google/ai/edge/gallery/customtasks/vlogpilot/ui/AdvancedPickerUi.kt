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
internal fun AdvancedTabPicker(
  selected: VlogPilotAdvancedTab,
  onSelect: (VlogPilotAdvancedTab) -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
  ) {
    Row(modifier = Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      VlogPilotAdvancedTab.entries.forEach { tab ->
        val active = tab == selected
        Surface(
          modifier = Modifier
            .weight(1f)
            .clickable { onSelect(tab) },
          shape = RoundedCornerShape(14.dp),
          color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
          contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
          tonalElevation = if (active) 2.dp else 0.dp,
        ) {
          Box(modifier = Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
            Text(tab.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }
  }
}

@Composable
internal fun AlbumPreviewCard(assets: List<Asset>, events: List<Event>) {
  PanelCard {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      SectionHeader(
        icon = Icons.Outlined.PhotoLibrary,
        title = "相册预扫",
        subtitle = "${assets.size} 个素材，${events.size} 个事件",
      )
      AssetStrip(assets = assets.take(18), totalCount = assets.size)
    }
  }
}

@Composable
internal fun StoryStatusBadge(status: EventSelectionStatus) {
  val text = when (status) {
    EventSelectionStatus.SELECTED -> "推荐"
    EventSelectionStatus.EXCLUDED -> "已忽略"
    EventSelectionStatus.COMPLETED -> "已生成"
    EventSelectionStatus.RESUME -> "可继续"
    else -> "可选择"
  }
  StatusBadge(text, status)
}

@Composable
internal fun CandidateDetails(candidate: EventCandidateSnapshot) {
  HorizontalDivider()
  SectionHeader(Icons.Outlined.Star, "排序指标", "综合分会决定候选优先级")
  ScoreMeter("综合价值", candidate.valueScore, accent = candidate.status == EventSelectionStatus.SELECTED)
  ScoreMeter("旅行/地点", candidate.travelScore)
  ScoreMeter("视频素材", candidate.mediaScore)
  ScoreMeter("故事跨度", candidate.storyScore)
  ScoreMeter("质量", candidate.qualityScore)
  ScoreMeter("新鲜度", candidate.recencyScore)

  if (candidate.scoutSummary.isNotBlank()) {
    HorizontalDivider()
    SectionHeader(
      Icons.Outlined.Visibility,
      "VLM scout",
      candidate.scoutEventType.ifBlank { "contact sheet 语义判断" },
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      MetricBadge("story", (candidate.scoutStoryValue * 100).toInt().toString(), modifier = Modifier.weight(1f))
      MetricBadge("visual", (candidate.scoutVisualValue * 100).toInt().toString(), modifier = Modifier.weight(1f))
      MetricBadge("pages", candidate.scoutPageCount.toString(), candidate.scoutSampled, Modifier.weight(1f))
    }
    Text(
      candidate.scoutSummary,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (candidate.scoutRejectReasons.isNotEmpty()) {
      Text(
        "caution: ${candidate.scoutRejectReasons.joinToString(" · ")}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }

  if (candidate.reasons.isNotEmpty()) {
    HorizontalDivider()
    KeyValue("排序理由", candidate.reasons.joinToString(" · "))
  }
}

