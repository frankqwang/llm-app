/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * AgentRuntime — wraps the gallery's LlmChatModelHelper so the 5 agents
 * (Montage, Audience, Director, Editor, Critic) can call Gemma 4 E2B with a
 * suspend-friendly API. One Engine, one Conversation, reset between agent
 * calls so each agent sees only its own system prompt + user turn.
 *
 * Observability: every ask() writes a one-line JSON record to
 * filesDir/agent_log/agent.jsonl with timing + outcome (ok / timeout / error).
 * Vivo OriginOS suppresses adb logcat for unprivileged apps, so the file log
 * is the only reliable post-mortem for stuck pipelines.
 *
 * Liveness: ask() has a 90s wall-clock timeout. If neither `done` nor `onError`
 * fires by then the call returns whatever partial text was streamed and a
 * timeout marker is logged — the alternative (silent hang forever) was the
 * primary failure mode of the v4 first runs.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.PowerPacer
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Contents
import java.io.Closeable
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class AgentRuntime(
  private val context: Context,
  private val gemmaModel: Model,
) : Closeable {

  @Volatile private var ownedInit = false
  /** Optional context tag prepended to every ask label (e.g. "iter_v2/editor").
   *  Set by the orchestrator's iterate path so agent_log entries can be
   *  attributed to a specific iteration in post-mortem analysis. */
  @Volatile private var contextTag: String? = null
  private val logFile: File by lazy {
    File(context.filesDir, "agent_log/agent.jsonl").apply { parentFile?.mkdirs() }
  }
  private val logLock = Any()

  /** Sets a label prefix for subsequent ask() calls. Pass null to clear. */
  fun setContextTag(tag: String?) {
    contextTag = tag?.takeIf { it.isNotBlank() }
  }

  /**
   * If the gallery already initialized this Model (e.g. user opened it from
   * LLM Ask Image first), reuse the existing engine. Otherwise call
   * [LlmChatModelHelper.initialize] ourselves and remember to clean it up in
   * [close]. Throws when init fails — silently letting the pipeline run with
   * an uninitialized engine produces a 100%-stubbed video that looks like
   * success in the UI, which is a worse failure mode than an explicit error.
   */
  suspend fun ensureInitialized() {
    PowerPacer.applyBackgroundThreadPriority()
    val t0 = System.nanoTime()
    try {
      suspendCancellableCoroutine<Unit> { cont ->
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
              if (cont.isActive) cont.resumeWithException(
                IllegalStateException("LLM engine init failed for '${gemmaModel.name}': $error"),
              )
            }
          },
        )
      }
      logEvent("init", (System.nanoTime() - t0) / 1_000_000, "ok", null)
    } catch (t: Throwable) {
      logEvent("init", (System.nanoTime() - t0) / 1_000_000, "error", t.message)
      throw t
    }
  }

  /**
   * Reset conversation with a new system prompt, then run one inference and
   * return the entire response text. If the engine is not available the
   * returned string is empty.
   *
   * Returns whatever was streamed by the time `done` arrived OR the 90s
   * timeout elapses — whichever comes first. A timeout returns the partial
   * text (which may be empty if Gemma was still in prefill); the agent's
   * JSON parser will then fall back to its default schema.
   */
  suspend fun ask(
    systemPrompt: String,
    userText: String,
    images: List<Bitmap> = emptyList(),
    label: String = "ask",
  ): String {
    PowerPacer.applyBackgroundThreadPriority()
    if (gemmaModel.instance !is LlmModelInstance) {
      logEvent(label, 0, "no_engine", null)
      return ""
    }
    val finalLabel = contextTag?.let { "$it/$label" } ?: label
    val t0 = System.nanoTime()
    val sb = StringBuilder()
    var outcome = "ok"
    try {
      MODEL_CALL_MUTEX.withLock {
        writeLastAgentCall(finalLabel, userText, images)
        withTimeout(ASK_TIMEOUT_MS) {
          suspendCancellableCoroutine<Unit> { cont ->
            LlmChatModelHelper.resetConversation(
              model = gemmaModel,
              supportImage = images.isNotEmpty(),
              supportAudio = false,
              systemInstruction = Contents.of(systemPrompt),
              enableConversationConstrainedDecoding = false,
            )
            LlmChatModelHelper.runInference(
              model = gemmaModel,
              input = userText,
              resultListener = { partial, done, _ ->
                sb.append(partial)
                if (done && cont.isActive) cont.resume(Unit)
              },
              cleanUpListener = {},
              onError = { msg ->
                Log.w(TAG, "ask onError: $msg")
                if (cont.isActive) cont.resume(Unit)
              },
              images = images,
            )
            // If the coroutine is cancelled (timeout fired), try to stop the
            // running inference so it doesn't keep occupying the engine.
            cont.invokeOnCancellation {
              runCatching { LlmChatModelHelper.stopResponse(gemmaModel) }
            }
          }
        }
      }
      outcome = if (sb.isEmpty()) "empty" else "ok"
    } catch (_: TimeoutCancellationException) {
      Log.w(TAG, "ask timed out after ${ASK_TIMEOUT_MS}ms (label=$label, partial=${sb.length} chars)")
      outcome = "timeout"
    } catch (t: CancellationException) {
      Log.w(TAG, "ask cancelled (label=$label)")
      throw t
    } catch (t: Throwable) {
      Log.w(TAG, "ask failed: ${t.message}")
      outcome = "error:${t::class.java.simpleName}"
    }
    val ms = (System.nanoTime() - t0) / 1_000_000
    logEvent(finalLabel, ms, outcome, "chars=${sb.length} images=${images.size} dims=${imageDims(images)}")
    PowerPacer.afterAgentCall()
    return sb.toString()
  }

  private fun writeLastAgentCall(label: String, userText: String, images: List<Bitmap>) {
    val text = buildString {
      append("t=").append(System.currentTimeMillis()).append('\n')
      append("label=").append(label).append('\n')
      append("model=").append(gemmaModel.name).append('\n')
      append("chars=").append(userText.length).append('\n')
      append("images=").append(images.size).append('\n')
      append("dims=").append(imageDims(images)).append('\n')
    }
    try {
      synchronized(logLock) {
        File(context.filesDir, "_last_agent_call.txt").writeText(text)
      }
    } catch (_: Throwable) {
      // best-effort; diagnostics must never fail generation
    }
  }

  private fun imageDims(images: List<Bitmap>): String =
    images.joinToString(separator = ",", prefix = "[", postfix = "]") { "${it.width}x${it.height}" }

  private fun logEvent(label: String, ms: Long, outcome: String, detail: String?) {
    val line = buildString {
      append('{')
      append("\"t\":").append(System.currentTimeMillis()).append(',')
      append("\"label\":\"").append(label).append("\",")
      append("\"ms\":").append(ms).append(',')
      append("\"outcome\":\"").append(outcome).append('"')
      if (detail != null) append(",\"detail\":\"").append(detail.replace("\"", "\\\"")).append('"')
      append('}').append('\n')
    }
    try {
      synchronized(logLock) { logFile.appendText(line) }
    } catch (_: Throwable) {
      // best-effort; log writes never fail the pipeline
    }
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
    private const val ASK_TIMEOUT_MS = 90_000L
    private val MODEL_CALL_MUTEX = Mutex()
  }
}
