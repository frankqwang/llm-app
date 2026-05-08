/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Persists per-event iteration history and the staged "next iteration"
 * feedback. The orchestrator's render layer already archives previous MP4s via
 * VersionArchive — this store keeps the bookkeeping that maps iterations to
 * their archived files plus the user's feedback transcript so the UI can show
 * a 上一版 arrow with a tooltip.
 *
 * Storage layout (under filesDir):
 *   decisions/<eventId>/_history.json          → IterationHistory index
 *   decisions/<eventId>/iter_<N>/feedback_input.txt   → user's raw text
 *   decisions/<eventId>/iter_<N>/feedback_parsed.json → IterationFeedback
 *   decisions/<eventId>/iter_<N>/timeline.json        → snapshot of timeline used
 *   _pending_iteration/<eventId>.json          → staged feedback for the worker
 *   candidates/archive/<eventId>__<stamp>.mp4  → previous MP4s (VersionArchive)
 *
 * The pending dir is the worker's input channel: VlogPilotViewModel writes
 * one file there, then enqueues VlogPipelineWorker with iteration mode = true.
 * The worker reads + deletes the file. This avoids serializing structured data
 * through WorkManager's Data type-bag.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.IterationFeedback
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.IterationHistory
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.IterationSummary
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object IterationStore {

  private const val TAG = "IterationStore"
  private const val PENDING_DIR = "_pending_iteration"
  private const val HISTORY_FILE = "_history.json"
  private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

  // ---- pending feedback (worker input channel) ----

  fun stagePending(context: Context, eventId: String, feedback: IterationFeedback) {
    try {
      val dir = File(context.filesDir, PENDING_DIR).apply { mkdirs() }
      File(dir, "$eventId.json").writeText(json.encodeToString(feedback))
    } catch (t: Throwable) {
      Log.w(TAG, "stagePending failed for $eventId: ${t.message}")
    }
  }

  fun loadPending(context: Context, eventId: String): IterationFeedback? {
    val file = File(File(context.filesDir, PENDING_DIR), "$eventId.json")
    if (!file.isFile) return null
    return try {
      json.decodeFromString<IterationFeedback>(file.readText())
    } catch (t: Throwable) {
      Log.w(TAG, "loadPending failed for $eventId: ${t.message}")
      null
    }
  }

  fun clearPending(context: Context, eventId: String) {
    try {
      File(File(context.filesDir, PENDING_DIR), "$eventId.json").delete()
    } catch (_: Throwable) {
    }
  }

  /** Lists eventIds that have a staged pending feedback. Useful when the UI
   *  recovers state on cold start — if the worker was killed mid-iteration the
   *  pending file will still be there. */
  fun listPending(context: Context): List<String> {
    val dir = File(context.filesDir, PENDING_DIR)
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles()?.filter { it.name.endsWith(".json") }
      ?.map { it.nameWithoutExtension }
      ?.sorted()
      .orEmpty()
  }

  // ---- history index ----

  fun loadHistory(context: Context, eventId: String): IterationHistory? {
    val file = historyFile(context, eventId)
    if (!file.isFile) return null
    return try {
      json.decodeFromString<IterationHistory>(file.readText())
    } catch (t: Throwable) {
      Log.w(TAG, "loadHistory failed for $eventId: ${t.message}")
      null
    }
  }

  /** Append (or create) an iteration to the event's history. Idempotent —
   *  if an entry with the same version exists, it's replaced. */
  fun appendIteration(
    context: Context,
    eventId: String,
    summary: IterationSummary,
  ) {
    val current = loadHistory(context, eventId)
      ?: IterationHistory(eventId = eventId, iterations = emptyList(), currentVersion = 0)
    val merged = (current.iterations.filter { it.version != summary.version } + summary)
      .sortedBy { it.version }
    val next = current.copy(
      iterations = merged,
      currentVersion = merged.maxOf { it.version },
    )
    writeHistory(context, eventId, next)
  }

  /** Records the very first version (v1) when the initial pipeline finishes.
   *  Idempotent — safe to call again on a re-run. */
  fun recordInitialVersion(context: Context, eventId: String, mp4Path: String) {
    if (loadHistory(context, eventId)?.iterations?.any { it.version == 1 } == true) return
    appendIteration(
      context,
      eventId,
      IterationSummary(
        version = 1,
        mp4Path = mp4Path,
        createdAtMs = System.currentTimeMillis(),
        feedbackText = "",
        changeSummary = "首版",
      ),
    )
  }

  /** Returns the path of the version immediately before currentVersion, if any.
   *  Used by the 上一版 arrow on ResultEventCard. */
  fun previousVersionPath(context: Context, eventId: String): String? {
    val h = loadHistory(context, eventId) ?: return null
    val prev = h.iterations.filter { it.version < h.currentVersion }
      .maxByOrNull { it.version } ?: return null
    return prev.mp4Path.takeIf { File(it).isFile }
  }

  // ---- per-iteration artifacts ----

  fun writeIterationArtifacts(
    context: Context,
    eventId: String,
    iterationId: String,
    feedback: IterationFeedback,
    timelineUsed: Timeline,
  ) {
    val dir = iterationDir(context, eventId, iterationId).apply { mkdirs() }
    try {
      File(dir, "feedback_input.txt").writeText(feedback.userText)
      File(dir, "feedback_parsed.json").writeText(json.encodeToString(feedback))
      File(dir, "timeline.json").writeText(json.encodeToString(timelineUsed))
    } catch (t: Throwable) {
      Log.w(TAG, "writeIterationArtifacts failed for $eventId/$iterationId: ${t.message}")
    }
  }

  // ---- internals ----

  private fun writeHistory(context: Context, eventId: String, history: IterationHistory) {
    try {
      historyFile(context, eventId).writeText(json.encodeToString(history))
    } catch (t: Throwable) {
      Log.w(TAG, "writeHistory failed for $eventId: ${t.message}")
    }
  }

  private fun historyFile(context: Context, eventId: String): File {
    val dir = File(File(context.filesDir, "decisions"), eventId).apply { mkdirs() }
    return File(dir, HISTORY_FILE)
  }

  private fun iterationDir(context: Context, eventId: String, iterationId: String): File =
    File(File(File(context.filesDir, "decisions"), eventId), iterationId)
}
