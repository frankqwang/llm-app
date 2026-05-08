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

/** Per-iteration phase timings, persisted as decisions/<eventId>/iter_<N>/perf.json
 *  so the post-mortem can attribute time across feedback-parse → editor →
 *  render. parseMs is 0 for chip-only iterations (no LLM call). */
@Serializable
data class IterationPerf(
  val eventId: String,
  val iterationId: String,
  val scope: String,
  val parseMs: Long = 0,
  val editorMs: Long = 0,
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

/**
 * Lightweight perf tracker for the iteration sub-paths. Records parse / editor
 * / render wall-clock and, on finalize, persists IterationPerf next to the
 * iteration's other artifacts.
 *
 * Usage:
 *   val perfTimer = IterationPerfTimer(eventId, iterationId, scope)
 *   perfTimer.parse { intentParser.parseFeedback(...) }
 *   perfTimer.editor { applyRevisions(...) }
 *   perfTimer.render { renderer.render(...) }
 *   perfTimer.finalize(context)  // writes JSON
 */
class IterationPerfTimer(
  private val eventId: String,
  private val iterationId: String,
  private val scope: String,
) {
  private var parseMs: Long = 0
  private var editorMs: Long = 0
  private var renderMs: Long = 0
  private val totalStart = System.nanoTime()

  suspend fun <T> parse(block: suspend () -> T): T = measure({ parseMs = it }, block)
  suspend fun <T> editor(block: suspend () -> T): T = measure({ editorMs = it }, block)
  suspend fun <T> render(block: suspend () -> T): T = measure({ renderMs = it }, block)

  fun finalize(context: Context): IterationPerf {
    val perf = IterationPerf(
      eventId = eventId,
      iterationId = iterationId,
      scope = scope,
      parseMs = parseMs,
      editorMs = editorMs,
      renderMs = renderMs,
      totalMs = (System.nanoTime() - totalStart) / 1_000_000,
    )
    try {
      val dir = File(context.filesDir, "decisions/$eventId/$iterationId").apply { mkdirs() }
      File(dir, "perf.json").writeText(Json { prettyPrint = true }.encodeToString(perf))
    } catch (_: Throwable) {
      // best-effort; missing perf is non-fatal
    }
    return perf
  }

  private suspend fun <T> measure(setter: (Long) -> Unit, block: suspend () -> T): T {
    val t0 = System.nanoTime()
    return try { block() } finally { setter((System.nanoTime() - t0) / 1_000_000) }
  }
}
