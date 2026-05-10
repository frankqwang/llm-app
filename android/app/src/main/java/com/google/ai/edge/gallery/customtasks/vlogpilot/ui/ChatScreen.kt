/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Claude-style chat surface — message list (USER bubbles right, agent
 * messages left) + input bar at the bottom + conversation header at the
 * top with multi-session switcher. Wired to [ChatViewModel] which persists
 * to ChatStore and bridges PipelineEventBus into the message stream so the
 * AI's work shows up live as it happens.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.data.MAX_AUDIO_CLIP_DURATION_SEC
import com.google.ai.edge.gallery.data.SAMPLE_RATE
import com.google.ai.edge.gallery.customtasks.vlogpilot.AssetThumb
import com.google.ai.edge.gallery.customtasks.vlogpilot.ProgressSnapshot
import com.google.ai.edge.gallery.customtasks.vlogpilot.compactStoryMeta
import com.google.ai.edge.gallery.customtasks.vlogpilot.friendlyProgress
import com.google.ai.edge.gallery.customtasks.vlogpilot.storyReason
import com.google.ai.edge.gallery.customtasks.vlogpilot.storyTitle
import com.google.ai.edge.gallery.customtasks.vlogpilot.chat.ChatConversation
import com.google.ai.edge.gallery.customtasks.vlogpilot.chat.ChatMessage
import com.google.ai.edge.gallery.customtasks.vlogpilot.chat.ChatRole
import com.google.ai.edge.gallery.customtasks.vlogpilot.chat.ChatViewModel
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventCandidateSnapshot
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionManifest
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Phase 3 entry point — replaces the placeholder. Pulls all state from a
 *  ChatViewModel created lazily per VM lifetime; the parent owns this VM
 *  if it wants to share it across recompositions. For now we instantiate
 *  inline because the chat is fully self-contained. */
@Composable
internal fun ChatScreen(
  viewModel: ChatViewModel,
  holdToDictateViewModel: HoldToDictateViewModel = hiltViewModel(),
  decisions: List<EventDecisions>,
  eventSelection: EventSelectionManifest?,
  focusedCandidate: EventCandidateSnapshot? = null,
  progress: ProgressSnapshot = ProgressSnapshot(),
  pipelineRunning: Boolean = false,
  hasModel: Boolean = false,
  modelName: String? = null,
  albumCount: Int = 0,
  albumLoading: Boolean = false,
  albumPreviewAssets: List<Asset> = emptyList(),
  supportsAudioInput: Boolean = false,
  onSend: (text: String, currentEventId: String?) -> Unit,
  onSendAudio: (audioWav: ByteArray, currentEventId: String?) -> Unit = { _, _ -> },
  onOpenResult: (eventId: String) -> Unit = {},
  onFocusEventChange: (String?) -> Unit = {},
  pendingPrefill: String? = null,
  autoSendPrefill: Boolean = false,
  onPrefillConsumed: () -> Unit = {},
  // Phase 1 fallback callbacks — kept so the empty-state buttons still work.
  onRescan: () -> Unit = {},
  onOpenModels: () -> Unit = {},
  onOpenAlbum: () -> Unit = {},
  onOpenCurator: () -> Unit = {},
) {

  val tokens = VlogPilotTokens
  val conversations by viewModel.conversations.collectAsStateSafe()
  val activeConvoId by viewModel.activeConversationId.collectAsStateSafe()
  val messages by viewModel.messages.collectAsStateSafe()
  val voiceState by holdToDictateViewModel.uiState.collectAsStateSafe()
  val visibleMessages = remember(messages) { collapseChatTimeline(messages) }
  val activeConvo = conversations.firstOrNull { it.id == activeConvoId }
  val hasWorkContext = focusedCandidate != null || pipelineRunning
  val context = LocalContext.current
  val density = LocalDensity.current
  val imeVisible = WindowInsets.ime.getBottom(density) > 0
  val listState = rememberLazyListState()

  var showHistory by remember { mutableStateOf(false) }
  var inputText by remember { mutableStateOf("") }
  var workbenchExpanded by remember(activeConvoId) { mutableStateOf(true) }
  var workContextExpanded by remember(activeConvoId, focusedCandidate?.eventId, pipelineRunning) {
    mutableStateOf(!pipelineRunning && visibleMessages.isEmpty())
  }

  LaunchedEffect(voiceState.errorMessage) {
    val message = voiceState.errorMessage
    if (message.isNotBlank()) {
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
      holdToDictateViewModel.clearError()
    }
  }

  LaunchedEffect(activeConvo?.eventId) {
    onFocusEventChange(activeConvo?.eventId)
  }

  LaunchedEffect(activeConvoId, visibleMessages.isEmpty(), hasWorkContext) {
    if (!hasWorkContext && visibleMessages.isEmpty()) {
      workbenchExpanded = true
    }
    if (hasWorkContext && visibleMessages.isNotEmpty()) {
      workContextExpanded = false
    }
  }

  LaunchedEffect(listState, visibleMessages.size, hasWorkContext) {
    if (!hasWorkContext && visibleMessages.isNotEmpty()) {
      snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
        .collect { (index, offset) ->
          if (index > 0 || offset > 24) {
            workbenchExpanded = false
          }
        }
    }
  }

  // Consume any pending prefill from the Works tab handoff. When
  // autoSendPrefill is true (e.g. the user tapped a candidate row), we
  // submit immediately so they don't have to tap send again.
  LaunchedEffect(pendingPrefill, autoSendPrefill) {
    if (!pendingPrefill.isNullOrBlank()) {
      if (autoSendPrefill) {
        viewModel.appendUserMessage(pendingPrefill)
        onSend(pendingPrefill, activeConvo?.eventId ?: focusedCandidate?.eventId)
      } else {
        inputText = pendingPrefill
      }
      onPrefillConsumed()
    }
  }

  // fillMaxSize + weight(1f) on the messages section → input bar always
  // pinned to the bottom of the available area. The previous heightIn(600dp)
  // floor caused the input bar to float in the middle of taller screens
  // because the chat lived inside a LazyColumn item.
  Column(modifier = Modifier.fillMaxSize().imePadding()) {
    // ── Header: title + rescan + history + new conversation ───────────────
    if (hasWorkContext) {
      ChatWorkContextCard(
        candidate = focusedCandidate,
        progress = progress,
        running = pipelineRunning,
        expanded = workContextExpanded,
        onToggleExpanded = { workContextExpanded = !workContextExpanded },
        onShowHistory = { showHistory = true },
        onStart = { candidate ->
          val command = "帮我做「${storyTitle(candidate)}」这个候选故事"
          viewModel.bindActiveEvent(candidate.eventId)
          viewModel.appendUserMessage(command)
          onSend(command, candidate.eventId)
        },
      )
      Spacer(Modifier.height(8.dp))
    } else {
      CreationWorkbenchV2(
        hasModel = hasModel,
        modelName = modelName,
        albumCount = albumCount,
        albumLoading = albumLoading,
        albumPreviewAssets = albumPreviewAssets,
        candidateCount = eventSelection?.candidates?.size ?: 0,
        decisionsCount = decisions.count { it.mp4Path != null },
        compact = imeVisible,
        expanded = workbenchExpanded,
        onToggleExpanded = { workbenchExpanded = !workbenchExpanded },
        onScanAlbum = onRescan,
        onOpenModels = onOpenModels,
        onOpenAlbum = onOpenAlbum,
        onOpenCurator = onOpenCurator,
        onShowHistory = { showHistory = true },
        onNew = {
          onFocusEventChange(null)
          viewModel.newConversation()
        },
      )
      Spacer(Modifier.height(10.dp))
    }

    // ── Messages ────────────────────────────────────────────────────────────
    // Auto-scroll to the latest message ONLY when the user is already near
    // the bottom — Apple Messages / Claude.ai both follow this rule so that
    // reading older content isn't interrupted when new messages stream in.
    val isNearBottom by remember {
      androidx.compose.runtime.derivedStateOf {
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        val total = info.totalItemsCount
        total == 0 || lastVisible >= total - 2
      }
    }
    LaunchedEffect(visibleMessages.size) {
      if (visibleMessages.isNotEmpty() && isNearBottom) {
        listState.animateScrollToItem(visibleMessages.size - 1)
      }
    }
    if (visibleMessages.isEmpty()) {
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (!hasWorkContext) {
          ChatIdleHint()
        } else {
          ChatWorkEmptyHint(running = pipelineRunning)
        }
      }
    } else {
      LazyColumn(
        state = listState,
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        items(visibleMessages, key = { it.id }) { msg -> ChatMessageRow(msg, decisions, onOpenResult) }
      }
    }

    Spacer(Modifier.height(if (imeVisible) 2.dp else 6.dp))

    // ── Suggestion chips (only on fresh conversation) ───────────────────────
    if (visibleMessages.size <= 1 && !hasWorkContext && !imeVisible) {
      ChatSuggestions(
        onTap = { suggestion -> inputText = suggestion },
      )
      Spacer(Modifier.height(12.dp))
    }

    // ── Input bar (always at bottom thanks to weight on the section above) ─
    ChatInputBar(
      value = inputText,
      onValueChange = { inputText = it },
      voiceViewModel = holdToDictateViewModel,
      voiceRecognizing = voiceState.recognizing,
      supportsAudioInput = supportsAudioInput,
      onVoiceText = { text ->
        inputText = mergeVoiceInput(inputText, text)
      },
      onVoiceAudio = { audioWav ->
        viewModel.appendUserAudioMessage(audioWav)
        onSendAudio(audioWav, activeConvo?.eventId ?: focusedCandidate?.eventId)
      },
      onSend = {
        val text = inputText.trim()
        if (text.isNotBlank()) {
          viewModel.appendUserMessage(text)
          onSend(text, activeConvo?.eventId ?: focusedCandidate?.eventId)
          inputText = ""
        }
      },
    )
  }

  if (showHistory) {
    ChatHistorySheet(
      conversations = conversations,
      activeId = activeConvoId,
      onSelect = {
        onFocusEventChange(conversations.firstOrNull { convo -> convo.id == it }?.eventId)
        viewModel.switchTo(it)
        showHistory = false
      },
      onDelete = { viewModel.deleteConversation(it) },
      onDismiss = { showHistory = false },
    )
  }
}

private fun collapseChatTimeline(messages: List<ChatMessage>): List<ChatMessage> {
  if (messages.isEmpty()) return emptyList()
  val collapsed = mutableListOf<ChatMessage>()
  messages.forEach { msg ->
    val last = collapsed.lastOrNull()
    val sameAgentStep = last != null &&
      msg.role == ChatRole.AGENT_TOOL &&
      last.role == ChatRole.AGENT_TOOL &&
      msg.eventId == last.eventId &&
      msg.agentStage == last.agentStage
    val sameStatus = last != null &&
      msg.role == ChatRole.AGENT_STATUS &&
      last.role == ChatRole.AGENT_STATUS &&
      msg.text == last.text
    if (sameAgentStep || sameStatus) {
      collapsed[collapsed.lastIndex] = msg
    } else {
      collapsed += msg
    }
  }
  return collapsed
}

private fun mergeVoiceInput(current: String, recognized: String): String {
  val text = recognized.trim()
  if (text.isBlank()) return current
  val base = current.trim()
  return if (base.isBlank()) text else "$base $text"
}

@Composable
private fun ChatHeader(
  conversation: ChatConversation?,
  onNew: () -> Unit,
  onShowHistory: () -> Unit,
  onRescan: () -> Unit,
) {
  val tokens = VlogPilotTokens
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 4.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box(
      modifier = Modifier
        .size(34.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(tokens.colors.accentTint),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        Icons.AutoMirrored.Outlined.Chat,
        contentDescription = null,
        tint = tokens.colors.accent,
        modifier = Modifier.size(19.dp),
      )
    }
    Spacer(Modifier.size(8.dp))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      Text(
        conversation?.title ?: "VlogPilot 对话",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      conversation?.let {
        Text(
          SimpleDateFormat("M月d日 HH:mm", Locale.US).format(Date(it.createdAtMs)),
          style = MaterialTheme.typography.labelSmall,
          color = tokens.colors.tertiaryLabel,
        )
      }
    }
    IconButton(onClick = onRescan) {
      Icon(Icons.Outlined.AutoAwesome, contentDescription = "重扫相册", tint = tokens.colors.accent)
    }
    IconButton(onClick = onShowHistory) {
      Icon(Icons.Outlined.History, contentDescription = "历史对话", tint = tokens.colors.accent)
    }
    IconButton(onClick = onNew) {
      Icon(Icons.Outlined.Edit, contentDescription = "新建对话", tint = tokens.colors.accent)
    }
  }
}

@Composable
private fun CreationWorkbenchV2(
  hasModel: Boolean,
  modelName: String?,
  albumCount: Int,
  albumLoading: Boolean,
  albumPreviewAssets: List<Asset>,
  candidateCount: Int,
  decisionsCount: Int,
  compact: Boolean,
  expanded: Boolean,
  onToggleExpanded: () -> Unit,
  onScanAlbum: () -> Unit,
  onOpenModels: () -> Unit,
  onOpenAlbum: () -> Unit,
  onOpenCurator: () -> Unit,
  onShowHistory: () -> Unit,
  onNew: () -> Unit,
) {
  val tokens = VlogPilotTokens
  val detailsVisible = expanded && !compact
  val modelStatus =
    if (hasModel) {
      "模型已就绪 · ${modelName ?: "本地模型"}"
    } else {
      "未导入模型 · 导入后可生成分镜"
    }
  val albumStatus = when {
    albumLoading -> "相册读取中"
    albumCount > 0 -> "相册 $albumCount 项"
    else -> "相册未扫描"
  }
  val compactStatus = listOf(
    modelStatus,
    albumStatus,
    "候选 $candidateCount",
    "成片 $decisionsCount",
  ).joinToString(" · ")
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(if (compact) 10.dp else 12.dp),
      verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .clickable(onClick = onToggleExpanded)
          .padding(vertical = if (compact) 0.dp else 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .size(if (compact) 32.dp else 36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.colors.accentTint),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            Icons.AutoMirrored.Outlined.Chat,
            contentDescription = null,
            tint = tokens.colors.accent,
            modifier = Modifier.size(if (compact) 18.dp else 20.dp),
          )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            "创作工作台",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
          )
          Text(
            if (detailsVisible) modelStatus else compactStatus,
            style = MaterialTheme.typography.bodyMedium,
            color = if (hasModel) tokens.colors.systemGreen else tokens.colors.systemOrange,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (detailsVisible && (candidateCount > 0 || decisionsCount > 0)) {
            Text(
              "AI 候选 $candidateCount · 已生成 $decisionsCount",
              style = MaterialTheme.typography.labelSmall,
              color = tokens.colors.tertiaryLabel,
              maxLines = 1,
            )
          }
        }
        Icon(
          if (detailsVisible) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
          contentDescription = if (detailsVisible) "收起工作台" else "展开工作台",
          tint = tokens.colors.tertiaryLabel,
          modifier = Modifier.size(18.dp),
        )
        IconButton(onClick = onShowHistory) {
          Icon(Icons.Outlined.History, contentDescription = "历史对话", tint = tokens.colors.accent)
        }
        IconButton(onClick = onNew) {
          Icon(Icons.Outlined.Edit, contentDescription = "新建对话", tint = tokens.colors.accent)
        }
      }

      if (detailsVisible) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TintedActionButton(
            text = if (hasModel) "扫描" else "模型",
            icon = if (hasModel) Icons.Outlined.AutoAwesome else Icons.Outlined.Settings,
            onClick = { if (hasModel) onScanAlbum() else onOpenModels() },
            tint = if (hasModel) tokens.colors.accent else tokens.colors.systemOrange,
            modifier = Modifier.weight(1f),
          )
          TintedActionButton(
            text = "素材",
            icon = Icons.Outlined.PhotoLibrary,
            onClick = onOpenAlbum,
            modifier = Modifier.weight(1f),
          )
          TintedActionButton(
            text = "手选",
            icon = Icons.Outlined.Movie,
            tint = tokens.colors.systemPurple,
            onClick = onOpenCurator,
            modifier = Modifier.weight(1f),
          )
        }
        AlbumPreviewRail(
          assets = albumPreviewAssets.take(12),
          albumCount = albumCount,
          albumLoading = albumLoading,
          onOpenAlbum = onOpenAlbum,
        )
      }
    }
  }
}

@Composable
private fun AlbumPreviewRail(
  assets: List<Asset>,
  albumCount: Int,
  albumLoading: Boolean,
  onOpenAlbum: () -> Unit,
) {
  val tokens = VlogPilotTokens
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        "相册预览",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        when {
          albumLoading -> "读取中"
          albumCount > 0 -> "$albumCount 项"
          else -> "未扫描"
        },
        style = MaterialTheme.typography.labelMedium,
        color = tokens.colors.secondaryLabel,
        modifier = Modifier.weight(1f),
      )
      Text(
        "查看全部",
        style = MaterialTheme.typography.labelMedium,
        color = tokens.colors.accent,
        modifier = Modifier.clickable(onClick = onOpenAlbum).padding(horizontal = 4.dp, vertical = 2.dp),
      )
    }

    if (assets.isNotEmpty()) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        assets.chunked(6).take(2).forEach { rowAssets ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            rowAssets.forEach { asset ->
              AssetThumb(
                asset = asset,
                modifier = Modifier
                  .weight(1f)
                  .height(46.dp),
                showType = false,
              )
            }
            repeat(6 - rowAssets.size) {
              Spacer(modifier = Modifier.weight(1f).height(46.dp))
            }
          }
        }
      }
    } else {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(tokens.colors.groupedSurfaceRaised)
          .clickable(onClick = onOpenAlbum)
          .padding(horizontal = 12.dp, vertical = 12.dp),
      ) {
        Text(
          if (albumLoading) "正在读取相册素材..." else "点这里浏览相册素材",
          style = MaterialTheme.typography.bodyMedium,
          color = tokens.colors.secondaryLabel,
        )
      }
    }
  }
}

@Composable
private fun CreationWorkbench(
  hasModel: Boolean,
  modelName: String?,
  albumCount: Int,
  albumLoading: Boolean,
  candidateCount: Int,
  decisionsCount: Int,
  onScanAlbum: () -> Unit,
  onOpenModels: () -> Unit,
  onOpenAlbum: () -> Unit,
  onOpenCurator: () -> Unit,
  onShowHistory: () -> Unit,
  onNew: () -> Unit,
) {
  val tokens = VlogPilotTokens
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.accentTint),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            Icons.AutoMirrored.Outlined.Chat,
            contentDescription = null,
            tint = tokens.colors.accent,
            modifier = Modifier.size(24.dp),
          )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            "创作工作台",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
          )
          Text(
            if (hasModel) "模型已就绪，可以扫描相册生成候选。" else "先导入模型，也可以先浏览相册素材。",
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.secondaryLabel,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(onClick = onShowHistory) {
          Icon(Icons.Outlined.History, contentDescription = "历史对话", tint = tokens.colors.accent)
        }
        IconButton(onClick = onNew) {
          Icon(Icons.Outlined.Edit, contentDescription = "新建对话", tint = tokens.colors.accent)
        }
      }

      WorkbenchModelStatus(
        hasModel = hasModel,
        modelName = modelName,
      )

      TintedActionButton(
        text = if (hasModel) "扫描相册推荐故事" else "导入本地模型",
        icon = if (hasModel) Icons.Outlined.AutoAwesome else Icons.Outlined.Settings,
        onClick = { if (hasModel) onScanAlbum() else onOpenModels() },
        tint = if (hasModel) tokens.colors.accent else tokens.colors.systemOrange,
        modifier = Modifier.fillMaxWidth(),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        TintedActionButton(
          text = "浏览相册素材",
          icon = Icons.Outlined.PhotoLibrary,
          onClick = onOpenAlbum,
          modifier = Modifier.weight(1f),
        )
        TintedActionButton(
          text = "手动选择素材",
          icon = Icons.Outlined.Movie,
          tint = tokens.colors.systemPurple,
          onClick = onOpenCurator,
          modifier = Modifier.weight(1f),
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ContextMetric(
          "相册素材",
          when {
            albumLoading -> "读取中"
            albumCount > 0 -> albumCount.toString()
            else -> "未扫描"
          },
          Modifier.weight(1f),
        )
        ContextMetric("AI 候选", candidateCount.toString(), Modifier.weight(1f))
        ContextMetric("已生成", decisionsCount.toString(), Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun WorkbenchModelStatus(
  hasModel: Boolean,
  modelName: String?,
) {
  val tokens = VlogPilotTokens
  val tint = if (hasModel) tokens.colors.systemGreen else tokens.colors.systemOrange
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(tint.copy(alpha = if (tokens.colors.isDark) 0.18f else 0.10f))
      .padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = if (hasModel) Icons.Outlined.AutoAwesome else Icons.Outlined.ErrorOutline,
      contentDescription = null,
      tint = tint,
      modifier = Modifier.size(20.dp),
    )
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      Text(
        if (hasModel) "本地模型可用" else "未导入本地模型",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        maxLines = 1,
      )
      Text(
        if (hasModel) (modelName ?: "已导入模型") else "生成分镜和剪辑方案前需要导入 Gemma 模型。",
        style = MaterialTheme.typography.bodySmall,
        color = tokens.colors.secondaryLabel,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun ChatWorkContextCard(
  candidate: EventCandidateSnapshot?,
  progress: ProgressSnapshot,
  running: Boolean,
  expanded: Boolean,
  onToggleExpanded: () -> Unit,
  onShowHistory: () -> Unit,
  onStart: (EventCandidateSnapshot) -> Unit,
) {
  val tokens = VlogPilotTokens
  val friendly = friendlyProgress(progress, making = running)
  val fraction = remember(friendly.current, friendly.total) {
    if (friendly.total > 0) (friendly.current.toFloat() / friendly.total.toFloat()).coerceIn(0f, 1f) else null
  }
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onToggleExpanded),
    shape = RoundedCornerShape(18.dp),
    color = if (running) tokens.colors.accentTint else MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
      ) {
        Box(
          modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
              if (running) tokens.colors.accent.copy(alpha = 0.18f)
              else tokens.colors.systemOrange.copy(alpha = if (tokens.colors.isDark) 0.22f else 0.12f),
            ),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            if (running) Icons.Outlined.Movie else Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            tint = if (running) tokens.colors.accent else tokens.colors.systemOrange,
            modifier = Modifier.size(19.dp),
          )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(
            candidate?.let(::storyTitle) ?: friendly.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            if (candidate != null) compactStoryMeta(candidate) else friendly.detail.ifBlank { "过程会实时显示在这里" },
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (!running && candidate != null) {
          Button(onClick = { onStart(candidate) }) {
            Text("开始制作")
          }
        }
        IconButton(onClick = onShowHistory) {
          Icon(
            Icons.Outlined.History,
            contentDescription = "历史记录",
            tint = tokens.colors.accent,
            modifier = Modifier.size(20.dp),
          )
        }
        Icon(
          if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
          contentDescription = if (expanded) "收起" else "展开",
          tint = tokens.colors.tertiaryLabel,
          modifier = Modifier.size(18.dp),
        )
      }

      if (expanded) {
        candidate?.let {
          CandidateAssetPreview(candidate = it, compact = running)
        }
      }

      if (running) {
        if (fraction != null) {
          LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
              .fillMaxWidth()
              .height(5.dp)
              .clip(RoundedCornerShape(999.dp)),
          )
        }
        Text(
          friendly.title,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = tokens.colors.accent,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          friendly.detail,
          style = MaterialTheme.typography.bodySmall,
          color = tokens.colors.secondaryLabel,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      } else if (expanded && candidate != null) {
        Text(
          storyReason(candidate),
          style = MaterialTheme.typography.bodySmall,
          color = tokens.colors.secondaryLabel,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          ContextMetric("素材", candidate.assets.size.toString(), Modifier.weight(1f))
          ContextMetric("视频", candidate.realVideoCount.toString(), Modifier.weight(1f))
          ContextMetric("适合度", (candidate.valueScore.coerceIn(0f, 1f) * 100).toInt().toString(), Modifier.weight(1f))
        }
      }
    }
  }
}

@Composable
private fun CandidateAssetPreview(candidate: EventCandidateSnapshot, compact: Boolean) {
  val tokens = VlogPilotTokens
  val thumbWidth = if (compact) 44.dp else 58.dp
  val thumbHeight = if (compact) 54.dp else 72.dp
  val shownAssets = candidate.assets.take(if (compact) 7 else 8)
  LazyRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    items(shownAssets, key = { it.id }) { asset ->
      AssetThumb(
        asset = asset,
        modifier = Modifier
          .widthIn(min = thumbWidth, max = thumbWidth)
          .height(thumbHeight),
      )
    }
    if (candidate.event.assetIds.size > shownAssets.size) {
      item {
        Box(
          modifier = Modifier
            .widthIn(min = thumbWidth, max = thumbWidth)
            .height(thumbHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.groupedSurfaceRaised),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            "+${candidate.event.assetIds.size - shownAssets.size}",
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.secondaryLabel,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
    }
  }
}

@Composable
private fun ContextMetric(label: String, value: String, modifier: Modifier = Modifier) {
  val tokens = VlogPilotTokens
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(10.dp),
    color = if (tokens.colors.isDark) tokens.colors.groupedSurfaceRaised else tokens.colors.groupedBackground,
    tonalElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
      verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
      Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = tokens.colors.tertiaryLabel,
        maxLines = 1,
      )
      Text(
        value,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun ChatWorkEmptyHint(running: Boolean) {
  val tokens = VlogPilotTokens
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      if (running) "制作步骤会继续出现在这里。" else "确认素材后，点上方「开始制作」。",
      style = MaterialTheme.typography.bodyMedium,
      color = tokens.colors.tertiaryLabel,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun ChatIdleHint() {
  val tokens = VlogPilotTokens
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      "选择上面的入口开始，或者直接输入你想做的 vlog。",
      style = MaterialTheme.typography.bodyMedium,
      color = tokens.colors.tertiaryLabel,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 24.dp),
    )
  }
}

@Composable
private fun ChatWelcomeBlock(
  decisionsCount: Int,
  candidateCount: Int,
  onRescan: () -> Unit,
  onOpenCurator: () -> Unit,
) {
  val tokens = VlogPilotTokens
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
        modifier = Modifier
          .size(56.dp)
          .clip(RoundedCornerShape(16.dp))
          .background(tokens.colors.accentTint),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.AutoMirrored.Outlined.Chat,
          contentDescription = null,
          tint = tokens.colors.accent,
          modifier = Modifier.size(28.dp),
        )
      }
      Text(
        "你好，我是 VlogPilot",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
      )
      Text(
        "告诉我你想做什么 vlog——我会读你的相册，写分镜，剪一条出来。",
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.secondaryLabel,
        textAlign = TextAlign.Center,
      )
      if (decisionsCount > 0 || candidateCount > 0) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
          ChatStat("AI 推荐", candidateCount.toString())
          ChatStat("已生成", decisionsCount.toString())
        }
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        TintedActionButton(
          text = "重扫相册",
          icon = Icons.Outlined.AutoAwesome,
          onClick = onRescan,
          modifier = Modifier.fillMaxWidth(0.5f),
        )
        TintedActionButton(
          text = "挑素材",
          icon = Icons.Outlined.Movie,
          tint = tokens.colors.systemPurple,
          onClick = onOpenCurator,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
private fun ChatStat(label: String, value: String) {
  val tokens = VlogPilotTokens
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = tokens.colors.accent)
    Text(label, style = MaterialTheme.typography.labelSmall, color = tokens.colors.secondaryLabel)
  }
}

@Composable
private fun ChatSuggestions(onTap: (String) -> Unit) {
  val tokens = VlogPilotTokens
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      "试试这样开始",
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = tokens.colors.secondaryLabel,
      modifier = Modifier.padding(start = 4.dp),
    )
    listOf(
      "💛 帮我做一条这周的家庭日常",
      "🍜 把上周的美食出片，要有食欲感",
      "🌅 把最近的旅行剪一条 30 秒怀旧风的",
    ).forEach { hint ->
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(if (tokens.colors.isDark) tokens.colors.groupedSurfaceRaised else tokens.colors.groupedBackground)
          .clickable { onTap(hint.substringAfter(' ')) }
          .padding(horizontal = 14.dp, vertical = 12.dp),
      ) {
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
      }
    }
  }
}

@Composable
private fun ChatInputBar(
  value: String,
  onValueChange: (String) -> Unit,
  voiceViewModel: HoldToDictateViewModel,
  voiceRecognizing: Boolean,
  supportsAudioInput: Boolean,
  onVoiceText: (String) -> Unit,
  onVoiceAudio: (ByteArray) -> Unit,
  onSend: () -> Unit,
) {
  val tokens = VlogPilotTokens
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier
        .weight(1f)
        .heightIn(min = 40.dp, max = 88.dp)
        .clip(RoundedCornerShape(15.dp))
        .background(MaterialTheme.colorScheme.surface)
        .border(
          width = 1.dp,
          color = if (value.isNotBlank()) tokens.colors.accent else tokens.colors.opaqueSeparator,
          shape = RoundedCornerShape(15.dp),
        )
        .padding(horizontal = 10.dp, vertical = 6.dp),
      textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
      cursorBrush = SolidColor(tokens.colors.accent),
      maxLines = 3,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
      keyboardActions = KeyboardActions(onSend = { onSend() }),
      decorationBox = { innerTextField ->
        Box(contentAlignment = Alignment.CenterStart) {
          if (value.isBlank()) {
            Text(
              "和 AI 聊聊你想做什么...",
              color = tokens.colors.tertiaryLabel,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          innerTextField()
        }
      },
    )
    VoiceDictationButton(
      viewModel = voiceViewModel,
      recognizing = voiceRecognizing,
      supportsAudioInput = supportsAudioInput,
      onText = onVoiceText,
      onAudio = onVoiceAudio,
    )
    val canSend = value.isNotBlank()
    Box(
      modifier = Modifier
        .size(42.dp)
        .clip(CircleShape)
        .background(if (canSend) tokens.colors.accent else tokens.colors.accent.copy(alpha = 0.4f))
        .clickable(enabled = canSend) { onSend() },
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        Icons.AutoMirrored.Outlined.Send,
        contentDescription = "发送",
        tint = Color.White,
        modifier = Modifier.size(19.dp),
      )
    }
  }
}

@SuppressLint("MissingPermission")
@Composable
private fun VoiceDictationButton(
  viewModel: HoldToDictateViewModel,
  recognizing: Boolean,
  supportsAudioInput: Boolean,
  onText: (String) -> Unit,
  onAudio: (ByteArray) -> Unit,
) {
  val context = LocalContext.current
  val tokens = VlogPilotTokens
  val scope = rememberCoroutineScope()
  val recordingFlag = remember { AtomicBoolean(false) }
  var recordingAudio by remember { mutableStateOf(false) }
  var recorder by remember { mutableStateOf<AudioRecord?>(null) }
  var recordAudioPermissionGranted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      recordAudioPermissionGranted = granted
    }

  fun stopDirectAudio() {
    recordingFlag.set(false)
  }

  fun startDirectAudio() {
    if (recordingAudio) {
      stopDirectAudio()
      return
    }
    scope.launch {
      val minBuffer = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
      if (minBuffer <= 0) {
        Toast.makeText(context, "录音初始化失败，先用文字输入。", Toast.LENGTH_SHORT).show()
        return@launch
      }
      val audioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBuffer * 2,
      )
      if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
        audioRecord.release()
        Toast.makeText(context, "录音设备不可用，先用文字输入。", Toast.LENGTH_SHORT).show()
        return@launch
      }

      recorder = audioRecord
      recordingFlag.set(true)
      recordingAudio = true
      val pcm = try {
        withContext(Dispatchers.IO) {
          val stream = ByteArrayOutputStream()
          val buffer = ByteArray(minBuffer)
          val maxBytes = SAMPLE_RATE * 2 * MAX_AUDIO_CLIP_DURATION_SEC
          audioRecord.startRecording()
          while (recordingFlag.get() && stream.size() < maxBytes) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0) stream.write(buffer, 0, read)
          }
          stream.toByteArray()
        }
      } catch (t: Throwable) {
        Toast.makeText(context, "录音失败：${t.message ?: t::class.java.simpleName}", Toast.LENGTH_SHORT).show()
        ByteArray(0)
      } finally {
        runCatching { audioRecord.stop() }
        audioRecord.release()
        recorder = null
        recordingFlag.set(false)
        recordingAudio = false
      }

      if (pcm.isNotEmpty()) {
        onAudio(pcm16MonoToWav(pcm, SAMPLE_RATE))
      }
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      recordingFlag.set(false)
      runCatching { recorder?.stop() }
      runCatching { recorder?.release() }
      recorder = null
    }
  }

  val active = recognizing || recordingAudio
  val tint = if (active) tokens.colors.systemRed else tokens.colors.accent
  Box(
    modifier = Modifier
      .size(42.dp)
      .clip(CircleShape)
      .background(tint.copy(alpha = if (tokens.colors.isDark) 0.20f else 0.12f))
      .clickable {
        if (!recordAudioPermissionGranted) {
          permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
          return@clickable
        }
        if (supportsAudioInput) {
          startDirectAudio()
        } else if (recognizing) {
          viewModel.stopSpeechRecognition()
        } else {
          viewModel.startSpeechRecognition(
            onDone = { text -> onText(text) },
            onAmplitudeChanged = {},
          )
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      Icons.Outlined.Mic,
      contentDescription = when {
        recordingAudio -> "停止录音"
        recognizing -> "停止听写"
        supportsAudioInput -> "录一段语音指令"
        else -> "语音转文字"
      },
      tint = tint,
      modifier = Modifier.size(19.dp),
    )
  }
}

private fun pcm16MonoToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
  val out = ByteArrayOutputStream(44 + pcm.size)
  fun writeAscii(value: String) {
    value.forEach { out.write(it.code) }
  }
  fun writeIntLe(value: Int) {
    out.write(value and 0xff)
    out.write((value shr 8) and 0xff)
    out.write((value shr 16) and 0xff)
    out.write((value shr 24) and 0xff)
  }
  fun writeShortLe(value: Int) {
    out.write(value and 0xff)
    out.write((value shr 8) and 0xff)
  }

  val channels = 1
  val bitsPerSample = 16
  val byteRate = sampleRate * channels * bitsPerSample / 8
  writeAscii("RIFF")
  writeIntLe(36 + pcm.size)
  writeAscii("WAVE")
  writeAscii("fmt ")
  writeIntLe(16)
  writeShortLe(1)
  writeShortLe(channels)
  writeIntLe(sampleRate)
  writeIntLe(byteRate)
  writeShortLe(channels * bitsPerSample / 8)
  writeShortLe(bitsPerSample)
  writeAscii("data")
  writeIntLe(pcm.size)
  out.write(pcm)
  return out.toByteArray()
}

@Composable
private fun ChatMessageRow(
  msg: ChatMessage,
  decisions: List<EventDecisions>,
  onOpenResult: (String) -> Unit,
) {
  when (msg.role) {
    ChatRole.USER -> UserBubble(msg)
    ChatRole.AGENT_STATUS -> AgentStatusLine(msg.text)
    ChatRole.SYSTEM -> SystemLine(msg.text)
    ChatRole.AGENT_TOOL -> {
      // Look up the matching EventDecisions so we can show the agent's
      // actual output when the user expands the card. Decision may not
      // exist yet (agent is still running) — card stays in loading state.
      val decision = msg.eventId?.let { id -> decisions.firstOrNull { it.eventId == id } }
      AgentToolCard(label = msg.text, stage = msg.agentStage, decision = decision)
    }
    ChatRole.RESULT -> ResultCard(msg, decisions, onOpenResult)
    ChatRole.LOADING -> LoadingDots()
  }
}

@Composable
private fun UserBubble(msg: ChatMessage) {
  if (msg.audioPath != null) {
    AudioUserBubble(msg)
    return
  }
  val tokens = VlogPilotTokens
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.End,
  ) {
    Box(
      modifier = Modifier
        .widthIn(max = 280.dp)
        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
        .background(tokens.colors.accent)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
      Text(msg.text, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
  }
}

@Composable
private fun AudioUserBubble(msg: ChatMessage) {
  val tokens = VlogPilotTokens
  var playing by remember(msg.id) { mutableStateOf(false) }
  var player by remember(msg.id) { mutableStateOf<MediaPlayer?>(null) }

  fun stop() {
    runCatching { player?.stop() }
    runCatching { player?.release() }
    player = null
    playing = false
  }

  DisposableEffect(msg.id) {
    onDispose { stop() }
  }

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.End,
  ) {
    Row(
      modifier = Modifier
        .widthIn(min = 148.dp, max = 260.dp)
        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
        .background(tokens.colors.accent)
        .clickable {
          val path = msg.audioPath ?: return@clickable
          if (playing) {
            stop()
          } else {
            runCatching {
              val mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stop() }
                prepare()
                start()
              }
              player = mediaPlayer
              playing = true
            }.onFailure { stop() }
          }
        }
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = if (playing) Icons.Outlined.Mic else Icons.Outlined.PlayArrow,
        contentDescription = if (playing) "正在播放语音" else "播放语音",
        tint = Color.White,
        modifier = Modifier.size(18.dp),
      )
      AudioBars(playing = playing, modifier = Modifier.weight(1f))
      Text(
        formatAudioDuration(msg.audioDurationMs ?: 0L),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.92f),
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
private fun AudioBars(playing: Boolean, modifier: Modifier = Modifier) {
  val alpha = if (playing) 1f else 0.68f
  val heights = listOf(10, 16, 12, 22, 14, 18, 11, 20, 13, 17, 10, 15)
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(3.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    heights.forEach { h ->
      Box(
        modifier = Modifier
          .height(h.dp)
          .widthIn(min = 3.dp, max = 3.dp)
          .clip(RoundedCornerShape(999.dp))
          .background(Color.White.copy(alpha = alpha)),
      )
    }
  }
}

private fun formatAudioDuration(ms: Long): String {
  val sec = (ms / 1000).coerceAtLeast(1)
  return "${sec}s"
}

@Composable
private fun AgentStatusLine(text: String) {
  val tokens = VlogPilotTokens
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Box(
      modifier = Modifier
        .padding(top = 6.dp)
        .size(5.dp)
        .clip(CircleShape)
        .background(tokens.colors.accent),
    )
    Text(
      text,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun SystemLine(text: String) {
  val tokens = VlogPilotTokens
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 2.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text,
      style = MaterialTheme.typography.labelSmall,
      color = tokens.colors.tertiaryLabel,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun AgentToolCard(label: String, stage: String?, decision: EventDecisions?) {
  val tokens = VlogPilotTokens
  val tint = when (stage) {
    "browse" -> tokens.colors.accent
    "audience" -> tokens.colors.systemPink
    "director" -> tokens.colors.systemOrange
    "editor" -> tokens.colors.systemPurple
    "critic" -> tokens.colors.systemOrange
    "render", "rendering" -> tokens.colors.accent
    else -> tokens.colors.secondaryLabel
  }
  // The agent's output for this stage. Null while still running.
  val output = remember(decision, stage) { decision?.let { agentOutputFor(it, stage) } }
  val hasOutput = output != null
  var expanded by remember(decision?.eventId, stage) { mutableStateOf(false) }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
  ) {
    Column {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable(enabled = hasOutput) { expanded = !expanded }
          .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
      ) {
        Box(
          modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(tint.copy(alpha = if (tokens.colors.isDark) 0.22f else 0.12f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
          )
        }
        Text(
          if (output != null && !expanded && output.summary.isNotBlank()) "$label · ${output.summary}" else label,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (hasOutput) {
          Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "收起详情" else "展开详情",
            tint = tokens.colors.tertiaryLabel,
            modifier = Modifier.size(16.dp),
          )
        } else {
          LoadingDot(tint = tint)
        }
      }
      AnimatedExpand(expanded = expanded && output != null) {
        Column(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
          HairlineDivider(startInset = 0.dp)
          output?.body?.forEach { line ->
            Text(
              line,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
    }
  }
}

/** Distilled agent output for the chat AGENT_TOOL card. summary is a 1-line
 *  preview shown collapsed; body is bullet-style detail shown expanded. */
private data class AgentOutput(val summary: String, val body: List<String>)

private fun agentOutputFor(d: EventDecisions, stage: String?): AgentOutput? = when (stage) {
  "browse" -> d.memory?.let { m ->
    AgentOutput(
      summary = m.storylineSummary.take(40),
      body = buildList {
        if (m.storylineSummary.isNotBlank()) add(m.storylineSummary)
        if (m.charactersObserved.isNotEmpty()) add("人物：${m.charactersObserved.joinToString("、")}")
        if (m.emotionalArc.isNotBlank()) add("情绪弧线：${m.emotionalArc}")
        if (m.visualStyleSignals.isNotBlank()) add("视觉信号：${m.visualStyleSignals}")
      },
    )
  }
  "audience" -> d.audience?.let { a ->
    val pace = when (a.pace) {
      com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Pace.SNAPPY -> "快剪 15-18s"
      com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Pace.BALANCED -> "均衡 18-22s"
      com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Pace.LINGERING -> "留白 22-28s"
    }
    AgentOutput(
      summary = "$pace · ${a.emotionalPayoff.take(20)}",
      body = listOfNotNull(
        "节奏：$pace",
        a.emotionalPayoff.takeIf { it.isNotBlank() }?.let { "情绪回报：$it" },
        a.hookStrategy.takeIf { it.isNotBlank() }?.let { "开头钩子：$it" },
        a.pacingGuidance.takeIf { it.isNotBlank() }?.let { "节奏建议：$it" },
        a.avoidList.takeIf { it.isNotEmpty() }?.let { "避免：${it.joinToString("、")}" },
      ),
    )
  }
  "director" -> d.director?.let { dir ->
    AgentOutput(
      summary = "${dir.title.ifBlank { "(未命名)" }} · ${dir.shotBlueprint.size} 镜头 · ${dir.targetDurationSec.toInt()}s",
      body = buildList {
        if (dir.title.isNotBlank()) add("标题：${dir.title}")
        if (dir.tone.isNotBlank()) add("基调：${dir.tone}")
        if (dir.narrativeArc.isNotEmpty()) add("叙事弧：${dir.narrativeArc.joinToString(" → ")}")
        dir.shotBlueprint.take(5).forEach { sr ->
          add("${sr.position}. ${sr.role.name.lowercase()} · ${"%.1f".format(java.util.Locale.US, sr.durationSec)}s · ${sr.visualRequirements.take(30)}")
        }
        if (dir.shotBlueprint.size > 5) add("…另外 ${dir.shotBlueprint.size - 5} 个镜头")
      },
    )
  }
  "editor" -> (d.timelineFinal ?: d.timelineV1)?.let { t ->
    val totalSec = t.shots.sumOf { it.durationSec.toDouble() }
    AgentOutput(
      summary = "${t.shots.size} 镜头 · ${"%.1f".format(java.util.Locale.US, totalSec)}s",
      body = buildList {
        t.shots.sortedBy { it.order }.take(5).forEach { shot ->
          val rationale = shot.rationale.take(30)
          add("#${shot.order} ${"%.1f".format(java.util.Locale.US, shot.durationSec)}s${if (rationale.isNotBlank()) " · $rationale" else ""}")
        }
        if (t.shots.size > 5) add("…另外 ${t.shots.size - 5} 个镜头")
      },
    )
  }
  "critic" -> d.critique?.let { c ->
    val v = when (c.verdict) {
      com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.CriticVerdict.ACCEPT -> "通过"
      com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.CriticVerdict.REVISE -> "需修订"
      com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.CriticVerdict.ABORT -> "放弃"
    }
    AgentOutput(
      summary = "$v · ${c.issues.size} 条意见",
      body = buildList {
        add("结论：$v")
        c.issues.forEach { add("• $it") }
        if (c.revisedRequests.isNotEmpty()) add("修订镜头：${c.revisedRequests.size} 处")
      },
    )
  }
  "render", "rendering" -> d.mp4Path?.let { mp4 ->
    AgentOutput(
      summary = mp4.substringAfterLast('/'),
      body = listOfNotNull(
        "输出文件：${mp4.substringAfterLast('/')}",
        d.perf?.totalMs?.takeIf { it > 0 }?.let { "总耗时：${formatMs(it)}" },
        d.perf?.renderMs?.takeIf { it > 0 }?.let { "渲染耗时：${formatMs(it)}" },
      ),
    )
  }
  else -> null
}

private fun formatMs(ms: Long): String = when {
  ms < 1000 -> "${ms}ms"
  ms < 60_000 -> "${ms / 1000}s"
  else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

@Composable
private fun ResultCard(
  msg: ChatMessage,
  decisions: List<EventDecisions>,
  onOpenResult: (String) -> Unit,
) {
  val tokens = VlogPilotTokens
  val decision = msg.eventId?.let { id -> decisions.firstOrNull { it.eventId == id } }
  val durationLabel = decision?.let { d ->
    (d.timelineFinal ?: d.timelineV1)?.let { t ->
      val totalSec = t.shots.sumOf { it.durationSec.toDouble() }
      "${t.shots.size} 镜头 · ${"%.1f".format(java.util.Locale.US, totalSec)}s"
    }
  }
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .let { mod -> if (msg.eventId != null) mod.clickable { onOpenResult(msg.eventId) } else mod },
    shape = RoundedCornerShape(12.dp),
    color = tokens.colors.systemGreen.copy(alpha = if (tokens.colors.isDark) 0.18f else 0.10f),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(
          Icons.Outlined.AutoAwesome,
          contentDescription = null,
          tint = tokens.colors.systemGreen,
          modifier = Modifier.size(18.dp),
        )
        Text(
          msg.text.ifBlank { "搞定！" },
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = tokens.colors.systemGreen,
        )
        if (msg.eventId != null) {
          Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "查看详情",
            tint = tokens.colors.systemGreen,
            modifier = Modifier.size(16.dp),
          )
        }
      }
      val meta = listOfNotNull(
        durationLabel,
        msg.mp4Path?.substringAfterLast('/'),
      ).joinToString(" · ")
      if (meta.isNotBlank()) {
        Text(
          meta,
          style = MaterialTheme.typography.labelSmall,
          color = tokens.colors.secondaryLabel,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun LoadingDots() {
  Row(verticalAlignment = Alignment.CenterVertically) {
    LoadingDot(tint = VlogPilotTokens.colors.accent)
  }
}

@Composable
private fun LoadingDot(tint: Color) {
  val infinite = rememberInfiniteTransition(label = "loading-dot")
  val scale by infinite.animateFloat(
    initialValue = 0.6f,
    targetValue = 1.4f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 800),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "scale",
  )
  Box(
    modifier = Modifier
      .size((6 * scale).dp)
      .clip(CircleShape)
      .background(tint),
  )
}

@Composable
private fun ChatHistorySheet(
  conversations: List<ChatConversation>,
  activeId: String?,
  onSelect: (String) -> Unit,
  onDelete: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val tokens = VlogPilotTokens
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      PlainTextButton(text = "完成", onClick = onDismiss)
    },
    title = {
      Text("历史对话", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    },
    text = {
      LazyColumn(
        modifier = Modifier.heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        items(conversations, key = { it.id }) { convo ->
          val isActive = convo.id == activeId
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(if (isActive) tokens.colors.accentTint else Color.Transparent)
              .clickable { onSelect(convo.id) }
              .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(
                convo.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isActive) tokens.colors.accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(convo.updatedAtMs)),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.tertiaryLabel,
              )
            }
            Box(
              modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable { onDelete(convo.id) },
              contentAlignment = Alignment.Center,
            ) {
              Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = tokens.colors.systemRed, modifier = Modifier.size(17.dp))
            }
          }
        }
      }
    },
    containerColor = MaterialTheme.colorScheme.background,
  )
}

// ───────────── helper extension to avoid pulling in lifecycle-runtime ─────
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateSafe(): androidx.compose.runtime.State<T> {
  val state = remember { androidx.compose.runtime.mutableStateOf(value) }
  LaunchedEffect(this) {
    collect { state.value = it }
  }
  return state
}
