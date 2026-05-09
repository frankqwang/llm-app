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
internal fun StoryHeroCard(
  state: PipelineState,
  recentDecisions: List<EventDecisions>,
  onStartClick: () -> Unit,
  onVideosClick: () -> Unit,
  onCurateClick: () -> Unit,
) {
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  PanelCard {
    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      SectionHeader(
        icon = Icons.Outlined.Movie,
        title = "创作一条 AI 回忆",
        subtitle = "我会从最近 90 天里找出适合轻剪辑的故事，也可以由你手动挑素材",
      )
      if (state is PipelineState.Error) {
        Text(
          state.message,
          style = MaterialTheme.typography.bodyMedium,
          color = tokens.colors.systemRed,
        )
      } else {
        Text(
          "点一下开始，我会先帮你看相册，再把适合剪的故事放到这里。",
          style = MaterialTheme.typography.bodyMedium,
          color = tokens.colors.secondaryLabel,
        )
      }
      com.google.ai.edge.gallery.customtasks.vlogpilot.ui.PrimaryActionButton(
        text = "扫描推荐",
        icon = Icons.Outlined.Search,
        onClick = onStartClick,
        modifier = Modifier.fillMaxWidth(),
      )
      // Secondary CTA — same level visually but "或者" framing makes it
      // optional. Power users who already know what they want skip the auto
      // discovery and go straight to curation.
      com.google.ai.edge.gallery.customtasks.vlogpilot.ui.PlainTextButton(
        text = "或者，我自己挑素材",
        onClick = onCurateClick,
        modifier = Modifier.fillMaxWidth(),
      )
      if (recentDecisions.isNotEmpty()) {
        com.google.ai.edge.gallery.customtasks.vlogpilot.ui.HairlineDivider(startInset = 0.dp)
        Text(
          "最近生成",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        recentDecisions.forEach { decision ->
          Text(
            storyTitle(decision),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        com.google.ai.edge.gallery.customtasks.vlogpilot.ui.TintedActionButton(
          text = "查看作品",
          icon = Icons.Outlined.Movie,
          onClick = onVideosClick,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

/** Compact "AI is making one for you, tap to see progress" hint shown on the
 *  Works tab while a pipeline is running. The full per-stage view lives in
 *  Chat (which is the source of truth for live progress) — Works should stay
 *  about finished works, not about process. */
@Composable
internal fun PipelineRunningHint(
  progress: ProgressSnapshot,
  onTap: () -> Unit,
  onCancel: () -> Unit,
) {
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  val subtitle = progress.headline.ifBlank { "AI 正在为你制作 vlog" }
  androidx.compose.material3.Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onTap() },
    shape = RoundedCornerShape(16.dp),
    color = tokens.colors.accentTint,
    tonalElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(36.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(tokens.colors.accent.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Outlined.Movie,
          contentDescription = null,
          tint = tokens.colors.accent,
          modifier = Modifier.size(20.dp),
        )
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          "AI 正在制作中",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = tokens.colors.accent,
        )
        Text(
          "$subtitle · 点这里看进度",
          style = MaterialTheme.typography.labelSmall,
          color = tokens.colors.secondaryLabel,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      androidx.compose.material3.TextButton(onClick = onCancel) {
        Text("取消", color = tokens.colors.systemRed, style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}

@Composable
internal fun StoryProgressCard(
  state: PipelineState,
  progress: ProgressSnapshot,
  liveDecision: com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions?,
  onCancel: () -> Unit,
) {
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  val friendly = friendlyProgress(progress, making = state is PipelineState.Running)
  val fraction = remember(friendly.current, friendly.total) {
    if (friendly.total > 0) (friendly.current.toFloat() / friendly.total.toFloat()).coerceIn(0f, 1f) else null
  }
  PanelCard {
    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      SectionHeader(
        icon = if (state is PipelineState.Running) Icons.Outlined.Movie else Icons.Outlined.Search,
        title = friendly.title,
        subtitle = friendly.detail,
      )
      if (fraction != null) {
        LinearProgressIndicator(
          progress = { fraction },
          modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp)),
        )
        Text(
          "已完成 ${friendly.current}/${friendly.total}",
          style = MaterialTheme.typography.labelMedium,
          color = tokens.colors.secondaryLabel,
        )
      }
      // Live agent perspective — shows each agent's actual output as it
      // becomes available. The current agent's dot pulses; previous agents
      // get a green check + collapsible card to inspect what they produced.
      if (liveDecision != null) {
        Text(
          "AI 正在制作",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = tokens.colors.secondaryLabel,
        )
        com.google.ai.edge.gallery.customtasks.vlogpilot.ui.AgentWorkPanel(
          d = liveDecision,
          live = state is PipelineState.Running,
        )
      }
      com.google.ai.edge.gallery.customtasks.vlogpilot.ui.TintedActionButton(
        text = "取消",
        onClick = onCancel,
        tint = tokens.colors.systemRed,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
internal fun LiveProcessCard(
  progress: ProgressSnapshot,
  decisions: List<EventDecisions>,
) {
  PanelCard {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      SectionHeader(
        icon = Icons.Outlined.Visibility,
        title = "实时制作过程",
        subtitle = "这里会跟着后台任务更新，不用切到高级页。",
      )
      if (progress.recent.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          progress.recent.take(6).forEachIndexed { index, item ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.Top,
            ) {
              Surface(
                modifier = Modifier.size(20.dp),
                shape = RoundedCornerShape(999.dp),
                color = if (index == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (index == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Text((index + 1).toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
              }
              Text(
                item,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      } else {
        Text(
          progress.detail.ifBlank { "等待后台任务上报进度。" },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (decisions.isNotEmpty()) {
        HorizontalDivider()
        decisions.take(4).forEach { decision ->
          LiveDecisionRow(decision)
        }
      }
    }
  }
}

@Composable
internal fun LiveDecisionRow(decision: EventDecisions) {
  val timeline = decision.timelineFinal ?: decision.timelineV1
  val status = when {
    decision.mp4Path != null -> "已出片"
    timeline != null -> "已排好镜头"
    decision.director != null -> "正在编排"
    decision.memory != null -> "已理解故事"
    decision.inputAssets.isNotEmpty() -> "已准备素材"
    else -> "等待处理"
  }
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = if (decision.mp4Path != null) Icons.Outlined.CheckCircle else Icons.Outlined.Timer,
      contentDescription = null,
      modifier = Modifier.size(18.dp),
      tint = if (decision.mp4Path != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        storyTitle(decision),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        status,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
internal fun InlineProcessPanel(
  progress: ProgressSnapshot,
  decisions: List<EventDecisions>,
  onCancel: () -> Unit,
) {
  val friendly = friendlyProgress(progress, making = true)
  val refreshOnly = isStoryRefreshProgress(progress)
  val fraction = remember(friendly.current, friendly.total) {
    if (friendly.total > 0) (friendly.current.toFloat() / friendly.total.toFloat()).coerceIn(0f, 1f) else null
  }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
      ) {
        Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(if (refreshOnly) "重扫过程" else "制作过程", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
          Text(
            friendly.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        FilledTonalButton(onClick = onCancel) {
          Text("取消")
        }
      }

      if (fraction != null) {
        LinearProgressIndicator(
          progress = { fraction },
          modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp)),
        )
        Text(
          "已完成 ${friendly.current}/${friendly.total}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Text(
          friendly.detail,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }

      val recent = progress.recent.take(3)
      if (recent.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
          recent.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
              Surface(
                modifier = Modifier
                  .padding(top = 4.dp)
                  .size(6.dp),
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.primary,
              ) {}
              Text(
                item,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }

      if (decisions.isNotEmpty()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        decisions.take(2).forEach { decision ->
          LiveDecisionRow(decision)
        }
      }
    }
  }
}

@Composable
internal fun StoryShelf(
  manifest: EventSelectionManifest,
  runConfig: VlogPilotRunConfig,
  running: Boolean,
  progress: ProgressSnapshot,
  decisions: List<EventDecisions>,
  selectedCategory: StoryBrowseCategory,
  selectedSort: StorySortMode,
  onCategorySelect: (StoryBrowseCategory) -> Unit,
  onSortSelect: (StorySortMode) -> Unit,
  onOpenStory: (String) -> Unit,
  onStartClick: () -> Unit,
  onClearOnly: () -> Unit,
  onCancel: () -> Unit,
  onToggleStory: (String) -> Unit,
  onMakeSelectedStories: () -> Unit,
) {
  val categories = storyBrowseCategories(manifest.candidates)
  val filtered = manifest.candidates
    .filter { matchesStoryCategory(it, selectedCategory) }
    .sortedWith(storySortComparator(selectedSort))
  PanelCard(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f)) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      StoryShelfHeader(
        manifest = manifest,
        runConfig = runConfig,
        running = running,
        progress = progress,
        decisions = decisions,
        selectedStories = manifest.candidates.filter { it.eventId in runConfig.onlySelectedEventIds },
        shownCount = filtered.size,
        selectedSort = selectedSort,
        onSortSelect = onSortSelect,
        onStartClick = onStartClick,
        onClearOnly = onClearOnly,
        onCancel = onCancel,
        onMakeSelectedStories = onMakeSelectedStories,
      )

      StoryCategoryChips(
        categories = categories,
        selected = selectedCategory,
        candidates = manifest.candidates,
        onSelect = onCategorySelect,
      )

      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
      ) {
        Column {
          if (filtered.isEmpty()) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text("这一类暂时没有故事", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          } else {
            filtered.forEachIndexed { index, candidate ->
              StoryListItem(
                candidate = candidate,
                selected = candidate.eventId in runConfig.onlySelectedEventIds,
                enabled = !running,
                onOpen = { onOpenStory(candidate.eventId) },
                onToggle = { onToggleStory(candidate.eventId) },
              )
              if (index != filtered.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun StoryShelfHeader(
  manifest: EventSelectionManifest,
  runConfig: VlogPilotRunConfig,
  running: Boolean,
  progress: ProgressSnapshot,
  decisions: List<EventDecisions>,
  selectedStories: List<EventCandidateSnapshot>,
  shownCount: Int,
  selectedSort: StorySortMode,
  onSortSelect: (StorySortMode) -> Unit,
  onStartClick: () -> Unit,
  onClearOnly: () -> Unit,
  onCancel: () -> Unit,
  onMakeSelectedStories: () -> Unit,
) {
  val refreshOnly = running && isStoryRefreshProgress(progress)
  Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          "创作台",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          "${manifest.candidateCount} 组故事 · 当前显示 $shownCount 组 · ${friendlyIntentLabel(manifest.intent)} · ${friendlyPowerLabel(manifest.powerProfile)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      com.google.ai.edge.gallery.customtasks.vlogpilot.ui.TintedActionButton(
        text = "重扫",
        icon = Icons.Outlined.Search,
        onClick = onStartClick,
        enabled = !running,
      )
    }

    if (selectedStories.isNotEmpty()) {
      val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
      val selectedTitle = if (selectedStories.size == 1) {
        storyTitle(selectedStories.first())
      } else {
        "${storyTitle(selectedStories.first())} 等 ${selectedStories.size} 组故事"
      }
      // Subtle accent-tinted card — accent for icon and emphasis text only,
      // with a clear PrimaryActionButton on the right. Avoids the all-blue
      // wash that drowned the title in earlier iterations.
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = tokens.colors.accentTint,
        tonalElevation = 0.dp,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            modifier = Modifier
              .size(34.dp)
              .clip(RoundedCornerShape(50))
              .background(tokens.colors.accent),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              Icons.Outlined.CheckCircle,
              contentDescription = null,
              tint = Color.White,
              modifier = Modifier.size(20.dp),
            )
          }
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
              "已选择 ${selectedStories.size} 组",
              style = MaterialTheme.typography.labelMedium,
              color = tokens.colors.accent,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              selectedTitle,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (running) {
              Text(
                if (refreshOnly) "正在更新候选" else "制作进行中",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.secondaryLabel,
              )
            } else {
              Text(
                "取消选择",
                modifier = Modifier.clickable { onClearOnly() },
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.secondaryLabel,
              )
            }
          }
          if (running) {
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(tokens.colors.accent.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
              Text(
                if (refreshOnly) "重扫中" else "制作中",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
              )
            }
          } else {
            com.google.ai.edge.gallery.customtasks.vlogpilot.ui.PrimaryActionButton(
              text = "开始制作",
              onClick = onMakeSelectedStories,
            )
          }
        }
      }
    }

    if (running) {
      InlineProcessPanel(
        progress = progress,
        decisions = decisions,
        onCancel = onCancel,
      )
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("排序", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
      StorySortMode.entries.forEach { sort ->
        val active = sort == selectedSort
        com.google.ai.edge.gallery.customtasks.vlogpilot.ui.CapsuleChip(
          text = sort.label,
          selected = active,
          onClick = { onSortSelect(sort) },
        )
      }
      Spacer(modifier = Modifier.weight(1f))
      if (runConfig.pinnedEventIds.isNotEmpty()) {
        Text("已优先 ${runConfig.pinnedEventIds.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
      }
    }
  }
}

private fun isStoryRefreshProgress(progress: ProgressSnapshot): Boolean =
  isStoryRefreshStage(progress.stage)

@Composable
internal fun StoryCategoryChips(
  categories: List<StoryBrowseCategory>,
  selected: StoryBrowseCategory,
  candidates: List<EventCandidateSnapshot>,
  onSelect: (StoryBrowseCategory) -> Unit,
) {
  LazyRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    contentPadding = PaddingValues(horizontal = 1.dp),
  ) {
    items(categories) { category ->
      val count = candidates.count { matchesStoryCategory(it, category) }
      com.google.ai.edge.gallery.customtasks.vlogpilot.ui.CapsuleChip(
        text = if (count > 0) "${category.label} $count" else category.label,
        selected = category == selected,
        onClick = { onSelect(category) },
      )
    }
  }
}

@Composable
internal fun StoryCategoryRail(
  categories: List<StoryBrowseCategory>,
  selected: StoryBrowseCategory,
  candidates: List<EventCandidateSnapshot>,
  onSelect: (StoryBrowseCategory) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f)),
  ) {
    Column {
      categories.forEach { category ->
        val active = category == selected
        val count = candidates.count { matchesStoryCategory(it, category) }
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(category) },
          shape = RoundedCornerShape(0.dp),
          color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.70f) else Color.Transparent,
          contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
          Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
              category.label,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              "$count 组",
              style = MaterialTheme.typography.labelSmall,
              color = if (active) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f) else MaterialTheme.colorScheme.outline,
            )
          }
        }
      }
    }
  }
}

@Composable
internal fun StoryListItem(
  candidate: EventCandidateSnapshot,
  selected: Boolean,
  enabled: Boolean,
  onOpen: () -> Unit,
  onToggle: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onOpen() }
      .padding(10.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val cover = candidate.assets.firstOrNull()
    if (cover != null) {
      AssetThumb(
        asset = cover,
        modifier = Modifier
          .width(70.dp)
          .height(76.dp),
      )
    } else {
      Box(
        modifier = Modifier
          .width(70.dp)
          .height(76.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Text(
          storyTitle(candidate),
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (candidate.event.userCuration != null) UserCuratedBadge()
        StoryTinyStatus(candidate.status)
      }
      Text(
        compactStoryMeta(candidate),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
          "适合度 ${(candidate.valueScore.coerceIn(0f, 1f) * 100).toInt()}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          storyListHint(candidate),
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.outline,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Button(onClick = onToggle, enabled = enabled) {
      Text(if (selected) "已选" else "选")
    }
  }
}

/** Tiny "你做的" tag rendered next to the title for user-curated stories.
 *  Same shape as StoryTinyStatus so they sit neatly side by side. */
@Composable
internal fun UserCuratedBadge() {
  Surface(
    shape = RoundedCornerShape(7.dp),
    color = MaterialTheme.colorScheme.tertiaryContainer,
    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
  ) {
    Text(
      "你做的",
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
internal fun StoryTinyStatus(status: EventSelectionStatus) {
  val text = when (status) {
    EventSelectionStatus.SELECTED -> "荐"
    EventSelectionStatus.EXCLUDED -> "隐"
    EventSelectionStatus.COMPLETED -> "成"
    EventSelectionStatus.RESUME -> "续"
    else -> "看"
  }
  val container = when (status) {
    EventSelectionStatus.SELECTED -> MaterialTheme.colorScheme.primaryContainer
    EventSelectionStatus.EXCLUDED -> MaterialTheme.colorScheme.errorContainer
    EventSelectionStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
  }
  val content = when (status) {
    EventSelectionStatus.SELECTED -> MaterialTheme.colorScheme.onPrimaryContainer
    EventSelectionStatus.EXCLUDED -> MaterialTheme.colorScheme.onErrorContainer
    EventSelectionStatus.COMPLETED -> MaterialTheme.colorScheme.onSecondaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  Surface(shape = RoundedCornerShape(7.dp), color = container, contentColor = content) {
    Text(
      text,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
internal fun StoryDetailDialog(
  candidate: EventCandidateSnapshot,
  runConfig: VlogPilotRunConfig,
  onDismiss: () -> Unit,
  onDislike: (String) -> Unit,
  onUndoDislike: (String) -> Unit,
  onMake: (String) -> Unit,
  onPin: (String) -> Unit,
  onRegenerate: (String) -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 720.dp),
      shape = RoundedCornerShape(22.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 8.dp,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
    ) {
      LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
          ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(
                storyTitle(candidate),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                storyMeta(candidate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            StoryStatusBadge(candidate.status)
          }
        }
        item {
          Text(
            storyReason(candidate),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        item {
          AssetStrip(assets = candidate.assets.take(12), totalCount = candidate.event.assetIds.size)
        }
        item {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              modifier = Modifier.weight(1f),
              onClick = { onMake(candidate.eventId) },
            ) {
              Text("开始制作")
            }
            FilledTonalButton(
              modifier = Modifier.weight(1f),
              onClick = {
                if (candidate.status == EventSelectionStatus.EXCLUDED) {
                  onUndoDislike(candidate.eventId)
                } else {
                  onDislike(candidate.eventId)
                }
              },
            ) {
              Text(if (candidate.status == EventSelectionStatus.EXCLUDED) "撤回" else "不喜欢")
            }
          }
        }
        item {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
              modifier = Modifier.weight(1f),
              onClick = { onPin(candidate.eventId) },
            ) {
              Text(if (candidate.eventId in runConfig.pinnedEventIds) "已优先" else "优先做")
            }
            FilledTonalButton(
              modifier = Modifier.weight(1f),
              onClick = { onRegenerate(candidate.eventId) },
            ) {
              Text("重新剪")
            }
          }
        }
        item {
          CandidateDetails(candidate)
        }
        item {
          FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = onDismiss) {
            Text("关闭")
          }
        }
      }
    }
  }
}

