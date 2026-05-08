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
) {
  PanelCard {
    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      SectionHeader(
        icon = Icons.Outlined.Movie,
        title = "帮你做一条回忆视频",
        subtitle = "我会从最近 30 天里找出适合剪成 vlog 的几组故事。",
      )
      if (state is PipelineState.Error) {
        Text(
          state.message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      } else {
        Text(
          "点一下开始，我会先帮你看相册，再把适合剪的视频故事放到这里。",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Button(modifier = Modifier.fillMaxWidth(), onClick = onStartClick) {
        Text("开始挑故事")
      }
      if (recentDecisions.isNotEmpty()) {
        HorizontalDivider()
        Text("最近生成", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        recentDecisions.forEach { decision ->
          Text(
            storyTitle(decision),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = onVideosClick) {
          Text("只看已生成的视频")
        }
      }
    }
  }
}

@Composable
internal fun StoryProgressCard(
  state: PipelineState,
  progress: ProgressSnapshot,
  onCancel: () -> Unit,
) {
  val friendly = friendlyProgress(progress, making = state is PipelineState.Running)
  val fraction = remember(friendly.current, friendly.total) {
    if (friendly.total > 0) (friendly.current.toFloat() / friendly.total.toFloat()).coerceIn(0f, 1f) else null
  }
  PanelCard {
    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            .height(7.dp)
            .clip(RoundedCornerShape(999.dp)),
        )
        Text(
          "已完成 ${friendly.current}/${friendly.total}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (progress.recent.isNotEmpty()) {
        Text(
          progress.recent.first(),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = onCancel) {
        Text("取消")
      }
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
          Text("制作过程", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StoryCategoryRail(
          categories = categories,
          selected = selectedCategory,
          candidates = manifest.candidates,
          onSelect = onCategorySelect,
          modifier = Modifier.width(84.dp),
        )

        Surface(
          modifier = Modifier.weight(1f),
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
  Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          "故事货架",
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
      FilledTonalButton(onClick = onStartClick, enabled = !running) { Text("重扫") }
    }

    if (selectedStories.isNotEmpty()) {
      val selectedTitle = if (selectedStories.size == 1) {
        storyTitle(selectedStories.first())
      } else {
        "${storyTitle(selectedStories.first())} 等 ${selectedStories.size} 组故事"
      }
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("已选择 ${selectedStories.size} 组", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
            Text(
              selectedTitle,
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onMakeSelectedStories, enabled = !running) {
              Text(if (running) "制作中" else "开始制作")
            }
            Text(
              if (running) "制作进行中" else "取消选择",
              modifier = Modifier.clickable(enabled = !running) { onClearOnly() },
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
              fontWeight = FontWeight.Medium,
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
        Surface(
          modifier = Modifier.clickable { onSortSelect(sort) },
          shape = RoundedCornerShape(999.dp),
          color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
          contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (active) 0f else 0.45f)),
        ) {
          Text(
            sort.label,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
      Spacer(modifier = Modifier.weight(1f))
      if (runConfig.pinnedEventIds.isNotEmpty()) {
        Text("已优先 ${runConfig.pinnedEventIds.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
      }
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

