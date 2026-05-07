/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Per-stage wall-clock timer. Each event collects browse/audience/director/
 * editor/critic/render durations + total. Persisted alongside the decision
 * JSONs so the UI viewer (and any post-mortem inspection) can show "AI spent
 * 8s here, ffmpeg spent 92s there" without parsing logcat.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StagePerf(
  val eventId: String,
  val perceptionAssetCount: Int = 0,
  val perceptionMs: Long = 0,
  val perceptionCacheHits: Int = 0,
  val browseMs: Long = 0,
  val audienceMs: Long = 0,
  val directorMs: Long = 0,
  val editorMs: Long = 0,
  val criticMs: Long = 0,
  val renderMs: Long = 0,
  val totalMs: Long = 0,
)

class PerfTimer(private val eventId: String) {

  private var browseMs: Long = 0
  private var audienceMs: Long = 0
  private var directorMs: Long = 0
  private var editorMs: Long = 0
  private var criticMs: Long = 0
  private var renderMs: Long = 0
  private val totalStart = System.nanoTime()

  suspend fun <T> browse(block: suspend () -> T): T = measure({ browseMs = it }, block)
  suspend fun <T> audience(block: suspend () -> T): T = measure({ audienceMs = it }, block)
  suspend fun <T> director(block: suspend () -> T): T = measure({ directorMs = it }, block)
  suspend fun <T> editor(block: suspend () -> T): T = measure({ editorMs = it }, block)
  suspend fun <T> critic(block: suspend () -> T): T = measure({ criticMs = it }, block)
  suspend fun <T> render(block: suspend () -> T): T = measure({ renderMs = it }, block)

  /** Persists the totals to filesDir/decisions/<eventId>/perf.json. */
  fun finalize(
    context: Context,
    perceptionAssetCount: Int,
    perceptionMs: Long,
    perceptionCacheHits: Int,
  ): StagePerf {
    val perf = StagePerf(
      eventId = eventId,
      perceptionAssetCount = perceptionAssetCount,
      perceptionMs = perceptionMs,
      perceptionCacheHits = perceptionCacheHits,
      browseMs = browseMs,
      audienceMs = audienceMs,
      directorMs = directorMs,
      editorMs = editorMs,
      criticMs = criticMs,
      renderMs = renderMs,
      totalMs = (System.nanoTime() - totalStart) / 1_000_000,
    )
    try {
      val dir = File(context.filesDir, "decisions/$eventId").apply { mkdirs() }
      File(dir, "perf.json").writeText(Json { prettyPrint = true }.encodeToString(perf))
    } catch (_: Throwable) {
      // best-effort; a missing perf record is non-fatal
    }
    return perf
  }

  private suspend fun <T> measure(setter: (Long) -> Unit, block: suspend () -> T): T {
    val t0 = System.nanoTime()
    return try { block() } finally { setter((System.nanoTime() - t0) / 1_000_000) }
  }
}
