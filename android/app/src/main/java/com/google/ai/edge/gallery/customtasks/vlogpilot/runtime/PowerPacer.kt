/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Thermal-friendly pacing for long on-device generation. The goal is not to
 * reduce creative quality; it spreads decode/model bursts out so Android keeps
 * the foreground UI responsive and the SoC has time to cool between calls.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.runtime

import android.os.Process
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

object PowerPacer {

  private const val ENABLED = true
  private const val FRAME_DECODE_PAUSE_MS = 10L
  private const val PERCEPTION_ASSET_PAUSE_MS = 90L
  private const val VLM_ASSET_PAUSE_MS = 650L
  private const val AGENT_CALL_PAUSE_MS = 250L

  fun applyBackgroundThreadPriority() {
    if (!ENABLED) return
    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
  }

  fun afterFrameDecode(index: Int, total: Int) {
    if (!ENABLED || total <= 2 || index >= total - 1) return
    sleepQuietly(FRAME_DECODE_PAUSE_MS)
  }

  suspend fun afterPerceptionAsset(cacheHit: Boolean) {
    if (cacheHit) {
      yield()
      return
    }
    breathe(PERCEPTION_ASSET_PAUSE_MS)
  }

  suspend fun afterVlmAsset() {
    breathe(VLM_ASSET_PAUSE_MS)
  }

  suspend fun afterAgentCall() {
    breathe(AGENT_CALL_PAUSE_MS)
  }

  private suspend fun breathe(ms: Long) {
    if (!ENABLED) return
    applyBackgroundThreadPriority()
    yield()
    delay(ms)
  }

  private fun sleepQuietly(ms: Long) {
    applyBackgroundThreadPriority()
    try {
      Thread.sleep(ms)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }
}
