/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Sealed-class progress events emitted by the orchestrator. ViewModel turns
 * these into UI state; Worker turns them into foreground-notification text.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

sealed interface PipelineProgress {
  data class DownloadingModels(val percent: Int, val label: String) : PipelineProgress
  data object Ingesting : PipelineProgress
  data class ScoutingEvents(
    val currentEvent: Int,
    val totalEvents: Int,
    val eventId: String,
    val currentPage: Int,
    val totalPages: Int,
    val cacheHit: Boolean = false,
  ) : PipelineProgress
  data class SelectingEvents(val candidateCount: Int, val selectedCount: Int, val detail: String) : PipelineProgress
  data class IngestDone(val assetCount: Int, val eventCount: Int) : PipelineProgress
  data class Perceiving(
    val current: Int,
    val total: Int,
    val assetName: String = "",
    val mediaType: String = "",
    val cacheHit: Boolean = false,
  ) : PipelineProgress
  /** VLM tagging pass: Gemma reads each thumbnail / video-frame sheet and writes semantic JSON. */
  data class Annotating(
    val current: Int,
    val total: Int,
    val assetName: String = "",
    val mediaType: String = "",
    val phase: String = "done", // start / done / skipped
    val elapsedMs: Long = 0L,
  ) : PipelineProgress
  data class AnnotationDone(val annotated: Int, val total: Int, val elapsedMs: Long) : PipelineProgress
  data class EventStart(val eventId: String, val index: Int, val total: Int) : PipelineProgress
  data class EventStage(val eventId: String, val stage: String, val detail: String = "") : PipelineProgress
  data class EventDone(val eventId: String, val outputPath: String) : PipelineProgress
  data class EventFailed(val eventId: String, val message: String) : PipelineProgress
  /** Iteration started — orchestrator is about to apply user feedback to an existing event. */
  data class IterationStart(
    val eventId: String,
    val baseVersion: Int,
    val targetVersion: Int,
    val scope: String,            // "render_only" / "shot_level" / "global" / "mixed"
  ) : PipelineProgress
  /** Iteration progress — phase one of: parsing / rebuilding / rendering. */
  data class IterationStage(
    val eventId: String,
    val phase: String,
    val detail: String = "",
  ) : PipelineProgress
  data class IterationDone(
    val eventId: String,
    val targetVersion: Int,
    val outputPath: String,
    val changeSummary: String,
  ) : PipelineProgress
  data class IterationFailed(val eventId: String, val message: String) : PipelineProgress
  data object AllDone : PipelineProgress
  data class Failed(val message: String) : PipelineProgress
}
