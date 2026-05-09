/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Product-level operation state. This keeps UI labels, enabled states, and
 * background task semantics aligned instead of guessing from a single
 * PipelineState.Running flag.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

internal enum class VlogPilotOperation {
  Idle,
  LoadingAssets,
  RefreshingStories,
  MakingVideos,
  OptimizingVideo,
  Error,
}

internal val VlogPilotOperation.blocksPrimaryPipeline: Boolean
  get() = this == VlogPilotOperation.RefreshingStories || this == VlogPilotOperation.MakingVideos

internal fun classifyVlogPilotOperation(
  state: PipelineState,
  progress: ProgressSnapshot,
  activeIteration: IterationSnapshot?,
  albumLoading: Boolean,
): VlogPilotOperation {
  if (state is PipelineState.Error) return VlogPilotOperation.Error
  if (state is PipelineState.Scanning || isStoryRefreshStage(progress.stage)) {
    return VlogPilotOperation.RefreshingStories
  }
  if (state is PipelineState.Running && !progress.stage.startsWith("iterate")) {
    return VlogPilotOperation.MakingVideos
  }
  if (activeIteration != null && activeIteration.phase != "done" && activeIteration.phase != "failed") {
    return VlogPilotOperation.OptimizingVideo
  }
  if (albumLoading) return VlogPilotOperation.LoadingAssets
  return VlogPilotOperation.Idle
}

internal fun isStoryRefreshStage(stage: String): Boolean =
  stage == "candidate_refresh" ||
    stage == "event_scout" ||
    stage == "event_select" ||
    stage == "scan" ||
    stage == "segment"
