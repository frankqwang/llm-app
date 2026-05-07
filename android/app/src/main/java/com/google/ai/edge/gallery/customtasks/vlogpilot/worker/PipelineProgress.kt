/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Sealed-class progress events emitted by the orchestrator. ViewModel turns
 * these into UI state; Worker turns them into Data for WorkManager.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

sealed interface PipelineProgress {
  data class DownloadingModels(val percent: Int, val label: String) : PipelineProgress
  data object Ingesting : PipelineProgress
  data class IngestDone(val assetCount: Int, val eventCount: Int) : PipelineProgress
  data class Perceiving(val current: Int, val total: Int) : PipelineProgress
  /** VLM tagging pass — Gemma 4 reads each thumbnail and writes scene/subjects/mood JSON. */
  data class Annotating(val current: Int, val total: Int) : PipelineProgress
  data class EventStart(val eventId: String, val index: Int, val total: Int) : PipelineProgress
  data class EventStage(val eventId: String, val stage: String) : PipelineProgress // browse/audience/director/editor/critic/render
  data class EventDone(val eventId: String, val outputPath: String) : PipelineProgress
  data class EventFailed(val eventId: String, val message: String) : PipelineProgress
  data object AllDone : PipelineProgress
  data class Failed(val message: String) : PipelineProgress
}
