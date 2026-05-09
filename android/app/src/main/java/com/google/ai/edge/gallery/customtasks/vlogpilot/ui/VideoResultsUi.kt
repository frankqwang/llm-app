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
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowBack
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
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.google.ai.edge.gallery.customtasks.vlogpilot.export.VlogExporter
import com.google.ai.edge.gallery.customtasks.vlogpilot.ui.AnimatedExpand
import com.google.ai.edge.gallery.customtasks.vlogpilot.ui.CapsuleChip
import com.google.ai.edge.gallery.customtasks.vlogpilot.ui.HairlineDivider
import com.google.ai.edge.gallery.customtasks.vlogpilot.ui.InsetGroupedSurface
import com.google.ai.edge.gallery.customtasks.vlogpilot.ui.LargeTitleHeader
import com.google.ai.edge.gallery.customtasks.vlogpilot.ui.TintedActionButton
import com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.MediaLoader
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.GenerationIntent
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.PowerProfile
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.VlogPilotRunConfig
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.IterationHistory
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.VlmTags
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventCandidateSnapshot
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionManifest
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionStatus
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.IterationStore
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.StagePerf
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class VideoBrowseCategory(val label: String) {
  All("全部"),
  Iterated("已优化"),
  FirstCut("初版"),
  Short("短片"),
  Rich("素材多"),
  Draft("未导出"),
}

internal enum class VideoSortMode(val label: String) {
  Recent("最近"),
  Version("版本"),
  Duration("时长"),
}

@Composable
internal fun VideoShelf(
  projects: List<VlogProject> = emptyList(),
  decisions: List<EventDecisions>,
  activeIteration: IterationSnapshot?,
  selectedCategory: VideoBrowseCategory,
  selectedSort: VideoSortMode,
  onCategorySelect: (VideoBrowseCategory) -> Unit,
  onSortSelect: (VideoSortMode) -> Unit,
  onOpenDetail: (String) -> Unit,
  onDismissIteration: () -> Unit,
) {
  // Counts and category list depend only on decisions; filtered list also
  // depends on the user's category/sort choice. Each remember key is the
  // minimum the result actually depends on — recomputing 6 chip counts
  // every recomposition was the previous behavior.
  val draftProjects = remember(projects, decisions) {
    val decisionEventIds = decisions.map { it.eventId }.toSet()
    projects.filter { project ->
      project.sourceEventId !in decisionEventIds && project.projectId !in decisionEventIds
    }
  }
  val categoryCounts = remember(decisions, draftProjects) {
    VideoBrowseCategory.entries.associateWith { cat ->
      decisions.count { matchesVideoCategory(it, cat) } + when (cat) {
        VideoBrowseCategory.All,
        VideoBrowseCategory.Draft -> draftProjects.size
        else -> 0
      }
    }
  }
  val categories = remember(decisions, draftProjects) { videoBrowseCategories(decisions, draftProjects) }
  val filtered = remember(decisions, selectedCategory, selectedSort) {
    decisions
      .filter { matchesVideoCategory(it, selectedCategory) }
      .sortedWith(videoSortComparator(selectedSort))
  }
  val visibleDraftProjects = remember(draftProjects, selectedCategory) {
    if (selectedCategory == VideoBrowseCategory.All || selectedCategory == VideoBrowseCategory.Draft) draftProjects else emptyList()
  }

  Column(verticalArrangement = Arrangement.spacedBy(VlogPilotTokens.spacing.sm)) {
    VideoShelfHeader(
      projects = projects,
      decisions = decisions,
      shownCount = filtered.size + visibleDraftProjects.size,
      selectedSort = selectedSort,
      activeIteration = activeIteration,
      onSortSelect = onSortSelect,
      onDismissIteration = onDismissIteration,
    )

    VideoCategoryChips(
      categories = categories,
      selected = selectedCategory,
      counts = categoryCounts,
      onSelect = onCategorySelect,
    )

    InsetGroupedSurface {
      if (filtered.isEmpty() && visibleDraftProjects.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(VlogPilotTokens.spacing.xl),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            "这一类暂时没有视频",
            style = MaterialTheme.typography.bodyMedium,
            color = VlogPilotTokens.colors.secondaryLabel,
          )
        }
      } else {
        filtered.forEachIndexed { index, decision ->
          val project = projectForDecision(projects, decision)
          VideoListItem(
            d = decision,
            project = project,
            onOpen = { onOpenDetail(decision.eventId) },
          )
          if (index != filtered.lastIndex || visibleDraftProjects.isNotEmpty()) {
            HairlineDivider(startInset = 96.dp)
          }
        }
        visibleDraftProjects.forEachIndexed { index, project ->
          ProjectDraftListItem(project)
          if (index != visibleDraftProjects.lastIndex) {
            HairlineDivider(startInset = 96.dp)
          }
        }
      }
    }

    Spacer(Modifier.size(VlogPilotTokens.spacing.lg))
  }
}

@Composable
private fun VideoShelfHeader(
  projects: List<VlogProject>,
  decisions: List<EventDecisions>,
  shownCount: Int,
  selectedSort: VideoSortMode,
  activeIteration: IterationSnapshot?,
  onSortSelect: (VideoSortMode) -> Unit,
  onDismissIteration: () -> Unit,
) {
  val readyCount = remember(decisions) { decisions.count { it.mp4Path != null } }
  val iteratedCount = remember(decisions) {
    decisions.count { it.versionCount > 1 || it.previousMp4Path != null }
  }
  val projectCount = remember(projects, decisions) { projects.size.coerceAtLeast(decisions.size) }
  Column(verticalArrangement = Arrangement.spacedBy(VlogPilotTokens.spacing.sm)) {
    LargeTitleHeader(
      title = "作品库",
      subtitle = "$projectCount 个作品 · ${readyCount} 条可播放 · ${iteratedCount} 条已优化",
    )

    activeIteration?.let {
      Box(modifier = Modifier.padding(horizontal = VlogPilotTokens.spacing.pageInset)) {
        IterationProgressStrip(snapshot = it, onDismiss = onDismissIteration)
      }
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = VlogPilotTokens.spacing.pageInset),
      horizontalArrangement = Arrangement.spacedBy(VlogPilotTokens.spacing.xs),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "排序",
        style = MaterialTheme.typography.labelMedium,
        color = VlogPilotTokens.colors.secondaryLabel,
      )
      Spacer(Modifier.width(VlogPilotTokens.spacing.xxs))
      VideoSortMode.entries.forEach { sort ->
        CapsuleChip(
          text = sort.label,
          selected = sort == selectedSort,
          onClick = { onSortSelect(sort) },
        )
      }
    }
  }
}

@Composable
private fun VideoCategoryChips(
  categories: List<VideoBrowseCategory>,
  selected: VideoBrowseCategory,
  counts: Map<VideoBrowseCategory, Int>,
  onSelect: (VideoBrowseCategory) -> Unit,
) {
  LazyRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(VlogPilotTokens.spacing.xs),
    contentPadding = PaddingValues(horizontal = VlogPilotTokens.spacing.pageInset),
  ) {
    items(categories) { category ->
      val count = counts[category] ?: 0
      CapsuleChip(
        text = if (count > 0) "${category.label} $count" else category.label,
        selected = category == selected,
        onClick = { onSelect(category) },
      )
    }
  }
}

@Composable
private fun VideoListItem(
  d: EventDecisions,
  project: VlogProject?,
  onOpen: () -> Unit,
) {
  val timeline = d.videoTimeline()
  val durationSec = d.videoDurationSec()
  val cover = d.videoCoverAsset()

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onOpen() }
      .padding(horizontal = VlogPilotTokens.spacing.lg, vertical = VlogPilotTokens.spacing.md),
    horizontalArrangement = Arrangement.spacedBy(VlogPilotTokens.spacing.md),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(72.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(VlogPilotTokens.colors.groupedSurfaceRaised),
    ) {
      if (cover != null) {
        AssetThumb(
          asset = cover,
          modifier = Modifier.size(72.dp),
        )
      } else {
        Box(
          modifier = Modifier.size(72.dp),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            Icons.Outlined.Movie,
            contentDescription = null,
            tint = VlogPilotTokens.colors.tertiaryLabel,
          )
        }
      }
      // Duration pill bottom-right of the thumbnail (Apple Photos pattern)
      Box(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(4.dp)
          .clip(RoundedCornerShape(6.dp))
          .background(Color.Black.copy(alpha = 0.55f))
          .padding(horizontal = 5.dp, vertical = 1.dp),
      ) {
        Text(
          formatSec(durationSec),
          style = MaterialTheme.typography.labelSmall,
          color = Color.White,
          fontWeight = FontWeight.Medium,
        )
      }
    }

    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
        project?.title ?: storyTitle(d),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        videoCompactMeta(d, project),
        style = MaterialTheme.typography.bodySmall,
        color = VlogPilotTokens.colors.secondaryLabel,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(VlogPilotTokens.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "${timeline?.shots?.size ?: 0} 镜头 · ${d.inputAssets.size} 素材${if (d.versionCount > 1) " · v${d.versionCount}" else ""}",
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.labelMedium,
          color = VlogPilotTokens.colors.tertiaryLabel,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        d.event?.userCuration?.let { UserCuratedBadge() }
        VideoStatusPill(d, project)
      }
    }

    Icon(
      imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
      contentDescription = "打开详情",
      tint = VlogPilotTokens.colors.tertiaryLabel,
      modifier = Modifier.size(20.dp),
    )
  }
}

@Composable
private fun VideoStatusPill(d: EventDecisions, project: VlogProject?) {
  val label = when {
    project?.status == ProjectStatus.FAILED -> "失败"
    project?.status == ProjectStatus.MAKING -> "制作中"
    project?.status == ProjectStatus.OPTIMIZING -> "优化中"
    project?.status == ProjectStatus.DRAFT -> "草稿"
    d.mp4Path == null -> "未导出"
    d.versionCount > 1 || d.previousMp4Path != null -> "已优化"
    else -> "初版"
  }
  val tint = when {
    project?.status == ProjectStatus.FAILED -> VlogPilotTokens.colors.systemRed
    project?.status == ProjectStatus.MAKING || project?.status == ProjectStatus.OPTIMIZING ->
      VlogPilotTokens.colors.systemOrange
    d.mp4Path == null -> VlogPilotTokens.colors.tertiaryLabel
    d.versionCount > 1 || d.previousMp4Path != null -> VlogPilotTokens.colors.systemGreen
    else -> VlogPilotTokens.colors.accent
  }
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(tint.copy(alpha = if (VlogPilotTokens.colors.isDark) 0.22f else 0.14f))
      .padding(horizontal = 7.dp, vertical = 2.dp),
  ) {
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      color = tint,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

/**
 * Full-screen vlog detail page. Replaces the previous inline expand pattern —
 * tapping a row in [VideoShelf] navigates here. Ships with a translucent back
 * bar at the top so users always know how to return to the list.
 */
@Composable
internal fun VlogDetailScreen(
  decisions: List<EventDecisions>,
  projects: List<VlogProject>,
  eventId: String,
  onBack: () -> Unit,
  onOpenIterationSheet: (String, Int?) -> Unit,
  onChangeStory: () -> Unit,
) {
  val tokens = VlogPilotTokens
  val decision = remember(decisions, eventId) { decisions.firstOrNull { it.eventId == eventId } }
  val project = remember(projects, decision) { decision?.let { projectForDecision(projects, it) } }

  androidx.compose.foundation.lazy.LazyColumn(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.background),
    contentPadding = PaddingValues(top = tokens.spacing.sm, bottom = tokens.spacing.xxl),
  ) {
    item(key = "detail-back-bar") {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = tokens.spacing.xs, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onBack) {
          Icon(
            Icons.Outlined.ArrowBack,
            contentDescription = "返回",
            tint = tokens.colors.accent,
          )
        }
        Text(
          decision?.let { project?.title ?: storyTitle(it) } ?: "作品详情",
          modifier = Modifier
            .weight(1f)
            .padding(horizontal = 4.dp),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onBackground,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      HairlineDivider(startInset = 0.dp)
      Spacer(Modifier.height(tokens.spacing.sm))
    }
    if (decision == null) {
      item(key = "detail-missing") {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(tokens.spacing.xxl),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            "找不到这条作品。它可能已经被刷新清理。",
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.secondaryLabel,
          )
        }
      }
    } else {
      item(key = "detail-content") {
        VideoExpandedDetail(
          d = decision,
          project = project,
          onOpenIterationSheet = onOpenIterationSheet,
          onChangeStory = onChangeStory,
        )
      }
    }
  }
}

@Composable
private fun VideoExpandedDetail(
  d: EventDecisions,
  project: VlogProject?,
  onOpenIterationSheet: (String, Int?) -> Unit,
  onChangeStory: () -> Unit,
) {
  var showingPrevious by remember(d.eventId, d.mp4Path, d.previousMp4Path) { mutableStateOf(false) }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val timeline = d.videoTimeline()
  val assetMap = remember(d.inputAssets) { d.inputAssets.associateBy { it.id } }
  val playablePath = if (showingPrevious) d.previousMp4Path else d.mp4Path
  val canShowPrevious = d.previousMp4Path != null && d.mp4Path != null
  val history by produceState<IterationHistory?>(initialValue = null, d.eventId, d.versionCount) {
    value = withContext(Dispatchers.IO) { IterationStore.loadHistory(context, d.eventId) }
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(
        start = VlogPilotTokens.spacing.lg,
        end = VlogPilotTokens.spacing.lg,
        top = VlogPilotTokens.spacing.xs,
        bottom = VlogPilotTokens.spacing.lg,
      ),
    verticalArrangement = Arrangement.spacedBy(VlogPilotTokens.spacing.md),
  ) {
    if (playablePath != null) {
      // Video on top with an optional translucent History toggle pinned to
      // the corner — same affordance as before, no extra row stealing
      // vertical space.
      Box(modifier = Modifier.fillMaxWidth()) {
        VideoPreview(mp4Path = playablePath)
        if (canShowPrevious) {
          Box(
            modifier = Modifier
              .align(Alignment.TopEnd)
              .padding(10.dp)
              .clip(RoundedCornerShape(50))
              .background(Color.Black.copy(alpha = 0.55f))
              .clickable { showingPrevious = !showingPrevious }
              .padding(horizontal = 10.dp, vertical = 5.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                Icons.Outlined.History,
                contentDescription = if (showingPrevious) "回到最新版" else "查看上一版",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
              )
              Spacer(Modifier.width(4.dp))
              Text(
                if (showingPrevious) "上一版" else "v${d.versionCount}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
              )
            }
          }
        }
      }
    } else {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(16.dp))
          .background(VlogPilotTokens.colors.groupedSurfaceRaised)
          .padding(VlogPilotTokens.spacing.xl),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          "这组作品还没有导出视频\n可以回到创作页重新制作",
          style = MaterialTheme.typography.bodyMedium,
          color = VlogPilotTokens.colors.secondaryLabel,
          textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
      }
    }

    var exporting by remember(d.eventId) { mutableStateOf(false) }
    // Primary actions row — share is the primary publish moment, save is
    // tinted-secondary so users notice it but the visual weight stays balanced.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(VlogPilotTokens.spacing.sm),
    ) {
      TintedActionButton(
        text = if (exporting) "保存中…" else "保存到相册",
        icon = Icons.Outlined.SaveAlt,
        onClick = {
          val path = playablePath ?: return@TintedActionButton
          if (exporting) return@TintedActionButton
          exporting = true
          val title = storyTitle(d)
          scope.launch {
            val result = withContext(Dispatchers.IO) {
              VlogExporter.saveToGallery(context, path, title)
            }
            exporting = false
            Toast.makeText(
              context,
              if (result != null) "已保存到相册：${result.displayName}" else "保存失败，请确认空间充足",
              Toast.LENGTH_SHORT,
            ).show()
          }
        },
        enabled = playablePath != null && !exporting,
        modifier = Modifier.weight(1f),
      )
      TintedActionButton(
        text = "分享",
        icon = Icons.Outlined.Share,
        onClick = {
          val path = playablePath ?: return@TintedActionButton
          val intent = VlogExporter.buildShareIntent(context, path, storyTitle(d))
          if (intent == null) {
            Toast.makeText(context, "分享失败，文件不存在", Toast.LENGTH_SHORT).show()
            return@TintedActionButton
          }
          runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show() }
        },
        enabled = playablePath != null,
        modifier = Modifier.weight(1f),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(VlogPilotTokens.spacing.sm),
    ) {
      TintedActionButton(
        text = "改一改",
        icon = Icons.Outlined.Tune,
        onClick = { onOpenIterationSheet(d.eventId, null) },
        enabled = d.mp4Path != null,
        tint = VlogPilotTokens.colors.systemPurple,
        modifier = Modifier.weight(1f),
      )
      TintedActionButton(
        text = "换故事",
        icon = Icons.Outlined.Edit,
        onClick = onChangeStory,
        tint = VlogPilotTokens.colors.systemOrange,
        modifier = Modifier.weight(1f),
      )
    }

    timeline?.let {
      ShotEditTimeline(
        eventId = d.eventId,
        timeline = it,
        assetMap = assetMap,
        canEdit = d.mp4Path != null,
        onOpenIterationSheet = onOpenIterationSheet,
      )
    }

    StageRail(d)
    VersionHistorySection(project = project, d = d, history = history)
    if (d.memory != null || d.audience != null || d.director != null || timeline != null || d.critique != null || d.perf != null) {
      SectionHeader(Icons.Outlined.Visibility, "制作过程", "Browse / Audience / Director / Editor / Critic / Render")
      ProcessOutputs(d = d, timeline = timeline, assetMap = assetMap)
    }
  }
}

@Composable
private fun ShotEditTimeline(
  eventId: String,
  timeline: Timeline,
  assetMap: Map<String, Asset>,
  canEdit: Boolean,
  onOpenIterationSheet: (String, Int?) -> Unit,
) {
  DecisionSection(
    icon = Icons.Outlined.Tune,
    title = "轻剪辑",
    subtitle = "${timeline.shots.size} 个镜头，可点单个镜头让 AI 局部修改",
  ) {
    timeline.shots.sortedBy { it.order }.forEach { shot ->
      ShotEditCard(
        eventId = eventId,
        shot = shot,
        asset = assetMap[shot.assetId],
        canEdit = canEdit,
        onOpenIterationSheet = onOpenIterationSheet,
      )
    }
  }
}

@Composable
private fun ShotEditCard(
  eventId: String,
  shot: ShotSpec,
  asset: Asset?,
  canEdit: Boolean,
  onOpenIterationSheet: (String, Int?) -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
  ) {
    Row(
      modifier = Modifier.padding(8.dp),
      horizontalArrangement = Arrangement.spacedBy(9.dp),
      verticalAlignment = Alignment.Top,
    ) {
      if (asset != null) {
        AssetThumb(
          asset = asset,
          modifier = Modifier
            .width(54.dp)
            .height(72.dp),
          showType = false,
        )
      } else {
        Box(
          modifier = Modifier
            .width(54.dp)
            .height(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
          contentAlignment = Alignment.Center,
        ) {
          Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp))
        }
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(
            "#${shot.order}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            listOf(
              shot.mediaType.name.lowercase(),
              formatSec(shot.durationSec.toDouble()),
              shot.transitionIn.name.lowercase(),
            ).joinToString(" · "),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (asset != null) {
          Text(
            asset.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (shot.caption.isNotBlank()) {
          Text(
            "字幕：${shot.caption}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        shot.videoTrim?.let { trim ->
          Text(
            "片段：${"%.1f".format(Locale.US, trim.startSec)}-${"%.1f".format(Locale.US, trim.endSec)}s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          shot.rationale.ifBlank { "AI 选入时间线" },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Surface(
        modifier = Modifier.clickable(enabled = canEdit) { onOpenIterationSheet(eventId, shot.order) },
        shape = RoundedCornerShape(999.dp),
        color = if (canEdit) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (canEdit) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
      ) {
        Text(
          "改",
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

@Composable
private fun VersionHistorySection(
  project: VlogProject?,
  d: EventDecisions,
  history: IterationHistory?,
) {
  val versions = history?.iterations.orEmpty().sortedByDescending { it.version }
  DecisionSection(icon = Icons.Outlined.History, title = "版本历史", subtitle = "当前 v${d.versionCount.coerceAtLeast(1)}") {
    KeyValue("作品", project?.title ?: storyTitle(d))
    KeyValue("模板", TemplateCatalog.byId(project?.templateId).label)
    if (versions.isEmpty()) {
      KeyValue("当前文件", d.mp4Path?.let { File(it).name } ?: "尚未导出")
      KeyValue("上一版", if (d.previousMp4Path != null) File(d.previousMp4Path).name else "无")
    } else {
      versions.forEach { summary ->
        VersionRow(
          version = summary.version,
          current = summary.version == history?.currentVersion,
          fileName = File(summary.mp4Path).name,
          createdAtMs = summary.createdAtMs,
          changeSummary = summary.changeSummary.ifBlank { if (summary.version == 1) "首版" else "AI 修改" },
        )
      }
    }
  }
}

@Composable
private fun VersionRow(
  version: Int,
  current: Boolean,
  fileName: String,
  createdAtMs: Long,
  changeSummary: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Surface(
      shape = RoundedCornerShape(7.dp),
      color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
      contentColor = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
      Text(
        "v$version",
        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
      )
    }
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        changeSummary,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        "${formatVersionTime(createdAtMs)} · $fileName",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

private fun formatVersionTime(ms: Long): String =
  SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
private fun ProjectDraftListItem(project: VlogProject) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 10.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(9.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .width(62.dp)
        .height(66.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      Icon(Icons.Outlined.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
          project.title,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        ProjectStatusPill(project.status)
      }
      Text(
        listOf(
          TemplateCatalog.byId(project.templateId).label,
          "${project.selectedAssetIds.size} 个素材",
          if (project.sourceType == ProjectSourceType.CURATED) "手动创作" else "AI 推荐",
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        "等待生成首版，完成后会在这里展开播放器和制作过程。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
  }
}

@Composable
private fun ProjectStatusPill(status: ProjectStatus) {
  val label = when (status) {
    ProjectStatus.DRAFT -> "草稿"
    ProjectStatus.MAKING -> "制作中"
    ProjectStatus.READY -> "可播放"
    ProjectStatus.OPTIMIZING -> "优化中"
    ProjectStatus.FAILED -> "失败"
  }
  Surface(
    shape = RoundedCornerShape(7.dp),
    color = if (status == ProjectStatus.FAILED) {
      MaterialTheme.colorScheme.errorContainer
    } else {
      MaterialTheme.colorScheme.primaryContainer
    },
    contentColor = if (status == ProjectStatus.FAILED) {
      MaterialTheme.colorScheme.onErrorContainer
    } else {
      MaterialTheme.colorScheme.onPrimaryContainer
    },
  ) {
    Text(
      label,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

private fun videoBrowseCategories(decisions: List<EventDecisions>, draftProjects: List<VlogProject>): List<VideoBrowseCategory> =
  VideoBrowseCategory.entries.filter { category ->
    category == VideoBrowseCategory.All ||
      decisions.any { matchesVideoCategory(it, category) } ||
      (category == VideoBrowseCategory.Draft && draftProjects.isNotEmpty())
  }

private fun matchesVideoCategory(d: EventDecisions, category: VideoBrowseCategory): Boolean = when (category) {
  VideoBrowseCategory.All -> true
  VideoBrowseCategory.Iterated -> d.versionCount > 1 || d.previousMp4Path != null
  VideoBrowseCategory.FirstCut -> d.mp4Path != null && d.versionCount <= 1 && d.previousMp4Path == null
  VideoBrowseCategory.Short -> d.videoDurationSec() in 0.1..15.0
  VideoBrowseCategory.Rich -> d.inputAssets.size >= 8 || (d.videoTimeline()?.shots?.size ?: 0) >= 6
  VideoBrowseCategory.Draft -> d.mp4Path == null
}

private fun videoSortComparator(mode: VideoSortMode): Comparator<EventDecisions> = when (mode) {
  VideoSortMode.Recent -> compareByDescending<EventDecisions> { it.videoModifiedMs() }
    .thenByDescending { it.event?.endEpochMs ?: 0L }
  VideoSortMode.Version -> compareByDescending<EventDecisions> { it.versionCount }
    .thenByDescending { it.videoModifiedMs() }
  VideoSortMode.Duration -> compareByDescending<EventDecisions> { it.videoDurationSec() }
    .thenByDescending { it.videoModifiedMs() }
}

private fun EventDecisions.videoTimeline(): Timeline? = timelineFinal ?: timelineV1

private fun EventDecisions.videoDurationSec(): Double =
  videoTimeline()?.shots?.sumOf { it.durationSec.toDouble() } ?: 0.0

private fun EventDecisions.videoCoverAsset(): Asset? {
  val firstShotAssetId = videoTimeline()?.shots?.minByOrNull { it.order }?.assetId
  return inputAssets.firstOrNull { it.id == firstShotAssetId } ?: inputAssets.firstOrNull()
}

private fun EventDecisions.videoModifiedMs(): Long {
  val path = mp4Path ?: previousMp4Path
  val fileTime = path?.let(::File)?.takeIf { it.isFile }?.lastModified() ?: 0L
  return maxOf(fileTime, event?.endEpochMs ?: 0L)
}

private fun videoCompactMeta(d: EventDecisions, project: VlogProject? = null): String {
  val range = d.event?.let { formatEventRange(it.startEpochMs, it.endEpochMs) }
  return listOfNotNull(
    range,
    project?.templateId?.let { TemplateCatalog.byId(it).label },
    if (d.mp4Path != null) "已出片" else "未导出",
    d.director?.tone?.takeIf { it.isNotBlank() },
  ).joinToString(" · ")
}

private fun projectForDecision(projects: List<VlogProject>, decision: EventDecisions): VlogProject? =
  projects.firstOrNull { it.sourceEventId == decision.eventId || it.projectId == decision.eventId }

