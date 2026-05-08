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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
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
  var selectedTab by remember { mutableStateOf(VlogPilotTab.Results) }
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
      StudioStatusCard(
        state = state,
        decisions = decisions,
        progress = progress,
        runConfig = runConfig,
        eventSelection = eventSelection,
        running = running,
        onRefreshClick = {
          requireAlbumPermission {
            selectedModelOrReport()?.let { viewModel.refreshCandidates(it) }
          }
        },
        onRunClick = {
          if (running) {
            viewModel.cancelPipeline()
          } else {
            requireAlbumPermission { launchPipeline() }
          }
        },
        onIntentSelect = viewModel::setIntent,
        onPowerSelect = viewModel::setPowerProfile,
      )
    }

    if (running) {
      item {
        LinearProgressIndicator(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp)),
        )
      }
    }

    item {
      WorkspaceTabs(selected = selectedTab, onSelect = { selectedTab = it })
    }

    val ready = state as? PipelineState.Ready
    when (selectedTab) {
      VlogPilotTab.Results -> {
        if (decisions.isEmpty()) {
          item { EmptyProcessCard(state, "还没有生成结果", "完成生成后，这里只展示成片和最终时间线。") }
        } else {
          items(decisions, key = { "result-${it.eventId}" }) { decision ->
            ResultEventCard(decision)
          }
        }
      }

      VlogPilotTab.Events -> {
        val manifest = eventSelection
        if (manifest == null) {
          item {
            EmptyProcessCard(
              state = state,
              title = "还没有候选事件",
              message = "点击刷新候选，只做轻量扫描和排序，不会启动 VLM 或渲染。",
            )
          }
        } else {
          item {
            EventSelectionHeader(
              manifest = manifest,
              runConfig = runConfig,
              onClearOnly = viewModel::clearOnlySelected,
            )
          }
          items(manifest.candidates, key = { "event-select-${it.eventId}" }) { candidate ->
            EventCandidateCard(
              candidate = candidate,
              runConfig = runConfig,
              onPin = viewModel::pinEvent,
              onExclude = viewModel::excludeEvent,
              onClearExcluded = viewModel::clearExcluded,
              onOnly = viewModel::onlyGenerateEvent,
              onRegenerate = viewModel::forceRegenerateEvent,
            )
          }
        }
      }

      VlogPilotTab.Process -> {
        if (decisions.isEmpty()) {
          item { EmptyProcessCard(state, "还没有过程数据", "开始生成后，这里会逐步出现输入素材、Agent 输出和渲染状态。") }
        } else {
          items(decisions, key = { "process-${it.eventId}" }) { decision ->
            EventDecisionCard(decision)
          }
        }
      }

      VlogPilotTab.Assets -> {
        if (ready != null) {
          item { AlbumPreviewCard(assets = ready.assets, events = ready.events) }
        }
        if (decisions.isEmpty()) {
          item { EmptyProcessCard(state, "还没有素材清单", "事件开始处理后，这里会显示每张素材的感知分数和 VLM 标签。") }
        } else {
          items(decisions, key = { "assets-${it.eventId}" }) { decision ->
            AssetManagementCard(decision)
          }
        }
      }

      VlogPilotTab.Prompts -> {
        item { PromptCatalog() }
      }
    }
  }
}

private enum class VlogPilotTab(val label: String, val icon: ImageVector) {
  Results("生成结果", Icons.Outlined.Movie),
  Events("事件选择", Icons.Outlined.Search),
  Process("过程透视", Icons.Outlined.Visibility),
  Assets("素材管理", Icons.Outlined.PhotoLibrary),
  Prompts("Prompt", Icons.Outlined.Edit),
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
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      items(options) { option ->
        val active = option == selected
        Surface(
          modifier = if (enabled) Modifier.clickable { onSelect(option) } else Modifier,
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

@Composable
private fun StudioStatusCard(
  state: PipelineState,
  decisions: List<EventDecisions>,
  progress: ProgressSnapshot,
  runConfig: VlogPilotRunConfig,
  eventSelection: EventSelectionManifest?,
  running: Boolean,
  onRefreshClick: () -> Unit,
  onRunClick: () -> Unit,
  onIntentSelect: (GenerationIntent) -> Unit,
  onPowerSelect: (PowerProfile) -> Unit,
) {
  val rendered = decisions.count { it.mp4Path != null }
  val totalShots = decisions.sumOf { (it.timelineFinal ?: it.timelineV1)?.shots?.size ?: 0 }
  val scannedAssets = decisions.sumOf { it.inputAssets.size }

  ElevatedCard(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.elevatedCardColors(
      containerColor = MaterialTheme.colorScheme.surface,
    ),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
          Icon(Icons.Outlined.Movie, contentDescription = null, modifier = Modifier.padding(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
          Text("过程透视", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(
            statusText(state),
            style = MaterialTheme.typography.bodySmall,
            color = if (state is PipelineState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (running) {
          FilledTonalButton(onClick = onRunClick) { Text("取消") }
        } else {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onRefreshClick) { Text("VLM浏览") }
            Button(onClick = onRunClick) { Text("生成") }
          }
        }
      }

      ControlSegment(
        label = "生成意图",
        options = GenerationIntent.entries.toList(),
        selected = runConfig.intent,
        optionLabel = ::intentLabel,
        onSelect = onIntentSelect,
        enabled = !running,
      )
      ControlSegment(
        label = "运行模式",
        options = PowerProfile.entries.toList(),
        selected = runConfig.powerProfile,
        optionLabel = ::powerLabel,
        onSelect = onPowerSelect,
        enabled = !running,
      )

      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { InfoPill("事件 ${decisions.size}", Icons.Outlined.Visibility) }
        item { InfoPill("候选 ${eventSelection?.candidateCount ?: 0}", Icons.Outlined.Search, eventSelection != null) }
        item { InfoPill("选中 ${eventSelection?.selectedEventIds?.size ?: 0}", Icons.Outlined.Star, eventSelection?.selectedEventIds?.isNotEmpty() == true) }
        item { InfoPill("扫描 $scannedAssets", Icons.Outlined.PhotoLibrary) }
        item { InfoPill("镜头 $totalShots", Icons.Outlined.Edit) }
        item {
          InfoPill(
            text = "成片 $rendered",
            icon = if (rendered > 0) Icons.Outlined.CheckCircle else Icons.Outlined.Movie,
            accent = rendered > 0,
          )
        }
      }

      if (running || progress.stage != "idle") {
        ProgressInsightPanel(progress)
      }
    }
  }
}

@Composable
private fun ProgressInsightPanel(progress: ProgressSnapshot) {
  val fraction = remember(progress.current, progress.total) {
    if (progress.total > 0) (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) else null
  }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
      ) {
        Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(progress.headline, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
          if (progress.detail.isNotBlank()) {
            Text(
              progress.detail,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
          }
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
      }

      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (progress.stage.isNotBlank()) item { InfoPill(progress.stage, Icons.Outlined.Star, progress.stage.contains("done")) }
        if (progress.current > 0 && progress.total > 0) item { InfoPill("${progress.current}/${progress.total}", Icons.Outlined.Timer) }
        if (progress.mediaType.isNotBlank()) item { InfoPill(progress.mediaType, Icons.Outlined.Movie) }
        if (progress.elapsedMs > 0) item { InfoPill(formatMs(progress.elapsedMs), Icons.Outlined.Timer, true) }
      }

      if (progress.recent.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
          progress.recent.take(4).forEach { item ->
            Text(
              item,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun AlbumPreviewCard(assets: List<Asset>, events: List<Event>) {
  ElevatedCard(modifier = Modifier.fillMaxWidth()) {
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
private fun EventSelectionHeader(
  manifest: EventSelectionManifest,
  runConfig: VlogPilotRunConfig,
  onClearOnly: () -> Unit,
) {
  ElevatedCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      SectionHeader(
        icon = Icons.Outlined.Search,
        title = "事件选择",
        subtitle = "VLM 先按 3x3 contact sheet 浏览候选事件，再做语义排序",
      )
      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { InfoPill("候选 ${manifest.candidateCount}", Icons.Outlined.Search, true) }
        item { InfoPill("选中 ${manifest.selectedEventIds.size}", Icons.Outlined.Star, manifest.selectedEventIds.isNotEmpty()) }
        item { InfoPill(intentLabel(manifest.intent), Icons.Outlined.Visibility) }
        item { InfoPill(powerLabel(manifest.powerProfile), Icons.Outlined.Timer) }
      }
      if (runConfig.onlySelectedEventIds.isNotEmpty()) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            "当前只生成指定事件：${runConfig.onlySelectedEventIds.joinToString { shortId(it) }}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FilledTonalButton(onClick = onClearOnly) { Text("恢复自动") }
        }
      }
    }
  }
}

@Composable
private fun EventCandidateCard(
  candidate: EventCandidateSnapshot,
  runConfig: VlogPilotRunConfig,
  onPin: (String) -> Unit,
  onExclude: (String) -> Unit,
  onClearExcluded: (String) -> Unit,
  onOnly: (String) -> Unit,
  onRegenerate: (String) -> Unit,
) {
  ElevatedCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
      ) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = when (candidate.status) {
            EventSelectionStatus.SELECTED -> MaterialTheme.colorScheme.primaryContainer
            EventSelectionStatus.EXCLUDED -> MaterialTheme.colorScheme.errorContainer
            EventSelectionStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
          },
          contentColor = when (candidate.status) {
            EventSelectionStatus.SELECTED -> MaterialTheme.colorScheme.onPrimaryContainer
            EventSelectionStatus.EXCLUDED -> MaterialTheme.colorScheme.onErrorContainer
            EventSelectionStatus.COMPLETED -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
          },
        ) {
          Icon(
            imageVector = if (candidate.status == EventSelectionStatus.SELECTED) Icons.Outlined.Star else Icons.Outlined.Visibility,
            contentDescription = null,
            modifier = Modifier.padding(10.dp),
          )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text("事件 ${shortId(candidate.eventId)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(
            listOfNotNull(
              formatEventRange(candidate.event.startEpochMs, candidate.event.endEpochMs).takeIf { it.isNotBlank() },
              "${candidate.assets.size} 个预览素材",
              "${candidate.realVideoCount} 段视频 / ${formatSec(candidate.realVideoSeconds.toDouble())}",
              "${"%.1f".format(Locale.US, candidate.spanHours)}h",
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        InfoPill(eventStatusLabel(candidate.status), Icons.Outlined.CheckCircle, candidate.status == EventSelectionStatus.SELECTED)
      }

      LinearProgressIndicator(
        progress = { candidate.valueScore.coerceIn(0f, 1f) },
        modifier = Modifier
          .fillMaxWidth()
          .height(6.dp)
          .clip(RoundedCornerShape(999.dp)),
      )

      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { InfoPill("value ${(candidate.valueScore * 100).toInt()}", Icons.Outlined.Star, candidate.status == EventSelectionStatus.SELECTED) }
        item { InfoPill(candidate.rankingMode, Icons.Outlined.Search, candidate.rankingMode == "vlm_scout") }
        item { InfoPill("travel ${(candidate.travelScore * 100).toInt()}", Icons.Outlined.Visibility) }
        item { InfoPill("media ${(candidate.mediaScore * 100).toInt()}", Icons.Outlined.Movie) }
        item { InfoPill("story ${(candidate.storyScore * 100).toInt()}", Icons.Outlined.Edit) }
        if (candidate.gpsAssetCount > 0) item { InfoPill("GPS ${candidate.gpsAssetCount}", Icons.Outlined.Search, true) }
      }

      if (candidate.scoutSummary.isNotBlank()) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        ) {
          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              item { InfoPill(candidate.scoutEventType.ifBlank { "scout" }, Icons.Outlined.Visibility, candidate.scoutRecommended) }
              item { InfoPill("story ${(candidate.scoutStoryValue * 100).toInt()}", Icons.Outlined.Edit) }
              item { InfoPill("visual ${(candidate.scoutVisualValue * 100).toInt()}", Icons.Outlined.PhotoLibrary) }
              item { InfoPill("pages ${candidate.scoutPageCount}", Icons.Outlined.PhotoLibrary, candidate.scoutSampled) }
            }
            Text(
              candidate.scoutSummary,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
            if (candidate.scoutRejectReasons.isNotEmpty()) {
              Text(
                "caution: ${candidate.scoutRejectReasons.joinToString(" · ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }

      if (candidate.reasons.isNotEmpty()) {
        Text(
          candidate.reasons.joinToString(" · "),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }

      AssetStrip(assets = candidate.assets.take(12), totalCount = candidate.event.assetIds.size)

      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (candidate.status == EventSelectionStatus.EXCLUDED) {
          item { FilledTonalButton(onClick = { onClearExcluded(candidate.eventId) }) { Text("取消排除") } }
        } else {
          item { FilledTonalButton(onClick = { onExclude(candidate.eventId) }) { Text("排除") } }
        }
        item { FilledTonalButton(onClick = { onPin(candidate.eventId) }) { Text(if (candidate.eventId in runConfig.pinnedEventIds) "已优先" else "优先生成") } }
        item { Button(onClick = { onOnly(candidate.eventId) }) { Text("只生成此事件") } }
        item { FilledTonalButton(onClick = { onRegenerate(candidate.eventId) }) { Text("重新生成") } }
      }
    }
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
private fun ResultEventCard(d: EventDecisions) {
  var showTimeline by remember { mutableStateOf(false) }
  val timeline = d.timelineFinal ?: d.timelineV1
  val title = d.director?.title?.takeIf { it.isNotBlank() } ?: "事件 ${shortId(d.eventId)}"
  val durationSec = timeline?.shots?.sumOf { it.durationSec.toDouble() } ?: 0.0
  val assetMap = remember(d.inputAssets) { d.inputAssets.associateBy { it.id } }

  ElevatedCard(modifier = Modifier.fillMaxWidth()) {
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
            eventSubtitle(d),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { InfoPill("镜头 ${timeline?.shots?.size ?: 0}", Icons.Outlined.Edit) }
        item { InfoPill(formatSec(durationSec), Icons.Outlined.Timer) }
        item { InfoPill("素材 ${d.inputAssets.size}", Icons.Outlined.PhotoLibrary) }
        item {
          InfoPill(
            text = if (d.mp4Path != null) "已渲染" else "等待渲染",
            icon = if (d.mp4Path != null) Icons.Outlined.CheckCircle else Icons.Outlined.Movie,
            accent = d.mp4Path != null,
          )
        }
      }

      d.mp4Path?.let { VideoPreview(mp4Path = it) }

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

  ElevatedCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      SectionHeader(
        icon = Icons.Outlined.PhotoLibrary,
        title = "事件 ${shortId(d.eventId)} 素材",
        subtitle = "${d.inputAssets.size} 个输入 / $tagged 个有 VLM 标签",
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
      .clip(RoundedCornerShape(14.dp))
      .clickable { expanded = !expanded },
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
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
            item { InfoPill(if (tags?.scene?.isNotBlank() == true) "已标注" else "未标注", Icons.Outlined.Search, tags?.scene?.isNotBlank() == true) }
            if (usedOrders.isNotEmpty()) item { InfoPill("入片 #${usedOrders.joinToString(",")}", Icons.Outlined.CheckCircle, true) }
            if ((videoInsight?.bestMomentSec ?: 0f) > 0f) item { InfoPill("best ${"%.1fs".format(Locale.US, videoInsight!!.bestMomentSec)}", Icons.Outlined.PlayArrow, true) }
            item { InfoPill("face ${perception?.faces?.size ?: 0}", Icons.Outlined.Person) }
          }
        }
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

  ElevatedCard(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.elevatedCardColors(
      containerColor = MaterialTheme.colorScheme.surface,
    ),
  ) {
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

      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { InfoPill("素材 ${d.inputAssets.size}", Icons.Outlined.PhotoLibrary) }
        item { InfoPill("镜头 $shotCount", Icons.Outlined.Edit) }
        item { InfoPill(formatSec(durationSec), Icons.Outlined.Timer) }
        item {
          InfoPill(
            text = if (d.mp4Path != null) "已渲染" else "未渲染",
            icon = if (d.mp4Path != null) Icons.Outlined.CheckCircle else Icons.Outlined.Movie,
            accent = d.mp4Path != null,
          )
        }
      }

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
  LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    items(stages) { stage ->
      StagePill(stage)
    }
  }
}

@Composable
private fun StagePill(stage: StageUi) {
  val container = if (stage.done) {
    MaterialTheme.colorScheme.primaryContainer
  } else {
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
  }
  val content = if (stage.done) {
    MaterialTheme.colorScheme.onPrimaryContainer
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }
  Surface(shape = RoundedCornerShape(999.dp), color = container, contentColor = content) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = if (stage.done) Icons.Outlined.CheckCircle else Icons.Outlined.Star,
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
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
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

@Composable
private fun InfoPill(text: String, icon: ImageVector, accent: Boolean = false) {
  val container = if (accent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
  val content = if (accent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
  Surface(shape = RoundedCornerShape(999.dp), color = container, contentColor = content) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
      Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
  }
}

@Composable
private fun PromptCatalog() {
  ElevatedCard(modifier = Modifier.fillMaxWidth()) {
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
      .clip(RoundedCornerShape(16.dp))
      .clickable { expanded = !expanded },
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
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

private fun eventSubtitle(d: EventDecisions): String {
  val range = d.event?.let { formatEventRange(it.startEpochMs, it.endEpochMs) }
  val place = d.event?.placeHint?.takeIf { it.isNotBlank() }
  return listOfNotNull(range, place, d.eventId).joinToString(" · ")
}

private fun statusText(state: PipelineState): String = when (state) {
  is PipelineState.Idle -> "等待开始"
  is PipelineState.Scanning -> state.phase
  is PipelineState.Ready -> "${state.assets.size} 个素材，${state.events.size} 个事件，可开始生成"
  is PipelineState.Running -> state.message
  is PipelineState.Done -> "完成，生成 ${state.outputs.size} 个候选视频"
  is PipelineState.Error -> "错误：${state.message}"
}

private fun intentLabel(intent: GenerationIntent): String = when (intent) {
  GenerationIntent.AUTO -> "自动"
  GenerationIntent.TRAVEL -> "旅行"
  GenerationIntent.ZOO -> "动物园"
  GenerationIntent.PEOPLE -> "人物"
  GenerationIntent.FOOD -> "美食"
}

private fun powerLabel(profile: PowerProfile): String = when (profile) {
  PowerProfile.LOW_POWER -> "低功耗"
  PowerProfile.BALANCED -> "均衡"
  PowerProfile.HIGH_QUALITY -> "高质量"
}

private fun eventStatusLabel(status: EventSelectionStatus): String = when (status) {
  EventSelectionStatus.SELECTED -> "已选中"
  EventSelectionStatus.RESUME -> "可续跑"
  EventSelectionStatus.FRESH -> "新候选"
  EventSelectionStatus.COMPLETED -> "已完成"
  EventSelectionStatus.EXCLUDED -> "已排除"
  EventSelectionStatus.NOT_SELECTED -> "未选中"
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
