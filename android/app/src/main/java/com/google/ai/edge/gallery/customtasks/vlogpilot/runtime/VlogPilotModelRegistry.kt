/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Bridges VlogPilotViewModel → VlogPipelineWorker without going through Hilt
 * worker injection. The ViewModel resolves the Model from the gallery's
 * already-loaded Tasks (whose models came from ModelAllowlist + the existing
 * download flow), stashes it here, then enqueues the worker. The worker reads
 * the same reference back. Both run in the same process, so a static holder
 * is safe — there is exactly one pipeline run at a time, gated by
 * WorkManager's UniqueWork policy.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.runtime

import com.google.ai.edge.gallery.data.Model

object VlogPilotModelRegistry {
  /** Set by the ViewModel right before enqueueWork; read by the Worker in doWork. */
  @Volatile var resolvedModel: Model? = null
}
