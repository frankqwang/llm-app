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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventCandidateSnapshot
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionManifest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collect

/** Phase 3 entry point — replaces the placeholder. Pulls all state from a
 *  ChatViewModel created lazily per VM lifetime; the parent owns this VM
 *  if it wants to share it across recompositions. For now we instantiate
 *  inline because the chat is fully self-contained. */
@Composable
internal fun ChatScreen(
  viewModel: ChatViewModel,
  decisions: List<EventDecisions>,
  eventSelection: EventSelectionManifest?,
  focusedCandidate: EventCandidateSnapshot? = null,
  progress: ProgressSnapshot = ProgressSnapshot(),
  pipelineRunning: Boolean = false,
  onSend: (text: String, currentEventId: String?) -> Unit,
  onOpenResult: (eventId: String) -> Unit = {},
  onFocusEventChange: (String?) -> Unit = {},
  pendingPrefill: String? = null,
  autoSendPrefill: Boolean = false,
  onPrefillConsumed: () -> Unit = {},
  // Phase 1 fallback callbacks — kept so the empty-state buttons still work.
  onRescan: () -> Unit = {},
  onOpenCurator: () -> Unit = {},
) {

  val tokens = VlogPilotTokens
  val conversations by viewModel.conversations.collectAsStateSafe()
  val activeConvoId by viewModel.activeConversationId.collectAsStateSafe()
  val messages by viewModel.messages.collectAsStateSafe()
  val activeConvo = conversations.firstOrNull { it.id == activeConvoId }
  val hasWorkContext = focusedCandidate != null || pipelineRunning

  var showHistory by remember { mutableStateOf(false) }
  var inputText by remember { mutableStateOf("") }

  LaunchedEffect(activeConvo?.eventId) {
    onFocusEventChange(activeConvo?.eventId)
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
  Column(modifier = Modifier.fillMaxSize()) {
    // ── Header: title + rescan + history + new conversation ───────────────
    if (!hasWorkContext) {
      ChatHeader(
        conversation = activeConvo,
        onNew = {
          onFocusEventChange(null)
          viewModel.newConversation()
        },
        onShowHistory = { showHistory = true },
        onRescan = onRescan,
      )

      Spacer(Modifier.height(8.dp))
    }

    if (hasWorkContext) {
      ChatWorkContextCard(
        candidate = focusedCandidate,
        progress = progress,
        running = pipelineRunning,
        onStart = { candidate ->
          val command = "帮我做「${storyTitle(candidate)}」这个候选故事"
          viewModel.bindActiveEvent(candidate.eventId)
          viewModel.appendUserMessage(command)
          onSend(command, candidate.eventId)
        },
      )
      Spacer(Modifier.height(8.dp))
    }

    // ── Messages ────────────────────────────────────────────────────────────
    val listState = rememberLazyListState()
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
    LaunchedEffect(messages.size) {
      if (messages.isNotEmpty() && isNearBottom) {
        listState.animateScrollToItem(messages.size - 1)
      }
    }
    if (messages.isEmpty()) {
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (!hasWorkContext) {
          ChatWelcomeBlock(
            decisionsCount = decisions.count { it.mp4Path != null },
            candidateCount = eventSelection?.candidates?.size ?: 0,
            onRescan = onRescan,
            onOpenCurator = onOpenCurator,
          )
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(messages, key = { it.id }) { msg -> ChatMessageRow(msg, decisions, onOpenResult) }
      }
    }

    Spacer(Modifier.height(12.dp))

    // ── Suggestion chips (only on fresh conversation) ───────────────────────
    if (messages.size <= 1 && !hasWorkContext) {
      ChatSuggestions(
        onTap = { suggestion -> inputText = suggestion },
      )
      Spacer(Modifier.height(12.dp))
    }

    // ── Input bar (always at bottom thanks to weight on the section above) ─
    ChatInputBar(
      value = inputText,
      onValueChange = { inputText = it },
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
        modifier = Modifier.size(20.dp),
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
private fun ChatWorkContextCard(
  candidate: EventCandidateSnapshot?,
  progress: ProgressSnapshot,
  running: Boolean,
  onStart: (EventCandidateSnapshot) -> Unit,
) {
  val tokens = VlogPilotTokens
  val friendly = friendlyProgress(progress, making = running)
  val fraction = remember(friendly.current, friendly.total) {
    if (friendly.total > 0) (friendly.current.toFloat() / friendly.total.toFloat()).coerceIn(0f, 1f) else null
  }
  Surface(
    modifier = Modifier.fillMaxWidth(),
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
            candidate?.let(::storyTitle) ?: "制作进行中",
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
      }

      candidate?.let {
        CandidateAssetPreview(candidate = it, compact = running)
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
        if (progress.recent.isNotEmpty()) {
          Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            progress.recent.take(4).forEach { item ->
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.Top,
              ) {
                Box(
                  modifier = Modifier
                    .padding(top = 6.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.accent),
                )
                Text(
                  item,
                  modifier = Modifier.weight(1f),
                  style = MaterialTheme.typography.labelSmall,
                  color = tokens.colors.secondaryLabel,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }
      } else if (candidate != null) {
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
  onSend: () -> Unit,
) {
  val tokens = VlogPilotTokens
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Bottom,
  ) {
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.weight(1f),
      placeholder = {
        Text(
          "和 AI 聊聊你想做什么...",
          color = tokens.colors.tertiaryLabel,
          style = MaterialTheme.typography.bodyMedium,
        )
      },
      shape = RoundedCornerShape(22.dp),
      maxLines = 4,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
      keyboardActions = KeyboardActions(onSend = { onSend() }),
      colors = OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = tokens.colors.opaqueSeparator,
        focusedBorderColor = tokens.colors.accent,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        focusedContainerColor = MaterialTheme.colorScheme.surface,
      ),
    )
    val canSend = value.isNotBlank()
    Box(
      modifier = Modifier
        .size(50.dp)
        .clip(CircleShape)
        .background(if (canSend) tokens.colors.accent else tokens.colors.accent.copy(alpha = 0.4f))
        .clickable(enabled = canSend) { onSend() },
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        Icons.AutoMirrored.Outlined.Send,
        contentDescription = "发送",
        tint = Color.White,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@Composable
private fun ChatMessageRow(
  msg: ChatMessage,
  decisions: List<EventDecisions>,
  onOpenResult: (String) -> Unit,
) {
  when (msg.role) {
    ChatRole.USER -> UserBubble(msg.text)
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
private fun UserBubble(text: String) {
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
        .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
      Text(text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
  }
}

@Composable
private fun AgentStatusLine(text: String) {
  val tokens = VlogPilotTokens
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Box(
      modifier = Modifier
        .padding(top = 4.dp)
        .size(6.dp)
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
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
  ) {
    Column {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable(enabled = hasOutput) { expanded = !expanded }
          .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(
          modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = if (tokens.colors.isDark) 0.22f else 0.12f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
          )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (output != null && !expanded) {
            Text(
              output.summary,
              style = MaterialTheme.typography.labelSmall,
              color = tokens.colors.tertiaryLabel,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        if (hasOutput) {
          Icon(
            imageVector = if (expanded) Icons.Outlined.History else Icons.Outlined.Edit,
            contentDescription = if (expanded) "收起" else "展开",
            tint = tokens.colors.tertiaryLabel,
            modifier = Modifier.size(16.dp),
          )
        } else {
          LoadingDot(tint = tint)
        }
      }
      AnimatedExpand(expanded = expanded && output != null) {
        Column(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          HairlineDivider(startInset = 0.dp)
          Spacer(Modifier.height(2.dp))
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
    shape = RoundedCornerShape(16.dp),
    color = tokens.colors.systemGreen.copy(alpha = if (tokens.colors.isDark) 0.18f else 0.10f),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
          Icons.Outlined.AutoAwesome,
          contentDescription = null,
          tint = tokens.colors.systemGreen,
          modifier = Modifier.size(20.dp),
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
            modifier = Modifier.size(18.dp),
          )
        }
      }
      durationLabel?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.secondaryLabel)
      }
      msg.mp4Path?.let {
        Text(
          it.substringAfterLast('/'),
          style = MaterialTheme.typography.labelSmall,
          color = tokens.colors.tertiaryLabel,
        )
      }
      Text(
        "点击查看完整结果，或继续输入「改一改」让我调整。",
        style = MaterialTheme.typography.bodySmall,
        color = tokens.colors.secondaryLabel,
      )
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
        modifier = Modifier.heightIn(max = 400.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        items(conversations, key = { it.id }) { convo ->
          val isActive = convo.id == activeId
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(if (isActive) tokens.colors.accentTint else Color.Transparent)
              .clickable { onSelect(convo.id) }
              .padding(horizontal = 12.dp, vertical = 10.dp),
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
            IconButton(onClick = { onDelete(convo.id) }) {
              Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = tokens.colors.systemRed, modifier = Modifier.size(18.dp))
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
