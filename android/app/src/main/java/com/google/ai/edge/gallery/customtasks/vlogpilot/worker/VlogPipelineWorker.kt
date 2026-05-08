/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * CoroutineWorker host for the orchestrator. Runs as a foreground service so
 * Android doesn't kill it during long on-device VLM inference.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.PowerPacer
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.VlogPilotRunConfig
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.VlogPilotModelRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

class VlogPipelineWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

  /** WorkManager calls this BEFORE doWork() to attach FGS metadata to the
   *  underlying JobScheduler slot. Without it, setForeground() inside doWork
   *  only posts a notification but the JobScheduler job stays "background",
   *  and OEM background-task killers (vivo iManager BTM, MIUI, ColorOS) tear
   *  the job down within ~2s — causing a worker restart loop. */
  override suspend fun getForegroundInfo(): ForegroundInfo = initialForegroundInfo()

  override suspend fun doWork(): androidx.work.ListenableWorker.Result {
    val runId = RUN_SEQ.incrementAndGet()
    val iterationModeCheck = inputData.getString(KEY_ITERATION_EVENT_ID) != null
    val curationModeCheck = inputData.getString(KEY_CURATION_REQUEST_ID) != null
    // Iteration mode (RENDER_ONLY in Milestone A) doesn't load Gemma, so it's
    // safe to run alongside the full pipeline. We only block duplicate FULL
    // pipelines (not iterations) since multiple Gemma engines blow up memory.
    // Curation mode DOES load Gemma → it shares the ACTIVE lock with full mode.
    val needsActiveLock = !iterationModeCheck
    if (needsActiveLock && !ACTIVE.compareAndSet(false, true)) {
      StateBreadcrumb.mark(applicationContext, "duplicate_worker", "run=$runId ignored; another full/curate pipeline is active")
      return androidx.work.ListenableWorker.Result.success()
    }
    val mode = when {
      iterationModeCheck -> "iterate"
      curationModeCheck -> "curate"
      else -> "full"
    }
    StateBreadcrumb.mark(applicationContext, "worker_start", "run=$runId id=$id attempt=$runAttemptCount mode=$mode")
    val runConfig = VlogPilotRunConfig.load(applicationContext)
    PowerPacer.setProfile(runConfig.powerProfile)
    PowerPacer.applyBackgroundThreadPriority()
    setForeground(initialForegroundInfo())
    val iterationEventId = inputData.getString(KEY_ITERATION_EVENT_ID)
    val curationRequestId = inputData.getString(KEY_CURATION_REQUEST_ID)
    return try {
      val gemma = VlogPilotModelRegistry.resolve(applicationContext) ?: run {
        val lastName = VlogPilotModelRegistry.lastEnqueuedModelName(applicationContext)
        val msg = if (lastName != null) {
          "Pipeline interrupted and model '$lastName' could not be restored. Open the app and tap Generate again."
        } else {
          "No LLM imported yet. Open Models and import Gemma 4 or another image-capable LLM first."
        }
        StateBreadcrumb.mark(applicationContext, "model_missing", msg)
        PipelineEventBus.publish(PipelineProgress.Failed(msg))
        return androidx.work.ListenableWorker.Result.failure()
      }
      val orch = PipelineOrchestrator(applicationContext, gemma)
      val onProgress: suspend (PipelineProgress) -> Unit = { progress ->
        try {
          PipelineEventBus.publish(progress)
        } catch (t: Throwable) {
          if (t is CancellationException) throw t
          StateBreadcrumb.mark(applicationContext, "event_bus_error", "${t::class.java.simpleName}: ${t.message}")
        }
        try {
          setForeground(foregroundFor(progress))
        } catch (t: Throwable) {
          if (t is CancellationException) throw t
          StateBreadcrumb.mark(applicationContext, "foreground_error", "${t::class.java.simpleName}: ${t.message}")
        }
      }
      if (iterationEventId != null) {
        // Iteration mode: read staged feedback, apply, archive prior MP4, write new.
        StateBreadcrumb.mark(applicationContext, "worker_iterate", "event=$iterationEventId")
        val feedback = IterationStore.loadPending(applicationContext, iterationEventId) ?: run {
          val msg = "no pending feedback for $iterationEventId"
          StateBreadcrumb.mark(applicationContext, "iterate_no_pending", msg)
          PipelineEventBus.publish(PipelineProgress.IterationFailed(iterationEventId, msg))
          return androidx.work.ListenableWorker.Result.failure()
        }
        orch.iterate(iterationEventId, feedback, onProgress)
      } else if (curationRequestId != null) {
        // User-curated story mode: read staged request, build event, parse intent,
        // run full Browser→Audience→Director→Editor→Critic→Render with overrides.
        StateBreadcrumb.mark(applicationContext, "worker_curate", "request=$curationRequestId")
        val request = CurationRequestStore.load(applicationContext, curationRequestId) ?: run {
          val msg = "no staged curation request for $curationRequestId"
          StateBreadcrumb.mark(applicationContext, "curate_no_request", msg)
          PipelineEventBus.publish(PipelineProgress.Failed(msg))
          return androidx.work.ListenableWorker.Result.failure()
        }
        orch.runFromCuration(request = request, runConfig = runConfig, onProgress = onProgress)
      } else {
        orch.run(windowDays = 30, runConfig = runConfig, onProgress = onProgress)
      }
      StateBreadcrumb.mark(applicationContext, "worker_success", "run=$runId mode=${if (iterationEventId != null) "iterate" else "full"}")
      androidx.work.ListenableWorker.Result.success()
    } catch (t: CancellationException) {
      StateBreadcrumb.mark(applicationContext, "worker_cancelled", "run=$runId ${t::class.java.simpleName}: ${t.message}")
      throw t
    } catch (t: Throwable) {
      StateBreadcrumb.mark(applicationContext, "worker_failed", "${t::class.java.simpleName}: ${t.message}")
      val failureMsg = t.message ?: t::class.java.simpleName
      val failureProgress: PipelineProgress = if (iterationEventId != null) {
        PipelineProgress.IterationFailed(iterationEventId, failureMsg)
      } else {
        PipelineProgress.Failed(failureMsg)
      }
      PipelineEventBus.publish(failureProgress)
      androidx.work.ListenableWorker.Result.failure()
    } finally {
      if (needsActiveLock) ACTIVE.set(false)
    }
  }

  private fun initialForegroundInfo(): ForegroundInfo {
    ensureChannel(applicationContext)
    return buildForeground(buildNotif("VlogPilot starting"))
  }

  private fun foregroundFor(p: PipelineProgress): ForegroundInfo {
    val text = when (p) {
      is PipelineProgress.DownloadingModels -> "download ${p.percent}% ${p.label}"
      PipelineProgress.Ingesting -> "scanning album"
      is PipelineProgress.ScoutingEvents -> "VLM scout ${p.currentEvent}/${p.totalEvents} page ${p.currentPage}/${p.totalPages}"
      is PipelineProgress.SelectingEvents -> "selecting events ${p.selectedCount}/${p.candidateCount}"
      is PipelineProgress.IngestDone -> "${p.assetCount} assets / ${p.eventCount} events"
      is PipelineProgress.Perceiving -> "perceive ${p.current}/${p.total} ${p.assetName.ifBlank { p.mediaType }}"
      is PipelineProgress.Annotating -> "${if (p.phase == "start") "annotating" else "annotated"} ${p.current}/${p.total} ${p.assetName.ifBlank { p.mediaType }}"
      is PipelineProgress.AnnotationDone -> "annotation done ${p.annotated}/${p.total}"
      is PipelineProgress.EventStart -> "event ${p.index}/${p.total}: ${p.eventId}"
      is PipelineProgress.EventStage -> "${p.eventId}: ${p.stage}"
      is PipelineProgress.EventDone -> "${p.eventId}: done"
      is PipelineProgress.EventFailed -> "${p.eventId}: failed"
      is PipelineProgress.IterationStart -> "iterate ${p.eventId}: v${p.baseVersion}→v${p.targetVersion} (${p.scope})"
      is PipelineProgress.IterationStage -> "iterate ${p.eventId}: ${p.phase}"
      is PipelineProgress.IterationDone -> "iterate ${p.eventId}: v${p.targetVersion} done"
      is PipelineProgress.IterationFailed -> "iterate ${p.eventId}: failed"
      PipelineProgress.AllDone -> "all done"
      is PipelineProgress.Failed -> "error: ${p.message}"
    }
    return buildForeground(buildNotif(text))
  }

  private fun buildForeground(notif: Notification): ForegroundInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(NOTIF_ID, notif)
    }

  private fun buildNotif(text: String): Notification =
    NotificationCompat.Builder(applicationContext, CHANNEL_ID)
      .setContentTitle("VlogPilot")
      .setContentText(text)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()

  companion object {
    const val WORK_NAME = "vlog_pilot_pipeline"
    /** Iteration WorkRequests reuse the same Worker class but with a unique
     *  WORK_NAME suffix per event so iterations on different events don't
     *  cancel each other (KEEP policy). */
    const val ITERATION_WORK_PREFIX = "vlog_pilot_iterate_"
    /** Curation WorkRequests are uniquely named per requestId; they share the
     *  ACTIVE lock with full pipeline since both load Gemma. */
    const val CURATION_WORK_PREFIX = "vlog_pilot_curate_"
    /** inputData key — when present, the Worker runs iterate mode using
     *  IterationStore.loadPending(context, eventId) instead of full run(). */
    const val KEY_ITERATION_EVENT_ID = "vlog_pilot.iteration_event_id"
    /** inputData key — when present, the Worker runs runFromCuration on the
     *  staged UserCurationRequest at CurationRequestStore.load(context, requestId). */
    const val KEY_CURATION_REQUEST_ID = "vlog_pilot.curation_request_id"
    private const val NOTIF_ID = 24601
    private const val CHANNEL_ID = "vlog_pilot_progress"
    private val ACTIVE = AtomicBoolean(false)
    private val RUN_SEQ = AtomicLong(0)

    fun iterationInputData(eventId: String): Data =
      workDataOf(KEY_ITERATION_EVENT_ID to eventId)

    fun iterationWorkName(eventId: String): String =
      "$ITERATION_WORK_PREFIX$eventId"

    fun curationInputData(requestId: String): Data =
      workDataOf(KEY_CURATION_REQUEST_ID to requestId)

    fun curationWorkName(requestId: String): String =
      "$CURATION_WORK_PREFIX$requestId"

    fun ensureChannel(context: Context) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      val mgr = context.getSystemService(NotificationManager::class.java) ?: return
      if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
      mgr.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "VlogPilot pipeline", NotificationManager.IMPORTANCE_LOW)
          .apply { description = "Long-running on-device vlog generation progress." },
      )
    }
  }
}
