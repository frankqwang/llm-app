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
  val activeIteration by viewModel.activeIteration.collectAsState()
  val context = LocalContext.current
  var selectedTab by remember { mutableStateOf(VlogPilotTab.Stories) }
  var selectedAdvancedTab by remember { mutableStateOf(VlogPilotAdvancedTab.Process) }
  var selectedStoryCategory by remember { mutableStateOf(StoryBrowseCategory.Recommended) }
  var selectedStorySort by remember { mutableStateOf(StorySortMode.Recommended) }
  var selectedStoryId by remember { mutableStateOf<String?>(null) }
  // When non-null, the bottom-sheet feedback editor is open targeting this event.
  var iterationSheetEventId by remember { mutableStateOf<String?>(null) }
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

  fun makeSelectedStories() {
    if (runConfig.onlySelectedEventIds.isEmpty()) return
    requireAlbumPermission {
      selectedModelOrReport()?.let { model ->
        viewModel.runFullPipeline(model)
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
          manifest == null && running -> item {
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
                running = running,
                progress = progress,
                decisions = decisions,
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
                onCancel = viewModel::cancelPipeline,
                onToggleStory = viewModel::toggleSelectedEvent,
                onMakeSelectedStories = ::makeSelectedStories,
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
          if (activeIteration != null) {
            item {
              IterationProgressStrip(
                snapshot = activeIteration,
                onDismiss = viewModel::dismissIterationStatus,
              )
            }
          }
          items(decisions, key = { "video-${it.eventId}" }) { decision ->
            ResultEventCard(
              d = decision,
              onOpenIterationSheet = { iterationSheetEventId = it },
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

  iterationSheetEventId?.let { eid ->
    val target = decisions.firstOrNull { it.eventId == eid }
    if (target == null) {
      // The decision was deleted in the background — close the sheet silently.
      iterationSheetEventId = null
    } else {
      IterationSheet(
        decisions = target,
        onDismiss = { iterationSheetEventId = null },
        onSubmit = { feedback ->
          viewModel.submitFeedback(eid, feedback)
          iterationSheetEventId = null
          // Auto-switch to Videos tab so the user sees the progress strip.
          selectedTab = VlogPilotTab.Videos
        },
      )
    }
  }
}

