/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Bridges VlogPilotViewModel → VlogPipelineWorker. Two layers:
 *
 *  (1) Process-local: a @Volatile var `resolvedModel` holding the live Model
 *      object. Fast path; the Worker reads this directly when it runs in the
 *      same process the ViewModel was alive in (i.e. >95% of cases).
 *
 *  (2) Disk-backed: SharedPreferences holds the model NAME of the last
 *      enqueued pipeline. Survives process death (Android OOM kills the
 *      foreground service when memory pressure hits — Gemma 4 + ffmpeg can
 *      easily push 3 GB). When the Worker restarts after a kill and finds
 *      `resolvedModel == null`, it can at least surface a precise error
 *      ("pipeline interrupted, please tap Generate again") instead of the
 *      generic "no model" message.
 *
 *  The pragmatic compromise: in-process the static path is fine; on restart
 *  we don't try to magically reconstruct the Model (that needs the gallery's
 *  full ModelManagerViewModel allowlist + import sharedprefs), we just fail
 *  loudly enough that the user knows what to do.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.runtime

import android.content.Context
import com.google.ai.edge.gallery.data.Model

object VlogPilotModelRegistry {

  private const val PREFS_NAME = "vlog_pilot_runtime"
  private const val KEY_MODEL_NAME = "last_enqueued_model_name"

  /** Live Model used by the Worker on the fast path. */
  @Volatile var resolvedModel: Model? = null

  fun stash(context: Context, model: Model) {
    resolvedModel = model
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_MODEL_NAME, model.name)
      .apply()
  }

  /** Read the disk-persisted last-enqueued model name. Returns null if no
   *  pipeline was ever started, or if the prefs file was wiped. */
  fun lastEnqueuedModelName(context: Context): String? =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getString(KEY_MODEL_NAME, null)
}
