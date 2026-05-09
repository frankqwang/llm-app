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
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
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

internal enum class VlogPilotTab(val label: String, val icon: ImageVector) {
  // Tab 1 (default): merged Create + Works — completed vlogs and AI-clustered
  // candidate stories share one feed. Click a candidate to start a chat that
  // creates it, click a completed vlog to open its detail page.
  Works("作品", Icons.Outlined.Movie),
  // Tab 2: Claude-style chat where the user creates / refines vlogs by
  // talking to the agent chain. Replaces the procedural Create flow.
  Chat("对话", Icons.AutoMirrored.Outlined.Chat),
  // Tab 3: full album browser (existing).
  Assets("相册", Icons.Outlined.PhotoLibrary),
  // Tab 4: app preferences.
  Settings("设置", Icons.Outlined.Settings),
}

internal enum class VlogPilotAdvancedTab(val label: String) {
  Process("技术过程"),
  Assets("素材详情"),
  Prompts("Prompt"),
}

internal enum class StoryBrowseCategory(val label: String) {
  Recommended("推荐"),
  All("全部"),
  Travel("旅行"),
  Family("家人"),
  Food("美食"),
  Animal("动物"),
  Video("视频多"),
  Done("已生成"),
  Hidden("不喜欢"),
}

internal enum class StorySortMode(val label: String) {
  Recommended("推荐"),
  Newest("最新"),
  Oldest("最早"),
}

/**
 * Apple-style segmented tab bar. Each tab is a capsule that fades from gray
 * to accent-tint when selected; selection animates with a subtle spring so
 * the bar feels alive rather than flicker-switching state.
 */
@Composable
internal fun WorkspaceTabs(selected: VlogPilotTab, onSelect: (VlogPilotTab) -> Unit) {
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = tokens.spacing.pageInset),
    shape = RoundedCornerShape(50),
    color = if (tokens.colors.isDark) tokens.colors.groupedSurfaceRaised
            else MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(4.dp),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      VlogPilotTab.entries.forEach { tab ->
        val active = tab == selected
        val containerColor by androidx.compose.animation.animateColorAsState(
          targetValue = if (active) tokens.colors.accentTint else Color.Transparent,
          animationSpec = androidx.compose.animation.core.tween(durationMillis = 220),
          label = "tab-bg",
        )
        val contentColor by androidx.compose.animation.animateColorAsState(
          targetValue = if (active) tokens.colors.accent else tokens.colors.secondaryLabel,
          animationSpec = androidx.compose.animation.core.tween(durationMillis = 220),
          label = "tab-fg",
        )
        Surface(
          modifier = Modifier
            .weight(1f)
            .clickable(
              interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
              indication = null,
              onClick = { onSelect(tab) },
            ),
          shape = RoundedCornerShape(50),
          color = containerColor,
          contentColor = contentColor,
          tonalElevation = 0.dp,
          shadowElevation = 0.dp,
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(tab.icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
              tab.label,
              style = MaterialTheme.typography.labelLarge,
              fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            )
          }
        }
      }
    }
  }
}

@Composable
internal fun <T> ControlSegment(
  label: String,
  options: List<T>,
  selected: T,
  optionLabel: (T) -> String,
  onSelect: (T) -> Unit,
  enabled: Boolean,
) {
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      label,
      style = MaterialTheme.typography.labelMedium,
      color = tokens.colors.secondaryLabel,
    )
    if (!enabled) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (tokens.colors.isDark) tokens.colors.groupedSurfaceRaised
                else MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            optionLabel(selected),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            "运行中锁定",
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.tertiaryLabel,
          )
        }
      }
    } else {
      LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options) { option ->
          com.google.ai.edge.gallery.customtasks.vlogpilot.ui.CapsuleChip(
            text = optionLabel(option),
            selected = option == selected,
            onClick = { onSelect(option) },
          )
        }
      }
    }
  }
}

