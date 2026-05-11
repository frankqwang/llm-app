/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Product-level operation state. This keeps UI labels, enabled states, and
 * background task semantics aligned instead of guessing from a single
 * PipelineState.Running flag.
 */
package com.vlogcopilot

internal enum class VlogCopilotOperation {
  Idle,
  LoadingAssets,
  RefreshingStories,
  MakingVideos,
  OptimizingVideo,
  Error,
}

internal val VlogCopilotOperation.blocksPrimaryPipeline: Boolean
  get() = this == VlogCopilotOperation.RefreshingStories || this == VlogCopilotOperation.MakingVideos

internal fun classifyVlogCopilotOperation(
  state: PipelineState,
  progress: ProgressSnapshot,
  activeIteration: IterationSnapshot?,
  albumLoading: Boolean,
): VlogCopilotOperation {
  if (state is PipelineState.Error) return VlogCopilotOperation.Error
  if (state is PipelineState.Scanning || isStoryRefreshStage(progress.stage)) {
    return VlogCopilotOperation.RefreshingStories
  }
  if (state is PipelineState.Running && !progress.stage.startsWith("iterate")) {
    return VlogCopilotOperation.MakingVideos
  }
  if (activeIteration != null && activeIteration.phase != "done" && activeIteration.phase != "failed") {
    return VlogCopilotOperation.OptimizingVideo
  }
  if (albumLoading) return VlogCopilotOperation.LoadingAssets
  return VlogCopilotOperation.Idle
}

internal fun isStoryRefreshStage(stage: String): Boolean =
  stage == "candidate_refresh" ||
    stage == "event_scout" ||
    stage == "event_select" ||
    stage == "scan" ||
    stage == "segment"
