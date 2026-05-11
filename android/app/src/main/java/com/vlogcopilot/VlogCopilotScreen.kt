/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Process viewer for the on-device VlogCopilot pipeline. The screen is built as
 * a compact editing console: input assets, agent outputs, timeline decisions,
 * critique notes, and the rendered candidate stay in one inspectable flow.
 */
package com.vlogcopilot

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.vlogcopilot.agents.AgentRuntime
import com.vlogcopilot.agents.ChatPlannerAgent
import com.vlogcopilot.agents.PromptStrings
import com.vlogcopilot.agents.EventScoutAgent
import com.vlogcopilot.agents.VlmAnnotator
import com.vlogcopilot.perception.MediaLoader
import com.vlogcopilot.runtime.GenerationIntent
import com.vlogcopilot.runtime.PowerProfile
import com.vlogcopilot.runtime.VlogCopilotRunConfig
import com.vlogcopilot.schemas.Asset
import com.vlogcopilot.schemas.Event
import com.vlogcopilot.schemas.MediaType
import com.vlogcopilot.schemas.Perception
import com.vlogcopilot.schemas.ShotSpec
import com.vlogcopilot.schemas.Timeline
import com.vlogcopilot.schemas.VlmTags
import com.vlogcopilot.worker.EventDecisions
import com.vlogcopilot.worker.EventCandidateSnapshot
import com.vlogcopilot.worker.EventSelectionManifest
import com.vlogcopilot.worker.EventSelectionStatus
import com.vlogcopilot.worker.StagePerf
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingChatPlan(
  val plan: ChatPlannerAgent.Plan,
  val originalText: String,
  val currentEventId: String?,
  val model: com.google.ai.edge.gallery.data.Model,
)

@Composable
internal fun VlogCopilotScreen(
  selectedTab: VlogCopilotTab,
  onTabChange: (VlogCopilotTab) -> Unit,
  chatViewModel: com.vlogcopilot.chat.ChatViewModel,
  bottomPadding: Dp,
  modelManagerViewModel: ModelManagerViewModel,
  onOpenGallery: () -> Unit,
  onOpenModelManager: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: VlogCopilotViewModel = hiltViewModel(),
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
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val context = LocalContext.current
  val chatAgentScope = rememberCoroutineScope()
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
  var chatFocusEventId by remember { mutableStateOf<String?>(null) }
  // When non-null, the bottom-sheet feedback editor is open targeting this event.
  var iterationSheetEventId by remember { mutableStateOf<String?>(null) }
  var iterationSheetTargetOrder by remember { mutableStateOf<Int?>(null) }
  // Full-screen curator overlay — when true, replaces the main tabbed view.
  var curatorOpen by remember { mutableStateOf(false) }
  // Inline error banner shown inside CuratorScreen when submit fails (e.g. no model imported).
  var curatorError by remember { mutableStateOf<String?>(null) }
  var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }

  LaunchedEffect(selectedTab) {
    if (selectedTab != VlogCopilotTab.Works) {
      detailEventId = null
      selectedStoryId = null
      iterationSheetEventId = null
      iterationSheetTargetOrder = null
    }
    if (selectedTab != VlogCopilotTab.Chat) {
      curatorOpen = false
      curatorError = null
    }
  }

  BackHandler(enabled = curatorOpen) {
    curatorError = null
    curatorOpen = false
  }
  BackHandler(enabled = detailEventId != null && iterationSheetEventId == null) {
    detailEventId = null
  }
  BackHandler(enabled = iterationSheetEventId != null) {
    iterationSheetEventId = null
    iterationSheetTargetOrder = null
  }
  BackHandler(enabled = selectedStoryId != null) {
    selectedStoryId = null
  }

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

  val downloadedModels = remember(
    modelManagerUiState.modelDownloadStatus,
    modelManagerUiState.modelImportingUpdateTrigger,
    modelManagerUiState.tasks,
  ) { modelManagerViewModel.getAllDownloadedModels() }
  val preferredModel = remember(downloadedModels) {
    downloadedModels.firstOrNull { it.name.contains("gemma-4", ignoreCase = true) }
      ?: downloadedModels.firstOrNull()
  }
  val preferredModelName = preferredModel?.let { it.displayName.ifBlank { it.name } }

  val primaryPipelineRunning = operation.blocksPrimaryPipeline
  var pendingPostRunPlan by remember { mutableStateOf<PendingChatPlan?>(null) }
  val chatFocusedCandidate = remember(chatFocusEventId, eventSelection, runConfig, primaryPipelineRunning) {
    val activeId = chatFocusEventId
      ?: if (primaryPipelineRunning) {
        runConfig.onlySelectedEventIds.firstOrNull()
          ?: eventSelection?.selectedEventIds?.firstOrNull()
      } else {
        null
      }
    activeId?.let { id -> eventSelection?.candidates?.firstOrNull { it.eventId == id } }
      ?: if (primaryPipelineRunning) {
        eventSelection?.candidates?.firstOrNull { it.eventId in runConfig.onlySelectedEventIds }
      } else {
        null
      }
  }

  fun buildChatPlannerContext(currentEventId: String?): ChatPlannerAgent.ChatContext {
    val activeId = currentEventId
      ?: chatFocusEventId
      ?: if (primaryPipelineRunning) {
        runConfig.onlySelectedEventIds.firstOrNull()
          ?: eventSelection?.selectedEventIds?.firstOrNull()
      } else {
        decisions.firstOrNull()?.eventId
      }
    val videos = decisions.take(6).map { d ->
      val timeline = d.timelineFinal ?: d.timelineV1
      ChatPlannerAgent.VideoSummary(
        eventId = d.eventId,
        title = storyTitle(d),
        shotCount = timeline?.shots?.size ?: 0,
        assetCount = d.inputAssets.size,
        durationSec = timeline?.shots?.sumOf { it.durationSec.toDouble() }?.toFloat() ?: 0f,
        versionCount = d.versionCount,
      )
    }
    val candidates = eventSelection?.candidates.orEmpty().take(8).map { c ->
      ChatPlannerAgent.CandidateSummary(
        eventId = c.eventId,
        title = storyTitle(c),
        assetCount = c.assets.size,
        videoCount = c.realVideoCount,
        score = c.valueScore,
        status = c.status.name.lowercase(Locale.ROOT),
        summary = c.scoutSummary.ifBlank { c.reasons.joinToString("；") },
      )
    }
    return ChatPlannerAgent.ChatContext(
      modelReady = preferredModel != null,
      albumCount = albumAssets.size,
      pipelineRunning = primaryPipelineRunning,
      activeEventId = activeId,
      videos = videos,
      candidates = candidates,
    )
  }

  fun replyFromAgent(text: String) {
    chatViewModel.appendAgentMessage(
      com.vlogcopilot.chat.ChatRole.AGENT_STATUS,
      text,
    )
  }

  fun executeChatPlan(
    plan: ChatPlannerAgent.Plan,
    originalText: String,
    currentEventId: String?,
    model: com.google.ai.edge.gallery.data.Model?,
  ) {
    if (plan.reply.isNotBlank()) replyFromAgent(plan.reply)
    when (plan.action) {
      ChatPlannerAgent.Action.ANSWER,
      ChatPlannerAgent.Action.SUGGEST_EDITS,
      ChatPlannerAgent.Action.CLARIFY -> Unit

      ChatPlannerAgent.Action.OPEN_MODELS -> onOpenModelManager()

      ChatPlannerAgent.Action.OPEN_ALBUM -> requireAlbumPermission {
        viewModel.loadAlbumAssets()
        onTabChange(VlogCopilotTab.Assets)
      }

      ChatPlannerAgent.Action.SCAN_ALBUM -> {
        if (model == null) {
          replyFromAgent("还没有可用模型。先导入本地 Gemma 模型，我才能扫描相册并推荐故事。")
        } else {
          requireAlbumPermission { viewModel.refreshCandidates(model) }
        }
      }

      ChatPlannerAgent.Action.MAKE_CANDIDATE -> {
        val target = plan.targetEventId
          ?.let { id -> eventSelection?.candidates?.firstOrNull { it.eventId == id } }
          ?: matchCandidateByTitle(originalText, eventSelection)
        when {
          target == null -> replyFromAgent("我需要先知道你想做哪一个候选故事。你可以点一个候选，或者说“做餐桌上的小日子”。")
          model == null -> replyFromAgent("还没有可用模型。先导入本地 Gemma 模型，我才能开始制作。")
          else -> {
            chatFocusEventId = target.eventId
            chatViewModel.bindActiveEvent(target.eventId)
            requireAlbumPermission { viewModel.runOnlyEvent(target.eventId, model) }
          }
        }
      }

      ChatPlannerAgent.Action.ITERATE -> {
        val targetEventId = plan.targetEventId
          ?: currentEventId
          ?: chatFocusEventId
          ?: decisions.firstOrNull()?.eventId
        val decision = targetEventId?.let { id -> decisions.firstOrNull { it.eventId == id } }
        when {
          targetEventId == null || decision == null -> {
            replyFromAgent("现在还没有可修改的成片。你可以先让我扫描相册做一条，成片后我再继续改。")
          }
          model == null -> {
            replyFromAgent("还没有可用模型。先导入本地 Gemma 模型，我才能理解你的修改要求。")
          }
          else -> {
            chatFocusEventId = targetEventId
            chatViewModel.bindActiveEvent(targetEventId)
            val feedback = buildIterationFromText(
              text = plan.feedbackText.ifBlank { originalText },
              eventId = targetEventId,
              baseVersion = decision.versionCount.coerceAtLeast(1),
            )
            viewModel.submitFeedback(targetEventId, feedback)
          }
        }
      }
    }
  }

  fun describeChatPlan(plan: ChatPlannerAgent.Plan): String = when (plan.action) {
    ChatPlannerAgent.Action.ITERATE ->
      plan.feedbackText.ifBlank { plan.reply }.ifBlank { "作为成片修改要求" }
    ChatPlannerAgent.Action.MAKE_CANDIDATE ->
      "制作候选故事${plan.targetEventId?.let { " $it" } ?: ""}"
    ChatPlannerAgent.Action.SCAN_ALBUM -> "扫描相册并推荐候选故事"
    ChatPlannerAgent.Action.OPEN_ALBUM -> "打开相册素材"
    ChatPlannerAgent.Action.OPEN_MODELS -> "打开模型管理"
    ChatPlannerAgent.Action.SUGGEST_EDITS -> "给当前成片提出可修改方向"
    ChatPlannerAgent.Action.CLARIFY -> plan.reply
    ChatPlannerAgent.Action.ANSWER -> plan.reply
  }.take(120)

  fun isMutatingAction(action: ChatPlannerAgent.Action): Boolean =
    action == ChatPlannerAgent.Action.SCAN_ALBUM ||
      action == ChatPlannerAgent.Action.MAKE_CANDIDATE ||
      action == ChatPlannerAgent.Action.ITERATE

  fun queueOrExecuteChatPlan(
    plan: ChatPlannerAgent.Plan,
    originalText: String,
    currentEventId: String?,
    model: com.google.ai.edge.gallery.data.Model,
  ) {
    if (primaryPipelineRunning && isMutatingAction(plan.action)) {
      pendingPostRunPlan = PendingChatPlan(plan, originalText, currentEventId, model)
      replyFromAgent("已加入当前任务后的下一步：${describeChatPlan(plan)}。当前制作继续跑，不会被这条指令打断。")
    } else {
      executeChatPlan(plan, originalText, currentEventId, model)
    }
  }

  LaunchedEffect(primaryPipelineRunning, pendingPostRunPlan, decisions.size) {
    val pending = pendingPostRunPlan ?: return@LaunchedEffect
    if (primaryPipelineRunning) return@LaunchedEffect
    if (pending.plan.action == ChatPlannerAgent.Action.ITERATE) {
      val targetEventId = pending.plan.targetEventId
        ?: pending.currentEventId
        ?: chatFocusEventId
        ?: decisions.firstOrNull()?.eventId
      if (targetEventId == null || decisions.none { it.eventId == targetEventId }) {
        return@LaunchedEffect
      }
    }
    pendingPostRunPlan = null
    replyFromAgent("当前任务结束，开始执行刚才排队的指令：${describeChatPlan(pending.plan)}。")
    executeChatPlan(pending.plan, pending.originalText, pending.currentEventId, pending.model)
  }

  // Full-screen curator overlay — replaces the tabbed body when active. The
  // outer modifier carries the parent Scaffold's top inset, and we add bottomPadding
  // here so its bottomBar (the "开始制作" CTA) isn't covered by the parent's tab nav.
  // Without this, users only saw the "已选 N 张" footer and the tap target was unreachable.
  if (curatorOpen) {
    Box(modifier = modifier.padding(bottom = bottomPadding)) {
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
          val requestId = viewModel.submitCuratedRequest(selectedIds, intentText, model)
          val eventId = "user_$requestId"
          chatFocusEventId = eventId
          chatViewModel.newConversation(
            initialTitle = intentText.ifBlank { "手动创作" }.take(24),
            eventId = eventId,
          )
          curatorOpen = false
          onTabChange(VlogCopilotTab.Chat)
        } else {
          // Keep curator open so the user doesn't lose their selection + intent text.
          // Banner explains what they need to do next.
          curatorError = "没有找到已导入的 LLM。请先在 Models 中导入一个支持图像的本地模型（例如 Gemma 4 E2B-IT）。"
        }
      },
      )
    }
    return
  }

  // Album state lives outside the LazyColumn so the asset detail dialog
  // can render at the screen level (Dialog isn't valid inside LazyListScope).
  val albumState = com.vlogcopilot.rememberAssetLibraryUiState()

  // Vlog detail navigation: full-screen page replaces the tabbed view when
  // a row is tapped in 作品库. Hardware back arrow returns to the list.
  // Box wrapper carries the parent Scaffold's top + bottom insets so the
  // detail page content doesn't run under the system status area or bottom tab nav.
  detailEventId?.let { id ->
    Box(modifier = modifier.padding(bottom = bottomPadding)) {
    com.vlogcopilot.VlogDetailScreen(
      decisions = decisions,
      projects = projects,
      eventId = id,
      onBack = { detailEventId = null },
      onOpenIterationSheet = { eventId, shotOrder ->
        iterationSheetEventId = eventId
        iterationSheetTargetOrder = shotOrder
      },
      onChangeStory = {
        chatFocusEventId = id
        chatViewModel.bindActiveEvent(id)
        detailEventId = null
        onTabChange(VlogCopilotTab.Chat)
      },
    )
    }
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
          chatFocusEventId = eventId
          chatViewModel.bindActiveEvent(eventId)
          viewModel.submitFeedback(eventId, feedback)
          detailEventId = null
          iterationSheetEventId = null
          iterationSheetTargetOrder = null
          onTabChange(VlogCopilotTab.Chat)
        },
      )
    }
    return
  }

  // Chat tab needs full-screen vertical space so its input bar can pin to the
  // bottom via Column weight(1f). LazyColumn items get unbounded vertical
  // constraints, which breaks weight — so we bypass the LazyColumn here.
  // ChatScreen owns IME avoidance so it can also compact the workbench while the keyboard is open.
  if (selectedTab == VlogCopilotTab.Chat) {
    val chatImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Box(
      modifier = modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp)
        .padding(bottom = if (chatImeVisible) 0.dp else bottomPadding)
        .padding(top = 12.dp),
    ) {
      com.vlogcopilot.ui.ChatScreen(
        viewModel = chatViewModel,
        decisions = decisions,
        eventSelection = eventSelection,
        focusedCandidate = chatFocusedCandidate,
        progress = progress,
        pipelineRunning = primaryPipelineRunning,
        hasModel = preferredModel != null,
        modelName = preferredModelName,
        albumCount = albumAssets.size,
        albumLoading = albumLoading,
        albumPreviewAssets = albumAssets.take(12),
        supportsAudioInput = preferredModel?.let { AgentRuntime.supportsNativeAudioInput(it) } == true,
        pendingPrefill = chatPrefill,
        autoSendPrefill = chatAutoSend,
        onPrefillConsumed = {
          chatPrefill = null
          chatAutoSend = false
        },
        onOpenResult = { eventId -> detailEventId = eventId },
        onFocusEventChange = { chatFocusEventId = it },
        onSend = onSend@ { text, currentEventId ->
          val normalized = text.trim()
          if (normalized.isBlank()) return@onSend
          val model = preferredModel
          val plannerContext = buildChatPlannerContext(currentEventId)
          if (model == null) {
            executeChatPlan(
              plan = ChatPlannerAgent.Plan(
                action = ChatPlannerAgent.Action.OPEN_MODELS,
                reply = "我需要先有一个本地多模态模型，才能接管剪辑判断。先导入 Gemma 模型，回来我就能看素材、给建议并执行修改。",
              ),
              originalText = normalized,
              currentEventId = currentEventId,
              model = null,
            )
            return@onSend
          }
          replyFromAgent("我先看一下当前素材、候选故事和成片状态。")
          chatAgentScope.launch {
            val plan = try {
              withContext(Dispatchers.IO) {
                val runtime = AgentRuntime(context.applicationContext, model)
                try {
                  runtime.ensureInitialized()
                  ChatPlannerAgent(runtime).plan(normalized, plannerContext)
                } finally {
                  runtime.close()
                }
              }
            } catch (t: Throwable) {
              ChatPlannerAgent.Plan(
                action = ChatPlannerAgent.Action.ANSWER,
                reply = "模型这次没有稳定返回规划结果：${t.message ?: t::class.java.simpleName}。你可以继续说要改节奏、镜头、字幕、调色，或者让我重新扫描相册。",
              )
            }
            queueOrExecuteChatPlan(plan, normalized, currentEventId, model)
          }
        },
        onSendAudio = onSendAudio@ { audioWav, currentEventId ->
          val model = preferredModel
          val plannerContext = buildChatPlannerContext(currentEventId)
          if (model == null) {
            executeChatPlan(
              plan = ChatPlannerAgent.Plan(
                action = ChatPlannerAgent.Action.OPEN_MODELS,
                reply = "我需要先有一个本地多模态模型，才能直接理解语音剪辑指令。",
              ),
              originalText = "语音指令",
              currentEventId = currentEventId,
              model = null,
            )
            return@onSendAudio
          }
          if (!AgentRuntime.supportsNativeAudioInput(model)) {
            replyFromAgent("这个模型没有登记出音频能力。我会用文字输入继续工作，或者你可以换一个支持音频的模型。")
            return@onSendAudio
          }
          replyFromAgent(
            if (primaryPipelineRunning) {
              "收到语音了，我先解析成后续剪辑意图；当前制作不会被打断。"
            } else {
              "收到语音了，正在让模型理解这段剪辑指令。"
            },
          )
          chatAgentScope.launch {
            val plan = try {
              withContext(Dispatchers.IO) {
                val runtime = AgentRuntime(context.applicationContext, model, supportAudio = AgentRuntime.supportsNativeAudioInput(model))
                try {
                  runtime.ensureInitialized()
                  ChatPlannerAgent(runtime).planAudio(audioWav, plannerContext)
                } finally {
                  runtime.close()
                }
              }
            } catch (t: Throwable) {
              ChatPlannerAgent.Plan(
                action = ChatPlannerAgent.Action.CLARIFY,
                reply = "模型这次没有稳定理解这段语音：${t.message ?: t::class.java.simpleName}。你可以再说一遍，或直接输入文字要求。",
              )
            }
            queueOrExecuteChatPlan(plan, "语音指令", currentEventId, model)
          }
        },
        onRescan = {
          requireAlbumPermission {
            selectedModelOrReport()?.let { viewModel.refreshCandidates(it) }
          }
        },
        onOpenModels = onOpenModelManager,
        onOpenAlbum = {
          requireAlbumPermission {
            viewModel.loadAlbumAssets()
            onTabChange(VlogCopilotTab.Assets)
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
    // Dialogs render after this if/else flow — they apply to all tabs.
  } else LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp)
      .padding(bottom = bottomPadding),
    contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    when (selectedTab) {
      VlogCopilotTab.Chat -> Unit  // Rendered above; unreachable here.
      VlogCopilotTab.Works -> {
        // When the AI is working, surface a small tappable hint that links
        // to Chat (which is the source of truth for live progress). Showing
        // the full StoryProgressCard here duplicated the Chat-tab agent_tool
        // cards and made the Works tab feel like "process" instead of
        // "finished works".
        if (primaryPipelineRunning) {
          item {
            PipelineRunningHint(
              progress = progress,
              onTap = {
                chatFocusEventId = chatFocusEventId
                  ?: runConfig.onlySelectedEventIds.firstOrNull()
                  ?: eventSelection?.selectedEventIds?.firstOrNull()
                chatFocusEventId?.let { chatViewModel.bindActiveEvent(it) }
                onTabChange(VlogCopilotTab.Chat)
              },
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
            activeEventIds = if (primaryPipelineRunning) {
              runConfig.onlySelectedEventIds + eventSelection?.selectedEventIds.orEmpty()
            } else {
              emptySet()
            },
            onOpenVlog = { eventId -> detailEventId = eventId },
            onMakeCandidate = { snapshot ->
              chatFocusEventId = snapshot.eventId
              chatViewModel.newConversation(initialTitle = storyTitle(snapshot), eventId = snapshot.eventId)
              chatPrefill = null
              chatAutoSend = false
              onTabChange(VlogCopilotTab.Chat)
            },
          )
        }
        if (decisions.isEmpty() && (eventSelection?.candidates.isNullOrEmpty()) && !primaryPipelineRunning) {
          item {
            EmptyActionCard(
              state = state,
              title = "还没有作品",
              message = "去创作工作台扫描相册、浏览素材，或手动选择素材开始。",
              actionLabel = "去创作工作台",
              onAction = {
                // Clearing here lets the user tap-through the error variant
                // back to a clean state. The error itself is still in the
                // chat as an AGENT_STATUS message — no information lost.
                viewModel.clearError()
                onTabChange(VlogCopilotTab.Chat)
              },
            )
          }
        }
      }

      VlogCopilotTab.Assets -> {
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

      VlogCopilotTab.Settings -> {
        item {
          SettingsCard(
            runConfig = runConfig,
            running = primaryPipelineRunning,
            onIntentSelect = viewModel::setIntent,
            onPowerSelect = viewModel::setPowerProfile,
            onOpenGallery = onOpenGallery,
            onOpenModelManager = onOpenModelManager,
          )
        }
        item { PromptDebugCard() }
      }
    }
  }

  com.vlogcopilot.AssetLibraryDialog(
    state = albumState,
    assets = albumAssets,
    usageByAssetId = assetUsage,
    decisions = decisions,
    manifest = eventSelection,
    onAnnotateAsset = { asset ->
      if (modelManagerViewModel.getAllDownloadedModels().isEmpty()) {
        Toast.makeText(context, "先导入本地模型再标注素材", Toast.LENGTH_SHORT).show()
        onOpenModelManager()
        false
      } else {
        viewModel.indexAssets(assetIds = listOf(asset.id), replaceExisting = true)
        Toast.makeText(context, "已加入后台标注：${asset.displayName.ifBlank { "素材" }}", Toast.LENGTH_SHORT).show()
        true
      }
    },
    onOpenStory = { eventId ->
      selectedStoryId = eventId
      onTabChange(VlogCopilotTab.Works)
    },
    onOpenVideo = { eventId ->
      detailEventId = eventId
      onTabChange(VlogCopilotTab.Works)
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
          chatFocusEventId = it
          chatViewModel.newConversation(initialTitle = storyTitle(candidate), eventId = it)
          makeStory(it)
          selectedStoryId = null
          onTabChange(VlogCopilotTab.Chat)
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
          chatFocusEventId = eid
          chatViewModel.bindActiveEvent(eid)
          viewModel.submitFeedback(eid, feedback)
          iterationSheetEventId = null
          iterationSheetTargetOrder = null
          onTabChange(VlogCopilotTab.Chat)
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

// All keywords are lowercase. Matching lowercases the input text first, so
// "VLOG"/"Vlog"/"vlog"/"Make"/"MAKE" all hit the same entry.
private val GENERATION_KEYWORDS = listOf(
  // 中文 — 命令式
  "做一", "帮我做", "做条", "做一条", "做个", "做一个", "做下",
  "整一个", "整一条", "整一下", "整一段", "弄一个", "弄一段",
  "剪一", "帮我剪", "来一段", "来一条", "出一段", "出一条",
  "生成", "做点", "搞一个", "搞一段",
  // 中文 — 流程
  "推荐", "扫一下", "扫相册", "重扫", "重新扫", "看一下相册",
  "看看相册", "刷新候选", "看下我的相册",
  // 英文 / 短词
  "vlog", "scan", "make a", "create a",
)

private val ITERATION_FASTER_KEYWORDS = listOf("快一点", "再快", "加快", "更快", "动感", "快点")
private val ITERATION_SLOWER_KEYWORDS = listOf("慢一点", "再慢", "慢点", "留白", "悠长", "慢一些")
private val ITERATION_REMOVE_CAPTIONS_KEYWORDS = listOf("去字幕", "无字幕", "不要字幕", "去掉字幕", "字幕去掉")
private val ITERATION_RECOLOR_KEYWORDS = listOf("换调色", "换色调", "换个色", "换色", "重调色", "调色")
private val ITERATION_VERBS = listOf("改", "修改", "调整", "再")

internal fun isGenerationCommand(text: String): Boolean =
  text.lowercase().let { lowered -> GENERATION_KEYWORDS.any { it in lowered } }

/** If the user's text contains a candidate's title (or its core date / type
 *  tokens), return that candidate. Powers the chat handoff: tap a candidate
 *  in Works → "帮我做「TITLE」" auto-sends → router catches it here →
 *  triggers that specific candidate, not a fresh scan. */
internal fun matchCandidateByTitle(
  text: String,
  selection: com.vlogcopilot.worker.EventSelectionManifest?,
): com.vlogcopilot.worker.EventCandidateSnapshot? {
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
): com.vlogcopilot.schemas.IterationFeedback {
  val actions = buildList {
    if (ITERATION_FASTER_KEYWORDS.any { it in text }) {
      add(com.vlogcopilot.schemas.QuickAction.FASTER_OVERALL)
    }
    if (ITERATION_SLOWER_KEYWORDS.any { it in text }) {
      add(com.vlogcopilot.schemas.QuickAction.SLOWER_OVERALL)
    }
    if (ITERATION_REMOVE_CAPTIONS_KEYWORDS.any { it in text }) {
      add(com.vlogcopilot.schemas.QuickAction.REMOVE_CAPTIONS)
    }
    if (ITERATION_RECOLOR_KEYWORDS.any { it in text }) {
      add(com.vlogcopilot.schemas.QuickAction.CHANGE_COLOR_GRADE)
    }
  }
  return com.vlogcopilot.pipeline.IterationPlanner.fromQuickActions(
    iterationId = "iter_%03d".format(baseVersion + 1),
    baseTimelineVersion = baseVersion,
    actions = actions,
    targetedShotOrders = emptyList(),
    userText = text,
    currentColorGrade = com.vlogcopilot.schemas.ColorGrade.NEUTRAL,
    currentPace = com.vlogcopilot.schemas.Pace.BALANCED,
  )
}

