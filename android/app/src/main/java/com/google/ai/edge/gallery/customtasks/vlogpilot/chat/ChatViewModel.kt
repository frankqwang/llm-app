/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * State holder for the Chat tab. Owns the active conversation, observes
 * PipelineEventBus to translate worker progress into chat messages, and
 * exposes a single send(text) entry point that the screen calls when the
 * user taps the send button. The actual command routing (start a generation,
 * fire an iteration, etc.) lives in ChatCommandRouter — kept separate so the
 * pipeline-side wiring is testable independent of UI.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.chat

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.SAMPLE_RATE
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.PipelineEventBus
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.PipelineProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Single chat-tab state holder. Hot-observable by Compose via the StateFlow
 *  fields. Backed by [ChatStore] so reloads survive process death. */
class ChatViewModel(private val context: Context) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val tag = "ChatViewModel"

  private val _conversations = MutableStateFlow<List<ChatConversation>>(emptyList())
  val conversations: StateFlow<List<ChatConversation>> = _conversations.asStateFlow()

  private val _activeConversationId = MutableStateFlow<String?>(null)
  val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

  private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

  init {
    scope.launch {
      reloadConversations()
      // If no conversations exist, seed with a welcome one so the chat tab
      // always shows something.
      if (_conversations.value.isEmpty()) {
        val seed = withContext(Dispatchers.IO) {
          ChatStore.createConversation(context, title = "新对话")
        }
        ChatStore.appendMessage(
          context,
          ChatMessage(
            id = "msg_${System.currentTimeMillis()}_seed",
            conversationId = seed.id,
            timestampMs = System.currentTimeMillis(),
            role = ChatRole.AGENT_STATUS,
            text = "你好，我是 VlogPilot。告诉我你想做什么 vlog——我会读你的相册，帮你写分镜，剪一条出来。",
          ),
        )
        reloadConversations()
        switchTo(seed.id)
      } else {
        // Resume the most recently updated conversation.
        switchTo(_conversations.value.first().id)
      }
    }
    // Bridge worker events into the chat stream.
    scope.launch {
      PipelineEventBus.flow.collect { event ->
        appendFromPipeline(event)
      }
    }
  }

  fun reload() {
    scope.launch { reloadConversations() }
  }

  fun newConversation(initialTitle: String = "新对话", eventId: String? = null) {
    scope.launch {
      val convo = withContext(Dispatchers.IO) {
        ChatStore.createConversation(context, title = initialTitle, eventId = eventId)
      }
      reloadConversations()
      switchTo(convo.id)
    }
  }

  /** Link the active conversation to the story currently being edited.
   *  Pipeline progress is filtered by eventId, so binding before enqueueing
   *  work keeps live progress visible in the Chat tab instead of only in
   *  the Works running strip. */
  fun bindActiveEvent(eventId: String) {
    val convoId = ensureActiveConversation()
    val now = System.currentTimeMillis()
    _conversations.update { list ->
      list.map { convo ->
        if (convo.id == convoId) convo.copy(eventId = eventId, updatedAtMs = now) else convo
      }
    }
    scope.launch {
      withContext(Dispatchers.IO) {
        ChatStore.updateConversation(context, convoId, eventId = eventId)
      }
      reloadConversations()
    }
  }

  fun switchTo(convoId: String) {
    _activeConversationId.value = convoId
    scope.launch {
      val msgs = withContext(Dispatchers.IO) { ChatStore.loadMessages(context, convoId) }
      _messages.value = msgs
    }
  }

  fun deleteConversation(convoId: String) {
    scope.launch {
      withContext(Dispatchers.IO) { ChatStore.deleteConversation(context, convoId) }
      reloadConversations()
      // Switch to the next one or seed a fresh conversation.
      val next = _conversations.value.firstOrNull()
      if (next != null) {
        switchTo(next.id)
      } else {
        newConversation()
      }
    }
  }

  /** Append a USER message — caller (router) is responsible for triggering
   *  any pipeline action that should follow. The chat stream itself is just
   *  the visual log; ChatCommandRouter does the work.
   *
   *  If no active conversation exists yet (init still loading), seed one
   *  inline so the user's tap is never silently dropped. */
  fun appendUserMessage(text: String): ChatMessage {
    val convoId = ensureActiveConversation()
    val now = System.currentTimeMillis()
    val msg = ChatMessage(
      id = "msg_${now}_user",
      conversationId = convoId,
      timestampMs = now,
      role = ChatRole.USER,
      text = text,
    )
    pushSync(msg)
    // If the conversation title is still the default, retitle to the
    // user's first message — like Claude.ai's auto-titled threads.
    val convo = _conversations.value.firstOrNull { it.id == convoId }
    if (convo == null || convo.title == "新对话") {
      scope.launch {
        withContext(Dispatchers.IO) {
          ChatStore.updateConversation(context, convoId, title = text.take(20))
        }
        reloadConversations()
      }
    }
    return msg
  }

  fun appendUserAudioMessage(audioWav: ByteArray): ChatMessage {
    val convoId = ensureActiveConversation()
    val now = System.currentTimeMillis()
    val msgId = "msg_${now}_user_audio"
    val audioPath = runCatching {
      ChatStore.saveAudioAttachment(context, convoId, msgId, audioWav)
    }.getOrNull()
    val durationMs = wavDurationMs(audioWav)
    val msg = ChatMessage(
      id = msgId,
      conversationId = convoId,
      timestampMs = now,
      role = ChatRole.USER,
      text = "语音指令",
      audioPath = audioPath,
      audioDurationMs = durationMs,
    )
    pushSync(msg)
    val convo = _conversations.value.firstOrNull { it.id == convoId }
    if (convo == null || convo.title == "新对话") {
      scope.launch {
        withContext(Dispatchers.IO) {
          ChatStore.updateConversation(context, convoId, title = "语音指令")
        }
        reloadConversations()
      }
    }
    return msg
  }

  /** Append a SYSTEM / AGENT_STATUS / etc message produced by the router.
   *  Updates the message stream synchronously so UI reflects the reply
   *  immediately — disk persistence is best-effort and runs in background. */
  fun appendAgentMessage(role: ChatRole, text: String, eventId: String? = null, agentStage: String? = null, mp4Path: String? = null) {
    val convoId = ensureActiveConversation()
    val now = System.currentTimeMillis()
    // Suffix with a short random tag so two replies in the same millisecond
    // (which the LazyColumn keys by .id) don't collide and drop one row.
    val rand = (1000..9999).random()
    val msg = ChatMessage(
      id = "msg_${now}_${role.name.lowercase()}_$rand",
      conversationId = convoId,
      timestampMs = now,
      role = role,
      text = text,
      eventId = eventId,
      agentStage = agentStage,
      mp4Path = mp4Path,
    )
    pushSync(msg)
  }

  /** Returns the active conversation id, creating one synchronously if
   *  none is set yet. The fallback only happens early in init (the UI
   *  composes before the seed-conversation coroutine completes); without
   *  it, the user's first tap before init finishes would be dropped. */
  private fun ensureActiveConversation(): String {
    _activeConversationId.value?.let { return it }
    Log.w(tag, "no active conversation — seeding one inline")
    val convo = ChatStore.createConversation(context, title = "新对话")
    _conversations.value = listOf(convo) + _conversations.value
    _activeConversationId.value = convo.id
    return convo.id
  }

  fun close() {
    scope.cancel()
  }

  private suspend fun reloadConversations() {
    val convos = withContext(Dispatchers.IO) { ChatStore.listConversations(context) }
    _conversations.value = convos
  }

  /** Push a message to the message stream synchronously, so the UI sees it
   *  this frame, then persist to disk in the background. The previous
   *  scope-launched flow lost messages whenever the chat scope was cancelled
   *  between the user tap and the IO completion — a free-form chat reply
   *  could vanish even though the user message above it persisted fine. */
  private fun pushSync(msg: ChatMessage) {
    if (_activeConversationId.value == msg.conversationId) {
      _messages.update { it + msg }
    }
    scope.launch {
      withContext(Dispatchers.IO) { ChatStore.appendMessage(context, msg) }
    }
  }

  /** Translate a single PipelineProgress event into a chat message. Only
   *  emits when the active conversation is linked to the same event (or has
   *  no event yet — a fresh conversation about to receive its first vlog). */
  private fun appendFromPipeline(event: PipelineProgress) {
    val convoId = _activeConversationId.value ?: return
    val convo = _conversations.value.firstOrNull { it.id == convoId } ?: return
    val (role, text, eventId, stage, mp4) = translate(event) ?: return
    if (convo.eventId != null && eventId != null && convo.eventId != eventId) {
      // Active conversation isn't about this event; skip.
      return
    }
    appendAgentMessage(role = role, text = text, eventId = eventId, agentStage = stage, mp4Path = mp4)
    if (convo.eventId == null && eventId != null) {
      // Bind the conversation to the first event it sees.
      scope.launch {
        withContext(Dispatchers.IO) {
          ChatStore.updateConversation(context, convoId, eventId = eventId)
        }
        reloadConversations()
      }
    }
  }

  private data class Translated(
    val role: ChatRole,
    val text: String,
    val eventId: String?,
    val stage: String?,
    val mp4: String?,
  )

  private fun translate(event: PipelineProgress): Translated? = when (event) {
    is PipelineProgress.Ingesting -> Translated(ChatRole.AGENT_STATUS, "正在扫描相册...", null, null, null)
    is PipelineProgress.IngestDone -> Translated(
      ChatRole.AGENT_STATUS,
      "扫描完成 · ${event.assetCount} 张素材 · ${event.eventCount} 个事件",
      null, null, null,
    )
    is PipelineProgress.Perceiving ->
      if (shouldEmitProgress(event.current, event.total)) {
        Translated(
          ChatRole.AGENT_STATUS,
          "读取素材特征 · ${event.current}/${event.total} · ${event.assetName.ifBlank { event.mediaType }}",
          null,
          null,
          null,
        )
      } else {
        null
      }
    is PipelineProgress.Annotating ->
      if (shouldEmitProgress(event.current, event.total)) {
        val verb = if (event.phase == "start") "正在打标" else "已标注"
        Translated(
          ChatRole.AGENT_STATUS,
          "$verb · ${event.current}/${event.total} · ${event.assetName.ifBlank { event.mediaType }}",
          null,
          null,
          null,
        )
      } else {
        null
      }
    is PipelineProgress.AnnotationDone -> Translated(
      ChatRole.AGENT_STATUS, "素材标签完成 · ${event.annotated}/${event.total}", null, null, null,
    )
    is PipelineProgress.ScoutingEvents -> null
    is PipelineProgress.SelectingEvents -> Translated(
      ChatRole.AGENT_STATUS, "挑选事件 · ${event.selectedCount}/${event.candidateCount}", null, null, null,
    )
    is PipelineProgress.EventStart -> Translated(
      ChatRole.SYSTEM, "开始制作 · ${event.eventId} (${event.index}/${event.total})", event.eventId, null, null,
    )
    is PipelineProgress.EventStage -> Translated(
      ChatRole.AGENT_TOOL, agentLabel(event.stage), event.eventId, event.stage, null,
    )
    is PipelineProgress.EventDone -> Translated(
      ChatRole.RESULT, "搞定！", event.eventId, null, event.outputPath,
    )
    is PipelineProgress.EventFailed -> Translated(
      ChatRole.SYSTEM, "失败：${event.message}", event.eventId, null, null,
    )
    is PipelineProgress.IterationStart -> Translated(
      ChatRole.AGENT_STATUS, "开始迭代 · v${event.baseVersion}→v${event.targetVersion}", event.eventId, null, null,
    )
    is PipelineProgress.IterationStage -> Translated(
      ChatRole.AGENT_TOOL, "迭代 · ${event.phase}", event.eventId, event.phase, null,
    )
    is PipelineProgress.IterationDone -> Translated(
      ChatRole.RESULT, event.changeSummary.ifBlank { "迭代完成" }, event.eventId, null, event.outputPath,
    )
    is PipelineProgress.IterationFailed -> Translated(
      ChatRole.SYSTEM, "迭代失败：${event.message}", event.eventId, null, null,
    )
    PipelineProgress.AllDone -> null
    is PipelineProgress.Failed -> Translated(ChatRole.SYSTEM, "失败：${event.message}", null, null, null)
    is PipelineProgress.DownloadingModels -> null
  }

  private fun agentLabel(stage: String): String = when (stage) {
    "browse" -> "Browse · 阅读事件"
    "audience" -> "Audience · 观众诉求"
    "director" -> "Director · 写分镜"
    "editor" -> "Editor · 选镜头"
    "critic" -> "Critic · 审片"
    "render", "rendering" -> "Render · 渲染"
    else -> "Stage · $stage"
  }

  private fun shouldEmitProgress(current: Int, total: Int): Boolean {
    if (current <= 1 || current >= total) return true
    val step = when {
      total <= 40 -> 1
      total <= 120 -> 4
      else -> 5
    }
    return current % step == 0
  }

  private fun wavDurationMs(audioWav: ByteArray): Long {
    val pcmBytes = (audioWav.size - 44).coerceAtLeast(0)
    return (pcmBytes * 1000L) / (SAMPLE_RATE * 2L)
  }
}
