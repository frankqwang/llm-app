/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * AgentRuntime — wraps the gallery's LlmChatModelHelper so the 5 agents
 * (Montage, Audience, Director, Editor, Critic) can call Gemma 4 E2B with a
 * suspend-friendly API. One Engine, one Conversation, reset between agent
 * calls so each agent sees only its own system prompt + user turn.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Contents
import java.io.Closeable
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AgentRuntime(
  private val context: Context,
  private val gemmaModel: Model,
) : Closeable {

  @Volatile private var ownedInit = false

  /**
   * If the gallery already initialized this Model (e.g. user opened it from
   * LLM Ask Image first), reuse the existing engine. Otherwise call
   * [LlmChatModelHelper.initialize] ourselves and remember to clean it up in
   * [close]. Idempotent.
   */
  suspend fun ensureInitialized() = suspendCancellableCoroutine<Unit> { cont ->
    if (gemmaModel.instance is LlmModelInstance) {
      Log.d(TAG, "Reusing existing engine for model '${gemmaModel.name}'")
      if (cont.isActive) cont.resume(Unit)
      return@suspendCancellableCoroutine
    }
    LlmChatModelHelper.initialize(
      context = context,
      model = gemmaModel,
      taskId = TASK_ID,
      supportImage = true,
      supportAudio = false,
      systemInstruction = null,
      onDone = { error ->
        if (error.isEmpty()) {
          ownedInit = true
          if (cont.isActive) cont.resume(Unit)
        } else {
          Log.e(TAG, "Engine init failed: $error")
          if (cont.isActive) cont.resume(Unit) // allow caller to fall back to stub responses
        }
      },
    )
  }

  /**
   * Reset conversation with a new system prompt, then run one inference and
   * return the entire response text. If the engine is not available the
   * returned string is empty.
   */
  suspend fun ask(
    systemPrompt: String,
    userText: String,
    images: List<Bitmap> = emptyList(),
  ): String = suspendCancellableCoroutine { cont ->
    if (gemmaModel.instance !is LlmModelInstance) { cont.resume(""); return@suspendCancellableCoroutine }
    LlmChatModelHelper.resetConversation(
      model = gemmaModel,
      supportImage = images.isNotEmpty(),
      supportAudio = false,
      systemInstruction = Contents.of(systemPrompt),
      enableConversationConstrainedDecoding = false,
    )
    val sb = StringBuilder()
    LlmChatModelHelper.runInference(
      model = gemmaModel,
      input = userText,
      resultListener = { partial, done, _ ->
        sb.append(partial)
        if (done && cont.isActive) cont.resume(sb.toString())
      },
      cleanUpListener = {},
      onError = { msg ->
        Log.w(TAG, "ask onError: $msg")
        if (cont.isActive) cont.resume(sb.toString())
      },
      images = images,
    )
  }

  /** Only releases the engine if WE created it; if the gallery owns it, hands-off. */
  override fun close() {
    if (ownedInit) {
      try { LlmChatModelHelper.cleanUp(gemmaModel) {} } catch (_: Throwable) {}
      ownedInit = false
    }
  }

  companion object {
    private const val TAG = "AgentRuntime"
    const val TASK_ID = "vlog_pilot"
  }
}
