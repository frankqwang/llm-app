/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * filesDir/_state.txt — single-line breadcrumb that the orchestrator rewrites
 * every time onProgress fires. External tools (adb shell run-as cat) can read
 * it to know exactly where the pipeline is, even when the app isn't logging
 * (vivo OriginOS suppresses adb logcat for unprivileged apps; this file is
 * the workaround).
 *
 * Format: "<unix_millis>\t<stage>\t<detail>" — one line, overwritten every tick.
 * Also appends to filesDir/_state_history.jsonl so the full trail survives.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

import android.content.Context
import java.io.File

object StateBreadcrumb {

  private val lock = Any()

  fun mark(context: Context, stage: String, detail: String = "") {
    val now = System.currentTimeMillis()
    val line = "$now\t$stage\t$detail"
    val current = File(context.filesDir, "_state.txt")
    val history = File(context.filesDir, "_state_history.jsonl")
    synchronized(lock) {
      runCatching { current.writeText(line) }
      runCatching {
        val esc = detail.replace("\\", "\\\\").replace("\"", "\\\"")
        history.appendText("{\"t\":$now,\"stage\":\"$stage\",\"detail\":\"$esc\"}\n")
      }
    }
  }

  fun fromProgress(context: Context, p: PipelineProgress) {
    when (p) {
      is PipelineProgress.DownloadingModels -> mark(context, "download", "${p.percent}% ${p.label}")
      PipelineProgress.Ingesting -> mark(context, "ingest", "")
      is PipelineProgress.ScoutingEvents -> mark(context, "event_scout", "${p.currentEvent}/${p.totalEvents} ${p.eventId} page=${p.currentPage}/${p.totalPages} cache=${p.cacheHit}")
      is PipelineProgress.SelectingEvents -> mark(context, "event_select_progress", "candidates=${p.candidateCount} selected=${p.selectedCount} ${p.detail}")
      is PipelineProgress.IngestDone -> mark(context, "ingest_done", "assets=${p.assetCount} events=${p.eventCount}")
      is PipelineProgress.Perceiving -> mark(context, "perceive", "${p.current}/${p.total} ${p.mediaType} ${p.assetName} cache=${p.cacheHit}")
      is PipelineProgress.Annotating -> mark(context, "annotate", "${p.phase} ${p.current}/${p.total} ${p.mediaType} ${p.assetName} ${p.elapsedMs}ms")
      is PipelineProgress.AnnotationDone -> mark(context, "annotate_done", "annotated=${p.annotated}/${p.total} ${p.elapsedMs}ms")
      is PipelineProgress.EventStart -> mark(context, "event_start", "${p.eventId} ${p.index}/${p.total}")
      is PipelineProgress.EventStage -> mark(context, "event_stage", "${p.eventId} ${p.stage} ${p.detail}")
      is PipelineProgress.EventDone -> mark(context, "event_done", "${p.eventId} ${p.outputPath}")
      is PipelineProgress.EventFailed -> mark(context, "event_failed", "${p.eventId} ${p.message}")
      is PipelineProgress.IterationStart -> mark(context, "iterate_start", "${p.eventId} v${p.baseVersion}->v${p.targetVersion} scope=${p.scope}")
      is PipelineProgress.IterationStage -> mark(context, "iterate_stage", "${p.eventId} ${p.phase} ${p.detail}")
      is PipelineProgress.IterationDone -> mark(context, "iterate_done", "${p.eventId} v${p.targetVersion} ${p.outputPath} ${p.changeSummary}")
      is PipelineProgress.IterationFailed -> mark(context, "iterate_failed", "${p.eventId} ${p.message}")
      PipelineProgress.AllDone -> mark(context, "all_done", "")
      is PipelineProgress.Failed -> mark(context, "failed", p.message)
    }
  }
}
