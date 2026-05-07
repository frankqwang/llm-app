/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Process-wide hot stream of pipeline progress events. The worker publishes;
 * any composable / VM observes. SharedFlow with replay=1 so a late observer
 * still gets the most recent event.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object PipelineEventBus {

  private val _flow = MutableSharedFlow<PipelineProgress>(
    replay = 1,
    extraBufferCapacity = 64,
  )
  val flow: SharedFlow<PipelineProgress> = _flow.asSharedFlow()

  suspend fun publish(progress: PipelineProgress) {
    _flow.emit(progress)
  }
}
