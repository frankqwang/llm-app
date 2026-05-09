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
  val projects by viewModel.projects.collectAsState()
  val progress by viewModel.progress.collectAsState()
  val runConfig by viewModel.runConfig.collectAsState()
  val eventSelection by viewModel.eventSelection.collectAsState()
  val activeIteration by viewModel.activeIteration.collectAsState()
  val curatorAssets by viewModel.curatorAssets.collectAsState()
  val curatorLoading by viewModel.curatorLoading.collectAsState()
  val albumAssets by viewModel.albumAssets.collectAsState()
  val albumVisibleCount by viewModel.albumVisibleCount.collectAsState()
  val albumLoading by viewModel.albumLoading.collectAsState()
  val albumError by viewModel.albumError.collectAsState()
  val assetUsage by viewModel.assetUsage.collectAsState()
  val operation by viewModel.operation.collectAsState()
  val context = LocalContext.current
  var selectedTab by remember { mutableStateOf(VlogPilotTab.Create) }
  var selectedStoryCategory by remember { mutableStateOf(StoryBrowseCategory.Recommended) }
  var selectedStorySort by remember { mutableStateOf(StorySortMode.Recommended) }
  var selectedVideoCategory by remember { mutableStateOf(VideoBrowseCategory.All) }
  var selectedVideoSort by remember { mutableStateOf(VideoSortMode.Recent) }
  var selectedStoryId by remember { mutableStateOf<String?>(null) }
  var focusedVideoEventId by remember { mutableStateOf<String?>(null) }
  // When non-null, replaces the tab view with a full-screen vlog detail page.
  // Tap a row in 作品库 to navigate in; back-arrow on the detail page clears it.
  var detailEventId by remember { mutableStateOf<String?>(null) }
  // When non-null, the bottom-sheet feedback editor is open targeting this event.
  var iterationSheetEventId by remember { mutableStateOf<String?>(null) }
  var iterationSheetTargetOrder by remember { mutableStateOf<Int?>(null) }
  // Full-screen curator overlay — when true, replaces the main tabbed view.
  var curatorOpen by remember { mutableStateOf(false) }
  // Inline error banner shown inside CuratorScreen when submit fails (e.g. no model imported).
  var curatorError by remember { mutableStateOf<String?>(null) }
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

  val primaryPipelineRunning = operation.blocksPrimaryPipeline

  // Full-screen curator overlay — replaces the tabbed body when active. Dialogs
  // (StoryDetail / IterationSheet) below this composable still render normally.
  if (curatorOpen) {
    CuratorScreen(
      assets = curatorAssets,
      loading = curatorLoading,
      errorMessage = curatorError,
      onBack = {
        curatorError = null
        curatorOpen = false
      },
      onDismissError = { curatorError = null },
      onSubmit = { selectedIds, intentText ->
        val model = modelManagerViewModel.getAllDownloadedModels().let { downloaded ->
          downloaded.firstOrNull { it.name.contains("gemma-4", ignoreCase = true) }
            ?: downloaded.firstOrNull()
        }
        if (model != null) {
          curatorError = null
          viewModel.submitCuratedRequest(selectedIds, intentText, model)
          curatorOpen = false
          selectedTab = VlogPilotTab.Works
        } else {
          // Keep curator open so the user doesn't lose their selection + intent text.
          // Banner explains what they need to do next.
          curatorError = "没有找到已导入的 LLM。请先在 Models 中导入一个支持图像的本地模型（例如 Gemma 4 E2B-IT）。"
        }
      },
    )
    return
  }

  // Album state lives outside the LazyColumn so the asset detail dialog
  // can render at the screen level (Dialog isn't valid inside LazyListScope).
  val albumState = com.google.ai.edge.gallery.customtasks.vlogpilot.rememberAssetLibraryUiState()

  // Vlog detail navigation: full-screen page replaces the tabbed view when
  // a row is tapped in 作品库. Hardware back arrow returns to the list.
  detailEventId?.let { id ->
    com.google.ai.edge.gallery.customtasks.vlogpilot.VlogDetailScreen(
      decisions = decisions,
      projects = projects,
      eventId = id,
      onBack = { detailEventId = null },
      onOpenIterationSheet = { eventId, shotOrder ->
        iterationSheetEventId = eventId
        iterationSheetTargetOrder = shotOrder
      },
      onChangeStory = {
        detailEventId = null
        selectedTab = VlogPilotTab.Create
      },
    )
    iterationSheetEventId?.let { eventId ->
      val target = decisions.firstOrNull { it.eventId == eventId } ?: return@let
      IterationSheet(
        decisions = target,
        initialTargetShotOrder = iterationSheetTargetOrder,
        onDismiss = {
          iterationSheetEventId = null
          iterationSheetTargetOrder = null
        },
        onSubmit = { feedback ->
          viewModel.submitFeedback(eventId, feedback)
          iterationSheetEventId = null
          iterationSheetTargetOrder = null
        },
      )
    }
    return
  }

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

    when (selectedTab) {
      VlogPilotTab.Create -> {
        val manifest = eventSelection
        when {
          manifest == null && primaryPipelineRunning -> item {
            // Pick the most recent in-flight event (no mp4 yet) so its
            // partial agent outputs feed the live work panel. Falls back to
            // the most recent decision overall if nothing is in flight yet
            // (early seconds of perception / annotation).
            val liveDecision = decisions.firstOrNull { it.mp4Path == null } ?: decisions.firstOrNull()
            StoryProgressCard(
              state = state,
              progress = progress,
              liveDecision = liveDecision,
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
              onVideosClick = { selectedTab = VlogPilotTab.Works },
              onCurateClick = {
                requireAlbumPermission {
                  viewModel.loadCuratorAssets()
                  curatorOpen = true
                }
              },
            )
          }

          else -> {
            item {
              StoryShelf(
                manifest = manifest,
                runConfig = runConfig,
                running = primaryPipelineRunning,
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

      VlogPilotTab.Works -> {
        if (decisions.isEmpty() && projects.isEmpty()) {
          item {
            EmptyActionCard(
              state = state,
              title = "还没有作品",
              message = "先在创作页选推荐故事，或手动挑素材生成第一版。",
              actionLabel = "去创作",
              onAction = { selectedTab = VlogPilotTab.Create },
            )
          }
        } else {
          item {
            VideoShelf(
              projects = projects,
              decisions = decisions,
              activeIteration = activeIteration,
              selectedCategory = selectedVideoCategory,
              selectedSort = selectedVideoSort,
              onCategorySelect = { selectedVideoCategory = it },
              onSortSelect = { selectedVideoSort = it },
              onOpenDetail = { eventId -> detailEventId = eventId },
              onDismissIteration = viewModel::dismissIterationStatus,
            )
          }
        }
      }

      VlogPilotTab.Assets -> {
        assetLibraryItems(
          state = albumState,
          assets = albumAssets,
          visibleCount = albumVisibleCount,
          loading = albumLoading,
          errorMessage = albumError,
          usageByAssetId = assetUsage,
          onLoad = {
            requireAlbumPermission {
              viewModel.loadAlbumAssets()
            }
          },
          onRefresh = {
            requireAlbumPermission {
              viewModel.loadAlbumAssets(force = true)
            }
          },
          onLoadMore = viewModel::loadMoreAlbumAssets,
        )
      }

      VlogPilotTab.Settings -> {
        item {
          SettingsCard(
            runConfig = runConfig,
            running = primaryPipelineRunning,
            onIntentSelect = viewModel::setIntent,
            onPowerSelect = viewModel::setPowerProfile,
          )
        }
        item { PromptDebugCard() }
      }
    }
  }

  com.google.ai.edge.gallery.customtasks.vlogpilot.AssetLibraryDialog(
    state = albumState,
    assets = albumAssets,
    usageByAssetId = assetUsage,
    decisions = decisions,
    manifest = eventSelection,
    onOpenStory = { eventId ->
      selectedStoryId = eventId
      selectedTab = VlogPilotTab.Create
    },
    onOpenVideo = { eventId ->
      detailEventId = eventId
      selectedTab = VlogPilotTab.Works
    },
  )

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
        initialTargetShotOrder = iterationSheetTargetOrder,
        onDismiss = {
          iterationSheetEventId = null
          iterationSheetTargetOrder = null
        },
        onSubmit = { feedback ->
          viewModel.submitFeedback(eid, feedback)
          iterationSheetEventId = null
          iterationSheetTargetOrder = null
          // Auto-switch to Works tab so the user sees the progress strip.
          selectedTab = VlogPilotTab.Works
        },
      )
    }
  }
}

