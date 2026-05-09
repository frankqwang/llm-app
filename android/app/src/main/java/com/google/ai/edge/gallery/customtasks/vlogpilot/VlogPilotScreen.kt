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
internal fun VlogPilotScreen(
  selectedTab: VlogPilotTab,
  onTabChange: (VlogPilotTab) -> Unit,
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
  var selectedStoryCategory by remember { mutableStateOf(StoryBrowseCategory.Recommended) }
  var selectedStorySort by remember { mutableStateOf(StorySortMode.Recommended) }
  var selectedVideoCategory by remember { mutableStateOf(VideoBrowseCategory.All) }
  var selectedVideoSort by remember { mutableStateOf(VideoSortMode.Recent) }
  var selectedStoryId by remember { mutableStateOf<String?>(null) }
  var focusedVideoEventId by remember { mutableStateOf<String?>(null) }
  // When non-null, replaces the tab view with a full-screen vlog detail page.
  // Tap a row in 作品库 to navigate in; back-arrow on the detail page clears it.
  var detailEventId by remember { mutableStateOf<String?>(null) }
  // Pending chat prefill — set when the user taps a candidate row in Works
  // and we hop to Chat tab with "做这个候选" pre-typed. Cleared once Chat
  // composes the message. autoSendPrefill = true means the candidate path
  // (1-tap from Works) submits immediately; false means the prefill is
  // typed into the input box but the user still has to confirm.
  var chatPrefill by remember { mutableStateOf<String?>(null) }
  var chatAutoSend by remember { mutableStateOf(false) }
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
          onTabChange(VlogPilotTab.Works)
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
        onTabChange(VlogPilotTab.Chat)
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
    when (selectedTab) {
      VlogPilotTab.Chat -> {
        item {
          com.google.ai.edge.gallery.customtasks.vlogpilot.ui.ChatScreen(
            decisions = decisions,
            eventSelection = eventSelection,
            pendingPrefill = chatPrefill,
            autoSendPrefill = chatAutoSend,
            onPrefillConsumed = {
              chatPrefill = null
              chatAutoSend = false
            },
            onSend = { text, currentEventId ->
              // Phase 3b keyword routing. Order matters: most specific first.
              // The chat VM has already appended the user message; this
              // callback's job is to KICK OFF the right pipeline action.
              val normalized = text.trim()
              val matchedCandidate = matchCandidateByTitle(normalized, eventSelection)
              when {
                // 1) Candidate match: user said "做「夏夜烧烤」" → start that
                //    specific candidate, not a fresh full scan.
                matchedCandidate != null -> {
                  // Mark only this candidate selected, then run.
                  viewModel.toggleSelectedEvent(matchedCandidate.eventId)
                  // makeSelectedStories needs runConfig to actually have the
                  // selected id — toggle is sync so by the time the next
                  // call sees runConfig it should be there. If not, the
                  // call returns early and we fall back to refresh.
                  requireAlbumPermission {
                    selectedModelOrReport()?.let { viewModel.runFullPipeline(it) }
                  }
                }
                // 2) Iteration intent — apply to the currently linked vlog or
                //    the most recent one.
                isIterationCommand(normalized) -> {
                  val targetEventId = currentEventId ?: decisions.firstOrNull()?.eventId
                  if (targetEventId != null) {
                    val feedback = buildIterationFromText(
                      normalized,
                      eventId = targetEventId,
                      baseVersion = decisions.firstOrNull { it.eventId == targetEventId }?.versionCount ?: 1,
                    )
                    viewModel.submitFeedback(targetEventId, feedback)
                  }
                }
                // 3) Generation intent without a specific candidate — scan
                //    + let AI pick.
                isGenerationCommand(normalized) -> {
                  requireAlbumPermission {
                    selectedModelOrReport()?.let { viewModel.refreshCandidates(it) }
                  }
                }
                // 4) Else — no-op, the chat VM keeps the user's message
                //    visible. AI waits for a clearer instruction.
                else -> Unit
              }
            },
            onRescan = {
              requireAlbumPermission {
                selectedModelOrReport()?.let { viewModel.refreshCandidates(it) }
              }
            },
            onOpenCurator = {
              requireAlbumPermission {
                viewModel.loadCuratorAssets()
                curatorOpen = true
              }
            },
          )
        }
      }
      VlogPilotTab.Works -> {
        // Live progress stays at the top so the user sees AI working.
        if (primaryPipelineRunning) {
          item {
            val liveDecision = decisions.firstOrNull { it.mp4Path == null } ?: decisions.firstOrNull()
            StoryProgressCard(
              state = state,
              progress = progress,
              liveDecision = liveDecision,
              onCancel = viewModel::cancelPipeline,
            )
          }
        }
        // Unified feed: completed vlogs + AI-clustered candidates with shared
        // category chips. Tap a candidate → bumps to Chat tab with prefilled
        // "做这个" message; tap a vlog → opens its detail page.
        item {
          UnifiedWorksFeed(
            decisions = decisions,
            manifest = eventSelection,
            onOpenVlog = { eventId -> detailEventId = eventId },
            onMakeCandidate = { snapshot ->
              chatPrefill = "帮我做「${storyTitle(snapshot)}」这个候选故事"
              chatAutoSend = true
              onTabChange(VlogPilotTab.Chat)
            },
          )
        }
        if (decisions.isEmpty() && (eventSelection?.candidates.isNullOrEmpty()) && !primaryPipelineRunning) {
          item {
            EmptyActionCard(
              state = state,
              title = "还没有作品",
              message = "去对话页让 AI 帮你创作第一条 vlog。",
              actionLabel = "开始对话",
              onAction = { onTabChange(VlogPilotTab.Chat) },
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
      onTabChange(VlogPilotTab.Works)
    },
    onOpenVideo = { eventId ->
      detailEventId = eventId
      onTabChange(VlogPilotTab.Works)
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
          onTabChange(VlogPilotTab.Works)
        },
      )
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────
// Chat command routing — keyword heuristics for Phase 3b. Detects whether
// the user typed a generation request, an iteration request, or neither.
// Phase 4 (planned) will replace these with an LLM intent parser, but the
// keyword path covers the most common phrasings cheaply.
// ─────────────────────────────────────────────────────────────────────────

private val GENERATION_KEYWORDS = listOf(
  "做一", "帮我做", "做条", "做一条", "做个", "做一个",
  "推荐", "扫一下", "重扫", "重新扫", "看一下相册", "scan",
  "做下", "整一个", "整一条", "整一下",
)

private val ITERATION_FASTER_KEYWORDS = listOf("快一点", "再快", "加快", "更快", "动感")
private val ITERATION_SLOWER_KEYWORDS = listOf("慢一点", "再慢", "慢点", "留白", "悠长")
private val ITERATION_REMOVE_CAPTIONS_KEYWORDS = listOf("去字幕", "无字幕", "不要字幕")
private val ITERATION_RECOLOR_KEYWORDS = listOf("换调色", "换色调", "换个色", "换色", "重调色")
private val ITERATION_VERBS = listOf("改", "修改", "调整", "再")

internal fun isGenerationCommand(text: String): Boolean =
  GENERATION_KEYWORDS.any { it in text }

/** If the user's text contains a candidate's title (or its core date / type
 *  tokens), return that candidate. Powers the chat handoff: tap a candidate
 *  in Works → "帮我做「TITLE」" auto-sends → router catches it here →
 *  triggers that specific candidate, not a fresh scan. */
internal fun matchCandidateByTitle(
  text: String,
  selection: com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionManifest?,
): com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventCandidateSnapshot? {
  val candidates = selection?.candidates ?: return null
  if (candidates.isEmpty()) return null

  // First pass — exact «title» surrounded by Chinese book-title quotes
  // (the auto-generated handoff format).
  val quoted = Regex("「(.+?)」").find(text)?.groupValues?.getOrNull(1)?.trim()
  if (!quoted.isNullOrBlank()) {
    candidates.firstOrNull { storyTitle(it) == quoted }?.let { return it }
    candidates.firstOrNull { storyTitle(it).contains(quoted) }?.let { return it }
    candidates.firstOrNull { quoted.contains(storyTitle(it)) }?.let { return it }
  }

  // Second pass — substring of any candidate's title in the user's text.
  // Sort by title length desc so "4月26日的动物故事" beats just "动物".
  return candidates
    .sortedByDescending { storyTitle(it).length }
    .firstOrNull { c ->
      val t = storyTitle(c).trim()
      t.isNotBlank() && t in text
    }
}

internal fun isIterationCommand(text: String): Boolean =
  ITERATION_FASTER_KEYWORDS.any { it in text } ||
    ITERATION_SLOWER_KEYWORDS.any { it in text } ||
    ITERATION_REMOVE_CAPTIONS_KEYWORDS.any { it in text } ||
    ITERATION_RECOLOR_KEYWORDS.any { it in text } ||
    ITERATION_VERBS.any { text.startsWith(it) || " $it" in text }

/** Rough text → IterationFeedback mapping. Picks the matching QuickActions
 *  and folds the original userText through so the worker's IntentParserAgent
 *  can refine if it wants. */
internal fun buildIterationFromText(
  text: String,
  eventId: String,
  baseVersion: Int,
): com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.IterationFeedback {
  val actions = buildList {
    if (ITERATION_FASTER_KEYWORDS.any { it in text }) {
      add(com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.QuickAction.FASTER_OVERALL)
    }
    if (ITERATION_SLOWER_KEYWORDS.any { it in text }) {
      add(com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.QuickAction.SLOWER_OVERALL)
    }
    if (ITERATION_REMOVE_CAPTIONS_KEYWORDS.any { it in text }) {
      add(com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.QuickAction.REMOVE_CAPTIONS)
    }
    if (ITERATION_RECOLOR_KEYWORDS.any { it in text }) {
      add(com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.QuickAction.CHANGE_COLOR_GRADE)
    }
  }
  return com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.IterationPlanner.fromQuickActions(
    iterationId = "iter_%03d".format(baseVersion + 1),
    baseTimelineVersion = baseVersion,
    actions = actions,
    targetedShotOrders = emptyList(),
    userText = text,
    currentColorGrade = com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ColorGrade.NEUTRAL,
    currentPace = com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Pace.BALANCED,
  )
}

