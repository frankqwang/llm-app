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
fun VlogPilotScreen(
  bottomPadding: Dp,
  modelManagerViewModel: ModelManagerViewModel,
  modifier: Modifier = Modifier,
  viewModel: VlogPilotViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsState()
  val decisions by viewModel.decisions.collectAsState()
  val progress by viewModel.progress.collectAsState()
  val runConfig by viewModel.runConfig.collectAsState()
  val eventSelection by viewModel.eventSelection.collectAsState()
  val context = LocalContext.current
  var selectedTab by remember { mutableStateOf(VlogPilotTab.Stories) }
  var selectedAdvancedTab by remember { mutableStateOf(VlogPilotAdvancedTab.Process) }
  var selectedStoryCategory by remember { mutableStateOf(StoryBrowseCategory.Recommended) }
  var selectedStorySort by remember { mutableStateOf(StorySortMode.Recommended) }
  var selectedStoryId by remember { mutableStateOf<String?>(null) }
  var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }

  val perms = remember {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
      arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
  }

  fun selectedModelOrReport() =
    modelManagerViewModel.getAllDownloadedModels().let { downloaded ->
      downloaded.firstOrNull { it.name.contains("gemma-4", ignoreCase = true) }
        ?: downloaded.firstOrNull()
    }.also { model ->
      if (model == null) {
        viewModel.reportError("没有找到已导入的 LLM。请先在 Models 中导入本地模型文件。")
      }
    }

  fun launchPipeline() {
    val model = selectedModelOrReport() ?: return
    viewModel.runFullPipeline(model)
  }

  val permLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
      if (grants.values.all { it }) {
        val action = pendingPermissionAction
        pendingPermissionAction = null
        action?.invoke() ?: launchPipeline()
      }
      else viewModel.reportError("没有相册权限，无法扫描素材。")
    }

  fun requireAlbumPermission(action: () -> Unit) {
    val ungranted = perms.filter {
      ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    if (ungranted.isEmpty()) {
      action()
    } else {
      pendingPermissionAction = action
      permLauncher.launch(ungranted.toTypedArray())
    }
  }

  fun makeStory(eventId: String) {
    requireAlbumPermission {
      selectedModelOrReport()?.let { model ->
        viewModel.runOnlyEvent(eventId, model)
      }
    }
  }

  val running = state is PipelineState.Running || state is PipelineState.Scanning
  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp)
      .padding(bottom = bottomPadding),
    contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      WorkspaceTabs(selected = selectedTab, onSelect = { selectedTab = it })
    }

    val ready = state as? PipelineState.Ready
    when (selectedTab) {
      VlogPilotTab.Stories -> {
        val manifest = eventSelection
        when {
          running -> item {
            StoryProgressCard(
              state = state,
              progress = progress,
              onCancel = viewModel::cancelPipeline,
            )
          }

          manifest == null -> item {
            StoryHeroCard(
              state = state,
              recentDecisions = decisions.take(2),
              onStartClick = {
                requireAlbumPermission {
                  selectedModelOrReport()?.let { viewModel.refreshCandidates(it) }
                }
              },
              onVideosClick = { selectedTab = VlogPilotTab.Videos },
            )
          }

          else -> {
            item {
              StoryShelf(
                manifest = manifest,
                runConfig = runConfig,
                selectedCategory = selectedStoryCategory,
                selectedSort = selectedStorySort,
                onCategorySelect = { selectedStoryCategory = it },
                onSortSelect = { selectedStorySort = it },
                onOpenStory = { selectedStoryId = it },
                onStartClick = {
                  requireAlbumPermission {
                    selectedModelOrReport()?.let { viewModel.refreshCandidates(it) }
                  }
                },
                onClearOnly = viewModel::clearOnlySelected,
                onSelectStory = viewModel::onlyGenerateEvent,
                onMakeStory = ::makeStory,
              )
            }
          }
        }
      }

      VlogPilotTab.Videos -> {
        if (decisions.isEmpty()) {
          item {
            EmptyActionCard(
              state = state,
              title = "还没有视频",
              message = "先去挑一组故事，我会帮你剪成一条回忆视频。",
              actionLabel = "去挑故事",
              onAction = { selectedTab = VlogPilotTab.Stories },
            )
          }
        } else {
          items(decisions, key = { "video-${it.eventId}" }) { decision ->
            ResultEventCard(
              d = decision,
              onRegenerate = viewModel::forceRegenerateEvent,
              onChangeStory = { selectedTab = VlogPilotTab.Stories },
            )
          }
        }
      }

      VlogPilotTab.Settings -> {
        item {
          SettingsCard(
            runConfig = runConfig,
            running = running,
            onIntentSelect = viewModel::setIntent,
            onPowerSelect = viewModel::setPowerProfile,
          )
        }
      }

      VlogPilotTab.Advanced -> {
        item {
          AdvancedTabPicker(selected = selectedAdvancedTab, onSelect = { selectedAdvancedTab = it })
        }
        when (selectedAdvancedTab) {
          VlogPilotAdvancedTab.Process -> {
            if (decisions.isEmpty()) {
              item { EmptyProcessCard(state, "还没有技术过程", "开始生成后，这里会显示每一步 Agent 输出和渲染状态。") }
            } else {
              items(decisions, key = { "process-${it.eventId}" }) { decision ->
                EventDecisionCard(decision)
              }
            }
          }
          VlogPilotAdvancedTab.Assets -> {
            if (ready != null) {
              item { AlbumPreviewCard(assets = ready.assets, events = ready.events) }
            }
            if (decisions.isEmpty()) {
              item { EmptyProcessCard(state, "还没有素材详情", "故事开始处理后，这里会显示每张素材的感知分数和 VLM 标签。") }
            } else {
              items(decisions, key = { "assets-${it.eventId}" }) { decision ->
                AssetManagementCard(decision)
              }
            }
          }
          VlogPilotAdvancedTab.Prompts -> {
            item { PromptCatalog() }
          }
        }
      }
    }
  }

  eventSelection?.candidates
    ?.firstOrNull { it.eventId == selectedStoryId }
    ?.let { candidate ->
      StoryDetailDialog(
        candidate = candidate,
        runConfig = runConfig,
        onDismiss = { selectedStoryId = null },
        onDislike = viewModel::excludeEvent,
        onUndoDislike = viewModel::clearExcluded,
        onMake = {
          makeStory(it)
          selectedStoryId = null
        },
        onPin = viewModel::pinEvent,
        onRegenerate = viewModel::forceRegenerateEvent,
      )
    }
}

private enum class VlogPilotTab(val label: String, val icon: ImageVector) {
  Stories("故事", Icons.Outlined.Search),
  Videos("视频", Icons.Outlined.Movie),
  Settings("设置", Icons.Outlined.Edit),
  Advanced("高级", Icons.Outlined.Visibility),
}

private enum class VlogPilotAdvancedTab(val label: String) {
  Process("技术过程"),
  Assets("素材详情"),
  Prompts("Prompt"),
}

private enum class StoryBrowseCategory(val label: String) {
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

private enum class StorySortMode(val label: String) {
  Recommended("推荐"),
  Newest("最新"),
  Oldest("最早"),
}

@Composable
private fun WorkspaceTabs(selected: VlogPilotTab, onSelect: (VlogPilotTab) -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
  ) {
    LazyRow(
      modifier = Modifier.padding(5.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      items(VlogPilotTab.entries) { tab ->
        val active = tab == selected
        Surface(
          modifier = Modifier.clickable { onSelect(tab) },
          shape = RoundedCornerShape(14.dp),
          color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
          contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
          tonalElevation = if (active) 2.dp else 0.dp,
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(tab.icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(tab.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }
  }
}

@Composable
private fun <T> ControlSegment(
  label: String,
  options: List<T>,
  selected: T,
  optionLabel: (T) -> String,
  onSelect: (T) -> Unit,
  enabled: Boolean,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    if (!enabled) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            optionLabel(selected),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text("运行中锁定", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
      }
    } else {
      LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options) { option ->
          val active = option == selected
          Surface(
            modifier = Modifier.clickable { onSelect(option) },
            shape = RoundedCornerShape(999.dp),
            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
          ) {
            Text(
              optionLabel(option),
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun StoryHeroCard(
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
private fun StoryProgressCard(
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
private fun StoryShelf(
  manifest: EventSelectionManifest,
  runConfig: VlogPilotRunConfig,
  selectedCategory: StoryBrowseCategory,
  selectedSort: StorySortMode,
  onCategorySelect: (StoryBrowseCategory) -> Unit,
  onSortSelect: (StorySortMode) -> Unit,
  onOpenStory: (String) -> Unit,
  onStartClick: () -> Unit,
  onClearOnly: () -> Unit,
  onSelectStory: (String) -> Unit,
  onMakeStory: (String) -> Unit,
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
        selectedStory = manifest.candidates.firstOrNull { it.eventId in runConfig.onlySelectedEventIds },
        shownCount = filtered.size,
        selectedSort = selectedSort,
        onSortSelect = onSortSelect,
        onStartClick = onStartClick,
        onClearOnly = onClearOnly,
        onMakeStory = onMakeStory,
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
                  onOpen = { onOpenStory(candidate.eventId) },
                  onSelect = { onSelectStory(candidate.eventId) },
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
private fun StoryShelfHeader(
  manifest: EventSelectionManifest,
  runConfig: VlogPilotRunConfig,
  selectedStory: EventCandidateSnapshot?,
  shownCount: Int,
  selectedSort: StorySortMode,
  onSortSelect: (StorySortMode) -> Unit,
  onStartClick: () -> Unit,
  onClearOnly: () -> Unit,
  onMakeStory: (String) -> Unit,
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
      FilledTonalButton(onClick = onStartClick) { Text("重扫") }
    }

    if (selectedStory != null) {
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
            Text("已选中", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
            Text(
              storyTitle(selectedStory),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { onMakeStory(selectedStory.eventId) }) {
              Text("开始制作")
            }
            Text(
              "取消选择",
              modifier = Modifier.clickable { onClearOnly() },
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
              fontWeight = FontWeight.Medium,
            )
          }
        }
      }
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
private fun StoryCategoryRail(
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
private fun StoryListItem(
  candidate: EventCandidateSnapshot,
  selected: Boolean,
  onOpen: () -> Unit,
  onSelect: () -> Unit,
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
    Button(onClick = onSelect) {
      Text(if (selected) "已选" else "选")
    }
  }
}

@Composable
private fun StoryTinyStatus(status: EventSelectionStatus) {
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
private fun StoryDetailDialog(
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

@Composable
private fun SettingsCard(
  runConfig: VlogPilotRunConfig,
  running: Boolean,
  onIntentSelect: (GenerationIntent) -> Unit,
  onPowerSelect: (PowerProfile) -> Unit,
) {
  PanelCard {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      SectionHeader(
        icon = Icons.Outlined.Edit,
        title = "视频设置",
        subtitle = if (running) "正在制作时会先锁定设置。" else "告诉我你想要什么样的视频。",
      )
      ControlSegment(
        label = "想做什么样的视频？",
        options = GenerationIntent.entries.toList(),
        selected = runConfig.intent,
        optionLabel = ::friendlyIntentLabel,
        onSelect = onIntentSelect,
        enabled = !running,
      )
      ControlSegment(
        label = "生成偏好",
        options = PowerProfile.entries.toList(),
        selected = runConfig.powerProfile,
        optionLabel = ::friendlyPowerLabel,
        onSelect = onPowerSelect,
        enabled = !running,
      )
    }
  }
}

@Composable
private fun AdvancedTabPicker(
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
private fun AlbumPreviewCard(assets: List<Asset>, events: List<Event>) {
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
private fun StoryStatusBadge(status: EventSelectionStatus) {
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
private fun CandidateDetails(candidate: EventCandidateSnapshot) {
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

@Composable
private fun EmptyProcessCard(
  state: PipelineState,
  title: String = "还没有过程数据",
  message: String = "开始生成后，这里会逐步出现输入素材、Agent 输出和渲染结果。",
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = if (state is PipelineState.Error) Icons.Outlined.ErrorOutline else Icons.Outlined.PhotoLibrary,
        contentDescription = null,
        tint = if (state is PipelineState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Column {
        Text(
          if (state is PipelineState.Error) "任务中断" else title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          if (state is PipelineState.Error) state.message else message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun EmptyActionCard(
  state: PipelineState,
  title: String,
  message: String,
  actionLabel: String,
  onAction: () -> Unit,
) {
  PanelCard {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      SectionHeader(
        icon = if (state is PipelineState.Error) Icons.Outlined.ErrorOutline else Icons.Outlined.Movie,
        title = if (state is PipelineState.Error) "任务中断" else title,
        subtitle = if (state is PipelineState.Error) state.message else message,
      )
      Button(modifier = Modifier.fillMaxWidth(), onClick = onAction) {
        Text(actionLabel)
      }
    }
  }
}

@Composable
private fun ResultEventCard(
  d: EventDecisions,
  onRegenerate: (String) -> Unit,
  onChangeStory: () -> Unit,
) {
  var showTimeline by remember { mutableStateOf(false) }
  val timeline = d.timelineFinal ?: d.timelineV1
  val title = storyTitle(d)
  val durationSec = timeline?.shots?.sumOf { it.durationSec.toDouble() } ?: 0.0
  val assetMap = remember(d.inputAssets) { d.inputAssets.associateBy { it.id } }

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
      }

      MetricGrid(
        items = listOf(
          MetricDatum("镜头", (timeline?.shots?.size ?: 0).toString()),
          MetricDatum("时长", formatSec(durationSec)),
          MetricDatum("素材", d.inputAssets.size.toString()),
          MetricDatum("渲染", if (d.mp4Path != null) "完成" else "等待", d.mp4Path != null),
        ),
        columns = 2,
      )

      if (d.mp4Path != null) {
        VideoPreview(mp4Path = d.mp4Path)
      } else {
        Text(
          "这组故事还没有导出视频。可以重新剪一次，或回到故事页换一组。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
          modifier = Modifier.weight(1f),
          onClick = { onRegenerate(d.eventId) },
        ) {
          Text("重新剪一次")
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

@Composable
private fun AssetManagementCard(d: EventDecisions) {
  val timeline = d.timelineFinal ?: d.timelineV1
  val usedOrders = remember(timeline) {
    timeline?.shots.orEmpty().groupBy { it.assetId }.mapValues { entry -> entry.value.map { it.order } }
  }
  val tagged = d.inputPerceptions.values.count { it.vlmTags.scene.isNotBlank() }

  PanelCard {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      SectionHeader(
        icon = Icons.Outlined.PhotoLibrary,
        title = "事件 ${shortId(d.eventId)} 素材",
        subtitle = "${d.inputAssets.size} 个输入 / $tagged 个有 VLM 标签",
      )
      MetricGrid(
        items = listOf(
          MetricDatum("输入", d.inputAssets.size.toString()),
          MetricDatum("已标注", tagged.toString(), tagged > 0),
          MetricDatum("入片", usedOrders.size.toString(), usedOrders.isNotEmpty()),
        ),
        columns = 3,
      )
      if (d.inputAssets.isNotEmpty()) {
        AssetStrip(assets = d.inputAssets.take(24), totalCount = d.inputAssets.size)
      }
      d.inputAssets.forEach { asset ->
        AssetAnnotationRow(
          asset = asset,
          perception = d.inputPerceptions[asset.id],
          usedOrders = usedOrders[asset.id].orEmpty(),
        )
      }
    }
  }
}

@Composable
private fun AssetAnnotationRow(asset: Asset, perception: Perception?, usedOrders: List<Int>) {
  var expanded by remember { mutableStateOf(false) }
  val tags = perception?.vlmTags
  val videoInsight = perception?.videoInsight
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable { expanded = !expanded },
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
  ) {
    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
      ) {
        AssetThumb(
          asset = asset,
          modifier = Modifier.width(56.dp).height(72.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(
            asset.displayName.ifBlank { shortId(asset.id) },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            assetMeta(asset),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            annotationSummary(tags, videoInsight?.summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) 4 else 2,
            overflow = TextOverflow.Ellipsis,
          )
          LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { SignalTag(if (tags?.scene?.isNotBlank() == true) "已标注" else "未标注", Icons.Outlined.Search, tags?.scene?.isNotBlank() == true) }
            if (usedOrders.isNotEmpty()) item { SignalTag("入片 #${usedOrders.joinToString(",")}", Icons.Outlined.CheckCircle, true) }
            if ((videoInsight?.bestMomentSec ?: 0f) > 0f) item { SignalTag("best ${"%.1fs".format(Locale.US, videoInsight!!.bestMomentSec)}", Icons.Outlined.PlayArrow, true) }
            item { SignalTag("face ${perception?.faces?.size ?: 0}", Icons.Outlined.Person) }
          }
        }
        Icon(
          imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
          contentDescription = if (expanded) "收起" else "展开",
          modifier = Modifier.size(22.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (expanded) {
        HorizontalDivider()
        KeyValue("assetId", asset.id)
        perception?.let { p ->
          KeyValue("质量", "sharp=${"%.2f".format(Locale.US, p.sharpness)} / bright=${"%.2f".format(Locale.US, p.brightness)} / nsfw=${"%.2f".format(Locale.US, p.nsfwScore)}")
          KeyValue("过滤", if (p.isJunk) "junk: ${p.junkReason}" else "可用")
          if (p.sceneCuts.isNotEmpty()) KeyValue("切点", p.sceneCuts.take(8).joinToString(", ") { "%.1fs".format(Locale.US, it) })
          AnnotationKeyValues(p.vlmTags)
          VideoInsightKeyValues(p)
        } ?: Text(
          "还没有 perception_cache 记录。通常是该素材还没进入感知/标注阶段，或缓存被清理。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun AnnotationKeyValues(tags: VlmTags) {
  if (tags.scene.isBlank() && tags.subjects.isEmpty() && tags.action.isBlank() && tags.mood.isBlank()) {
    Text("VLM 标签为空。Recall 会退化到质量/时序/时长信号。", style = MaterialTheme.typography.bodySmall)
    return
  }
  KeyValue("scene", tags.scene)
  if (tags.subjects.isNotEmpty()) KeyValue("subjects", tags.subjects.joinToString("、"))
  KeyValue("action", tags.action)
  KeyValue("mood", tags.mood)
  KeyValue("time", tags.timeFeel)
  KeyValue("salient", tags.salient)
  KeyValue("role", tags.narrativeRoleHint)
}

@Composable
private fun VideoInsightKeyValues(perception: Perception) {
  val insight = perception.videoInsight
  if (insight.frameTimestampsSec.isEmpty() && insight.summary.isBlank()) return
  KeyValue("video summary", insight.summary)
  KeyValue("action arc", insight.actionArc)
  if (insight.bestMomentSec > 0f) KeyValue("best moment", "%.1fs (#%d)".format(Locale.US, insight.bestMomentSec, insight.bestMomentIndex))
  if (insight.badMomentIndices.isNotEmpty()) KeyValue("avoid frames", insight.badMomentIndices.joinToString(", "))
  if (insight.frameTimestampsSec.isNotEmpty()) KeyValue("sample frames", insight.frameTimestampsSec.joinToString(", ") { "%.1fs".format(Locale.US, it) })
}

@Composable
private fun EventDecisionCard(d: EventDecisions) {
  var expanded by remember { mutableStateOf(false) }
  val timeline = d.timelineFinal ?: d.timelineV1
  val assetMap = remember(d.inputAssets) { d.inputAssets.associateBy { it.id } }
  val title = d.director?.title?.takeIf { it.isNotBlank() }
    ?: d.memory?.storylineSummary?.takeIf { it.isNotBlank() }?.let { it.take(34) }
    ?: "事件 ${shortId(d.eventId)}"
  val shotCount = timeline?.shots?.size ?: 0
  val durationSec = timeline?.shots?.sumOf { it.durationSec.toDouble() } ?: 0.0

  PanelCard {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .clickable { expanded = !expanded }
          .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            eventSubtitle(d),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(onClick = { expanded = !expanded }) {
          Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "收起" else "展开",
          )
        }
      }

      MetricGrid(
        items = listOf(
          MetricDatum("素材", d.inputAssets.size.toString()),
          MetricDatum("镜头", shotCount.toString()),
          MetricDatum("时长", formatSec(durationSec)),
          MetricDatum("渲染", if (d.mp4Path != null) "完成" else "未完成", d.mp4Path != null),
        ),
        columns = 2,
      )

      StageRail(d)

      if (d.inputAssets.isNotEmpty()) {
        SectionHeader(
          icon = Icons.Outlined.Search,
          title = "本事件扫描素材",
          subtitle = "${d.inputAssets.size} 个输入，按时间线参与 browse / recall",
        )
        AssetStrip(assets = d.inputAssets.take(24), totalCount = d.inputAssets.size)
      }

      if (expanded) {
        HorizontalDivider()
        d.mp4Path?.let { path ->
          DecisionSection(icon = Icons.Outlined.PlayArrow, title = "Render", subtitle = "成片预览") {
            VideoPreview(mp4Path = path)
            Text(
              File(path).name,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        ProcessOutputs(d = d, timeline = timeline, assetMap = assetMap)
      } else {
        CollapsedSummary(d = d, timeline = timeline)
      }
    }
  }
}

@Composable
private fun StageRail(d: EventDecisions) {
  val timeline = d.timelineFinal ?: d.timelineV1
  val stages = listOf(
    StageUi("扫描", d.inputAssets.isNotEmpty()),
    StageUi("浏览", d.memory != null),
    StageUi("观众", d.audience != null),
    StageUi("导演", d.director != null),
    StageUi("剪辑", timeline != null),
    StageUi("审片", d.critique != null || timeline?.critiqueHistory?.isNotEmpty() == true),
    StageUi("渲染", d.mp4Path != null),
  )
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f),
  ) {
    LazyRow(
      modifier = Modifier.padding(8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(stages) { stage ->
        StageStep(stage)
      }
    }
  }
}

@Composable
private fun StageStep(stage: StageUi) {
  val container = if (stage.done) {
    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
  } else {
    Color.Transparent
  }
  val content = if (stage.done) {
    MaterialTheme.colorScheme.primary
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }
  Surface(shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = if (stage.done) Icons.Outlined.CheckCircle else Icons.Outlined.Timer,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
      )
      Text(stage.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
  }
}

@Composable
private fun CollapsedSummary(d: EventDecisions, timeline: Timeline?) {
  val text = when {
    d.memory?.storylineSummary?.isNotBlank() == true -> d.memory.storylineSummary
    d.director?.narrativeArc?.isNotEmpty() == true -> d.director.narrativeArc.joinToString(" / ")
    timeline != null -> "已生成 ${timeline.shots.size} 个镜头，展开查看选片和理由。"
    d.inputAssets.isNotEmpty() -> "已记录输入素材，等待 Agent 输出。"
    else -> "等待事件输入。"
  }
  Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun ProcessOutputs(
  d: EventDecisions,
  timeline: Timeline?,
  assetMap: Map<String, Asset>,
) {
  d.memory?.let { memory ->
    DecisionSection(icon = Icons.Outlined.Visibility, title = "Browse", subtitle = "事件记忆") {
      Text(memory.storylineSummary, style = MaterialTheme.typography.bodySmall)
      if (memory.emotionalArc.isNotBlank()) KeyValue("情绪弧线", memory.emotionalArc)
      if (memory.charactersObserved.isNotEmpty()) KeyValue("人物", memory.charactersObserved.joinToString(", "))
      if (memory.visualStyleSignals.isNotBlank()) KeyValue("视觉信号", memory.visualStyleSignals)
      memory.keyMoments.take(4).forEach { moment ->
        Text(
          "#${moment.imageIndex} ${shortId(moment.assetId)} · ${moment.why}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }

  d.audience?.let { audience ->
    DecisionSection(icon = Icons.Outlined.Person, title = "Audience", subtitle = "观看诉求") {
      if (audience.emotionalPayoff.isNotBlank()) KeyValue("情绪回报", audience.emotionalPayoff)
      if (audience.hookStrategy.isNotBlank()) KeyValue("开头钩子", audience.hookStrategy)
      if (audience.povVoice.isNotBlank()) KeyValue("视角", audience.povVoice)
      if (audience.pacingGuidance.isNotBlank()) KeyValue("节奏", audience.pacingGuidance)
      if (audience.avoidList.isNotEmpty()) KeyValue("避免", audience.avoidList.joinToString(", "))
    }
  }

  d.director?.let { director ->
    DecisionSection(icon = Icons.Outlined.Movie, title = "Director", subtitle = "叙事脚本") {
      if (director.title.isNotBlank()) {
        Text(director.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
      }
      KeyValue("基调", director.tone)
      KeyValue("目标时长", formatSec(director.targetDurationSec.toDouble()))
      if (director.narrativeArc.isNotEmpty()) KeyValue("叙事弧线", director.narrativeArc.joinToString(" -> "))
      director.shotBlueprint.take(8).forEach { req ->
        Text(
          "${req.position}. ${req.role.name.lowercase()} · ${"%.1f".format(Locale.US, req.durationSec)}s · ${req.visualRequirements}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }

  timeline?.let { tl ->
    DecisionSection(icon = Icons.Outlined.Edit, title = "Editor", subtitle = "最终时间线 ${tl.shots.size} shots") {
      tl.shots.forEach { shot ->
        ShotRow(shot = shot, asset = assetMap[shot.assetId])
      }
    }
  }

  val critiqueHistory = timeline?.critiqueHistory.orEmpty()
  if (d.critique != null || critiqueHistory.isNotEmpty()) {
    DecisionSection(icon = Icons.Outlined.Search, title = "Critic", subtitle = "审片反馈") {
      val critique = d.critique ?: critiqueHistory.lastOrNull()
      if (critique == null || (critique.issues.isEmpty() && critique.revisedRequests.isEmpty())) {
        Text("没有发现需要修改的问题。", style = MaterialTheme.typography.bodySmall)
      } else {
        critique.issues.forEach { issue ->
          Text(
            "• $issue",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (critique.revisedRequests.isNotEmpty()) {
          KeyValue("重选镜头", "${critique.revisedRequests.size} 个")
        }
      }
      if (critiqueHistory.size > 1) {
        KeyValue("迭代次数", critiqueHistory.size.toString())
      }
    }
  }

  d.perf?.let { perf ->
    DecisionSection(icon = Icons.Outlined.Timer, title = "Timing", subtitle = "阶段耗时") {
      PerfGrid(perf)
    }
  }
}

@Composable
private fun ShotRow(shot: ShotSpec, asset: Asset?) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.Top,
  ) {
    if (asset != null) {
      AssetThumb(
        asset = asset,
        modifier = Modifier
          .width(48.dp)
          .height(64.dp),
        showType = false,
      )
    } else {
      Box(
        modifier = Modifier
          .width(48.dp)
          .height(64.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp))
      }
    }
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        "#${shot.order} · ${shot.mediaType.name.lowercase()} · ${"%.1f".format(Locale.US, shot.durationSec)}s · ${shot.transitionIn.name.lowercase()}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (shot.caption.isNotBlank()) {
        Text(shot.caption, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
      }
      Text(
        "${shortId(shot.assetId)} · ${shot.rationale.ifBlank { "已选入时间线" }}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun AssetStrip(assets: List<Asset>, totalCount: Int) {
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
private fun AssetThumb(
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
private fun VideoPreview(mp4Path: String) {
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
private fun DecisionSection(
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
private fun SectionHeader(icon: ImageVector, title: String, subtitle: String? = null) {
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
private fun KeyValue(label: String, value: String) {
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
private fun PerfGrid(perf: StagePerf) {
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

private data class MetricDatum(
  val label: String,
  val value: String,
  val accent: Boolean = false,
)

@Composable
private fun PanelCard(
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
private fun MetricGrid(
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
private fun MetricBadge(
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
private fun StatusBadge(text: String, status: EventSelectionStatus) {
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
private fun SignalTag(text: String, icon: ImageVector, accent: Boolean = false) {
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
private fun ScoreMeter(label: String, score: Float, accent: Boolean = false) {
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

@Composable
private fun PromptCatalog() {
  PanelCard {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      SectionHeader(
        icon = Icons.Outlined.Edit,
        title = "当前 Prompt",
        subtitle = "系统 prompt + 实际 user prompt 模板",
      )
      promptSpecs().forEach { spec ->
        PromptCard(spec)
      }
    }
  }
}

@Composable
private fun PromptCard(spec: PromptSpec) {
  var expanded by remember { mutableStateOf(false) }
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable { expanded = !expanded },
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(spec.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(spec.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
          imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
          contentDescription = null,
        )
      }
      PromptTextBlock("System", spec.systemPrompt, expanded)
      spec.userTemplate?.let { PromptTextBlock("User 模板", it, expanded) }
    }
  }
}

@Composable
private fun PromptTextBlock(label: String, text: String, expanded: Boolean) {
  val shown = if (expanded || text.length <= 520) text else text.take(520) + "\n..."
  Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
  ) {
    Text(
      shown,
      modifier = Modifier.padding(10.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private data class PromptSpec(
  val title: String,
  val subtitle: String,
  val systemPrompt: String,
  val userTemplate: String? = null,
)

private fun promptSpecs(): List<PromptSpec> = listOf(
  PromptSpec(
    title = "VLM 单素材标注",
    subtitle = "图片缩略图 -> VlmTags",
    systemPrompt = PromptStrings.VLM_IMAGE_SYSTEM,
    userTemplate = "媒体类型: <image/video/live_photo>。请输出 VlmTags JSON。",
  ),
  PromptSpec(
    title = "VLM 视频多帧标注",
    subtitle = "自适应多帧视频网格 -> VlmTags + VideoInsight",
    systemPrompt = PromptStrings.VLM_VIDEO_SYSTEM,
    userTemplate = "媒体类型: <video/live_photo>。帧编号和时间戳: 1=0.4s, 2=1.2s, ...。请输出 Video VlmTags JSON。",
  ),
  PromptSpec(
    title = "Event Scout",
    subtitle = "3x3 候选事件 contact sheet -> EventScout",
    systemPrompt = EventScoutAgent.SCOUT_SYSTEM_PROMPT,
    userTemplate = """
Event id: <eventId>
Page: <page>/<pages>
Cells are numbered 1..9. Each cell is one image or one sampled video frame.
Return EventPageScout JSON only.
""".trimIndent(),
  ),
  PromptSpec(
    title = "Browser / Contact Sheet",
    subtitle = "事件缩略图网格 -> EventMemory",
    systemPrompt = PromptStrings.MONTAGE_SYSTEM,
    userTemplate = "事件 <eventId> 第 <page>/<pages> 页，本页 <N> 张。image_index 为本页内 1..<N> 编号。请输出 EventMemory JSON。",
  ),
  PromptSpec(
    title = "Audience",
    subtitle = "事件记忆 -> 观众情绪目标",
    systemPrompt = PromptStrings.AUDIENCE_SYSTEM,
    userTemplate = """
事件 <eventId> 的 EventMemory:
- storyline_summary: <storyline_summary>
- emotional_arc: <emotional_arc>
- characters: <characters>
- visual_style: <visual_style_signals>

请输出 AudienceBrief JSON。
""".trimIndent(),
  ),
  PromptSpec(
    title = "Director",
    subtitle = "EventMemory + AudienceBrief -> 分镜剧本",
    systemPrompt = PromptStrings.DIRECTOR_SYSTEM,
    userTemplate = """
EventMemory:
<storyline_summary>
情绪曲线: <emotional_arc>
人物: <characters>

AudienceBrief:
- 情绪点: <emotional_payoff>
- hook: <hook_strategy>
- pov: <pov_voice>
- 节奏: <pacing_guidance>
- 避免: <avoid_list>

总素材: <assetCount> 张/段；目标 vlog 时长 18-22 秒。
请输出 DirectorBrief JSON。
""".trimIndent(),
  ),
  PromptSpec(
    title = "Editor",
    subtitle = "候选缩略图 + 标签 -> 选一个镜头",
    systemPrompt = PromptStrings.EDITOR_SYSTEM,
    userTemplate = """
当前 slot：role=<role>; mood=<mood_target>; visual_req=<visual_requirements>
previous_shot_summary: <previous_shot_summary>

候选标签（VLM 已经看过每张图，结构化摘要）：
  1. scene=<scene> / action=<action> / mood=<mood> / salient=<salient> / subjects=<subjects>
  ...

请综合标签 + 缩略图视觉，从 <N> 张候选中选 1 张（编号 1..<N>）。
""".trimIndent(),
  ),
  PromptSpec(
    title = "Critic",
    subtitle = "粗剪 timeline + storyboard -> 视觉审片与修订请求",
    systemPrompt = PromptStrings.CRITIC_SYSTEM,
    userTemplate = """
DirectorBrief.title=<title>; tone=<tone>; target_duration=<target_duration>
narrative_arc: <arc>
cheap_checks: <pass/needs_attention>

EventMemory.storyline: <storyline>

Timeline v<iteration> shots:
  #1 [image/video] dur=<sec>s trim=<start-end>s caption="<caption>" - <rationale>
  ...

附一张 storyboard：每格对应一个 shot，左上角编号和 #order 一致。
请同时检查文字时间线和 storyboard，输出 Critique JSON。
""".trimIndent(),
  ),
)

private data class StageUi(val label: String, val done: Boolean)

private data class FriendlyProgress(
  val title: String,
  val detail: String,
  val current: Int = 0,
  val total: Int = 0,
)

private fun eventSubtitle(d: EventDecisions): String {
  val range = d.event?.let { formatEventRange(it.startEpochMs, it.endEpochMs) }
  val place = d.event?.placeHint?.takeIf { it.isNotBlank() }
  return listOfNotNull(range, place, d.eventId).joinToString(" · ")
}

private fun videoSubtitle(d: EventDecisions): String {
  val range = d.event?.let { formatEventRange(it.startEpochMs, it.endEpochMs) }
  val timeline = d.timelineFinal ?: d.timelineV1
  val duration = timeline?.shots?.sumOf { it.durationSec.toDouble() }?.let { formatSec(it) }
  return listOfNotNull(range, "${d.inputAssets.size} 个素材", duration).joinToString(" · ")
}

private fun storyTitle(candidate: EventCandidateSnapshot): String =
  storyTitle(candidate.event, candidate.scoutSummary, candidate.scoutEventType)

private fun storyTitle(d: EventDecisions): String {
  d.director?.title?.takeIf { it.isNotBlank() }?.let { return it }
  return d.event?.let { event ->
    storyTitle(event, d.memory?.storylineSummary.orEmpty(), "")
  } ?: "一段回忆视频"
}

private fun storyTitle(event: Event, summary: String, eventType: String): String {
  val day = storyDay(event.startEpochMs)
  val theme = storyTheme(eventType, summary)
  return "${day}的$theme"
}

private fun storyMeta(candidate: EventCandidateSnapshot): String =
  listOfNotNull(
    formatEventRange(candidate.event.startEpochMs, candidate.event.endEpochMs).takeIf { it.isNotBlank() },
    "${candidate.event.assetIds.size} 张/段素材",
    "${candidate.realVideoCount} 段视频",
    formatSec(candidate.realVideoSeconds.toDouble()).takeIf { candidate.realVideoSeconds > 0f },
  ).joinToString(" · ")

private fun compactStoryMeta(candidate: EventCandidateSnapshot): String =
  listOfNotNull(
    storyDay(candidate.event.startEpochMs),
    "${candidate.event.assetIds.size}素材",
    if (candidate.realVideoCount > 0) "${candidate.realVideoCount}视频" else null,
    formatSec(candidate.realVideoSeconds.toDouble()).takeIf { candidate.realVideoSeconds > 0f },
  ).joinToString(" · ")

private fun storyListHint(candidate: EventCandidateSnapshot): String = when {
  candidate.status == EventSelectionStatus.COMPLETED -> "已经剪过"
  candidate.status == EventSelectionStatus.EXCLUDED -> "已放到不喜欢"
  candidate.realVideoSeconds >= 180f -> "视频素材很足"
  candidate.realVideoCount >= 8 -> "视频片段多"
  candidate.scoutRecommended -> "画面连贯"
  candidate.gpsAssetCount > 0 -> "有地点线索"
  else -> storyTheme(candidate.scoutEventType, candidate.scoutSummary)
}

private fun storyReason(candidate: EventCandidateSnapshot): String {
  val theme = storyTheme(candidate.scoutEventType, candidate.scoutSummary)
  val videoHint = when {
    candidate.realVideoSeconds >= 60f -> "视频素材比较充足"
    candidate.realVideoCount > 0 -> "有可用的视频片段"
    else -> "照片内容比较集中"
  }
  val scoutHint = when {
    candidate.scoutRecommended -> "画面和内容比较适合剪成 vlog"
    candidate.scoutSummary.isNotBlank() -> "我已经看过这组缩略图，内容有一定连贯性"
    else -> "这组素材时间上比较接近，可以先作为一个故事看看"
  }
  return "这像是一组$theme，$videoHint，$scoutHint。"
}

private fun storyTheme(eventType: String, summary: String): String {
  val text = "$eventType $summary".lowercase(Locale.ROOT)
  return when {
    listOf("food", "meal", "pizza", "restaurant", "dinner", "lunch", "breakfast", "餐", "美食").any { it in text } -> "美食时光"
    listOf("travel", "trip", "street", "hotel", "beach", "outdoor", "旅行", "出游").any { it in text } -> "出游回忆"
    listOf("zoo", "animal", "pet", "dog", "cat", "动物").any { it in text } -> "动物故事"
    listOf("child", "kid", "people", "family", "friend", "person", "人物", "孩子", "家人").any { it in text } -> "家庭时光"
    else -> "生活片段"
  }
}

private fun storyDay(epochMs: Long): String {
  if (epochMs <= 0L) return "今天"
  val fmt = SimpleDateFormat("M月d日", Locale.getDefault())
  return fmt.format(Date(epochMs))
}

private fun storyBrowseCategories(candidates: List<EventCandidateSnapshot>): List<StoryBrowseCategory> =
  StoryBrowseCategory.entries.filter { category ->
    category == StoryBrowseCategory.Recommended ||
      category == StoryBrowseCategory.All ||
      candidates.any { matchesStoryCategory(it, category) }
  }

private fun matchesStoryCategory(candidate: EventCandidateSnapshot, category: StoryBrowseCategory): Boolean = when (category) {
  StoryBrowseCategory.Recommended -> candidate.status == EventSelectionStatus.SELECTED || candidate.scoutRecommended
  StoryBrowseCategory.All -> true
  StoryBrowseCategory.Travel -> storyTheme(candidate.scoutEventType, candidate.scoutSummary) == "出游回忆"
  StoryBrowseCategory.Family -> storyTheme(candidate.scoutEventType, candidate.scoutSummary) == "家庭时光"
  StoryBrowseCategory.Food -> storyTheme(candidate.scoutEventType, candidate.scoutSummary) == "美食时光"
  StoryBrowseCategory.Animal -> storyTheme(candidate.scoutEventType, candidate.scoutSummary) == "动物故事"
  StoryBrowseCategory.Video -> candidate.realVideoCount >= 3 || candidate.realVideoSeconds >= 45f
  StoryBrowseCategory.Done -> candidate.status == EventSelectionStatus.COMPLETED
  StoryBrowseCategory.Hidden -> candidate.status == EventSelectionStatus.EXCLUDED
}

private fun storySortComparator(mode: StorySortMode): Comparator<EventCandidateSnapshot> = when (mode) {
  StorySortMode.Recommended -> compareByDescending<EventCandidateSnapshot> { if (it.status == EventSelectionStatus.SELECTED) 1 else 0 }
    .thenByDescending { it.valueScore }
    .thenByDescending { it.realVideoSeconds }
    .thenByDescending { it.event.startEpochMs }
  StorySortMode.Newest -> compareByDescending { it.event.startEpochMs }
  StorySortMode.Oldest -> compareBy { it.event.startEpochMs }
}

private fun friendlyProgress(progress: ProgressSnapshot, making: Boolean = false): FriendlyProgress {
  if (making) {
    return when {
      progress.stage == "queued" || progress.stage == "work_running" -> FriendlyProgress(
        title = "正在准备制作",
        detail = progress.detail.ifBlank { "我会按你选中的故事来剪，完成后会出现在“视频”里。" },
        current = progress.current,
        total = progress.total,
      )
      progress.stage == "ingest" || progress.stage == "event_scout" || progress.stage == "candidate_refresh" -> FriendlyProgress(
        title = "正在制作视频",
        detail = "正在确认这组故事的素材和画面顺序，不会改变你的选择。",
        current = progress.current,
        total = progress.total,
      )
      progress.stage.startsWith("perceive") || progress.stage.startsWith("annotate") -> FriendlyProgress(
        title = "正在挑选入片画面",
        detail = "我在给素材打分，避开模糊、重复或不适合入片的画面。",
        current = progress.current,
        total = progress.total,
      )
      progress.stage.startsWith("render") -> FriendlyProgress(
        title = "正在导出视频",
        detail = "马上就能在“视频”里看到成片。",
        current = progress.current,
        total = progress.total,
      )
      else -> FriendlyProgress(
        title = progress.headline.ifBlank { "正在制作视频" },
        detail = progress.detail.ifBlank { "制作中，完成后会出现在“视频”里。" },
        current = progress.current,
        total = progress.total,
      )
    }
  }
  val defaultDetail = "我在找画面清楚、故事连贯、适合剪成视频的片段。"
  return when {
    progress.stage == "event_scout" || progress.stage == "candidate_refresh" -> FriendlyProgress(
      title = "正在帮你看相册",
      detail = defaultDetail,
      current = progress.current,
      total = progress.total,
    )
    progress.stage == "download" -> FriendlyProgress(
      title = "正在准备模型",
      detail = "第一次使用需要先确认本地模型可以运行。",
      current = progress.current,
      total = progress.total,
    )
    progress.stage.startsWith("perceive") || progress.stage.startsWith("annotate") -> FriendlyProgress(
      title = "正在挑清楚的画面",
      detail = "我会避开模糊、重复或不适合入片的素材。",
      current = progress.current,
      total = progress.total,
    )
    progress.stage.startsWith("render") -> FriendlyProgress(
      title = "正在导出视频",
      detail = "马上就能在“视频”里看到成片。",
      current = progress.current,
      total = progress.total,
    )
    else -> FriendlyProgress(
      title = progress.headline.ifBlank { "正在制作视频" },
      detail = progress.detail.ifBlank { defaultDetail },
      current = progress.current,
      total = progress.total,
    )
  }
}

private fun friendlyIntentLabel(intent: GenerationIntent): String = when (intent) {
  GenerationIntent.AUTO -> "自动帮我选"
  GenerationIntent.TRAVEL -> "旅行回忆"
  GenerationIntent.ZOO -> "动物园/宠物"
  GenerationIntent.PEOPLE -> "家人朋友"
  GenerationIntent.FOOD -> "美食日常"
}

private fun friendlyPowerLabel(profile: PowerProfile): String = when (profile) {
  PowerProfile.LOW_POWER -> "快一点"
  PowerProfile.BALANCED -> "平衡"
  PowerProfile.HIGH_QUALITY -> "效果更好"
}

private fun formatEventRange(startMs: Long, endMs: Long): String {
  val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
  if (startMs <= 0L || endMs <= 0L) return ""
  return "${fmt.format(Date(startMs))} - ${fmt.format(Date(endMs))}"
}

private fun formatMs(ms: Long): String = when {
  ms < 1000 -> "${ms}ms"
  ms < 60_000 -> "${ms / 1000}s"
  else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

private fun formatSec(sec: Double): String = when {
  sec <= 0.0 -> "0s"
  sec < 60.0 -> "${"%.1f".format(Locale.US, sec)}s"
  else -> "${(sec / 60).toInt()}m${(sec % 60).toInt()}s"
}

private fun shortId(id: String): String =
  if (id.length <= 8) id else id.take(6) + "…" + id.takeLast(4)

private fun assetMeta(asset: Asset): String {
  val type = asset.mediaType.name.lowercase()
  val size = if (asset.widthPx > 0 && asset.heightPx > 0) "${asset.widthPx}x${asset.heightPx}" else "unknown size"
  val dur = if (asset.durationMs > 0) " / ${formatSec(asset.durationMs / 1000.0)}" else ""
  return "$type / $size$dur"
}

private fun annotationSummary(tags: VlmTags?, videoSummary: String? = null): String {
  if (tags == null) return "未读取到标注缓存"
  if (tags.scene.isBlank() && tags.subjects.isEmpty() && tags.action.isBlank() && tags.mood.isBlank()) {
    return "VLM 标签为空"
  }
  return listOfNotNull(
    tags.scene.takeIf { it.isNotBlank() },
    tags.subjects.takeIf { it.isNotEmpty() }?.joinToString("、"),
    tags.action.takeIf { it.isNotBlank() },
    tags.mood.takeIf { it.isNotBlank() },
    tags.salient.takeIf { it.isNotBlank() },
    videoSummary?.takeIf { it.isNotBlank() },
  ).joinToString(" · ")
}
