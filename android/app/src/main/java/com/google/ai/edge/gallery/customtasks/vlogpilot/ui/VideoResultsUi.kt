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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
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
internal fun ResultEventCard(
  d: EventDecisions,
  onOpenIterationSheet: (String) -> Unit,
  onChangeStory: () -> Unit,
) {
  var showTimeline by remember { mutableStateOf(false) }
  // Toggles VideoPreview between the latest mp4 (d.mp4Path) and the previous
  // version (d.previousMp4Path). Local to each card; resets when the user
  // navigates away from this card.
  var showingPrevious by remember(d.eventId, d.mp4Path, d.previousMp4Path) { mutableStateOf(false) }
  val timeline = d.timelineFinal ?: d.timelineV1
  val title = storyTitle(d)
  val durationSec = timeline?.shots?.sumOf { it.durationSec.toDouble() } ?: 0.0
  val assetMap = remember(d.inputAssets) { d.inputAssets.associateBy { it.id } }
  val playablePath = if (showingPrevious) d.previousMp4Path else d.mp4Path
  val canShowPrevious = d.previousMp4Path != null && d.mp4Path != null

  PanelCard {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
          Icon(Icons.Outlined.Movie, contentDescription = null, modifier = Modifier.padding(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(
            videoSubtitle(d),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        // 上一版 toggle — visible only when an iteration has produced an
        // archived prior version. Acts as undo/redo: tap once to compare,
        // tap again to return.
        if (canShowPrevious) {
          IconButton(onClick = { showingPrevious = !showingPrevious }) {
            Icon(
              imageVector = Icons.Outlined.History,
              contentDescription = if (showingPrevious) "回到最新版" else "查看上一版",
              tint = if (showingPrevious) MaterialTheme.colorScheme.primary
                     else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      MetricGrid(
        items = listOf(
          MetricDatum("镜头", (timeline?.shots?.size ?: 0).toString()),
          MetricDatum("时长", formatSec(durationSec)),
          MetricDatum("素材", d.inputAssets.size.toString()),
          MetricDatum(
            label = "版本",
            value = if (showingPrevious) "上一版" else "v${d.versionCount.coerceAtLeast(1)}",
            accent = showingPrevious,
          ),
        ),
        columns = 2,
      )

      if (playablePath != null) {
        VideoPreview(mp4Path = playablePath)
      } else {
        Text(
          "这组故事还没有导出视频。可以再生成一次，或回到故事页换一组。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
          modifier = Modifier.weight(1f),
          onClick = { onOpenIterationSheet(d.eventId) },
          enabled = d.mp4Path != null,
        ) {
          Icon(Icons.Outlined.Tune, contentDescription = null)
          Text(text = "  改一改", fontWeight = FontWeight.SemiBold)
        }
        FilledTonalButton(
          modifier = Modifier.weight(1f),
          onClick = onChangeStory,
        ) {
          Text("换个故事")
        }
      }

      if (timeline != null) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          SectionHeader(Icons.Outlined.Edit, "最终时间线", "${timeline.shots.size} 个镜头")
          FilledTonalButton(onClick = { showTimeline = !showTimeline }) {
            Text(if (showTimeline) "收起" else "展开")
          }
        }
        if (showTimeline) {
          DecisionSection(icon = Icons.Outlined.Edit, title = "Timeline", subtitle = "按最终渲染顺序") {
            timeline.shots.forEach { shot -> ShotRow(shot = shot, asset = assetMap[shot.assetId]) }
          }
        }
      }
    }
  }
}

