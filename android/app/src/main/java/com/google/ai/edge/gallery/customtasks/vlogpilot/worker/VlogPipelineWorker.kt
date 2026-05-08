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
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
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
    if (!ACTIVE.compareAndSet(false, true)) {
      StateBreadcrumb.mark(applicationContext, "duplicate_worker", "run=$runId ignored; another VlogPipelineWorker is active")
      return androidx.work.ListenableWorker.Result.success()
    }
    StateBreadcrumb.mark(applicationContext, "worker_start", "run=$runId id=$id attempt=$runAttemptCount")
    val runConfig = VlogPilotRunConfig.load(applicationContext)
    PowerPacer.setProfile(runConfig.powerProfile)
    PowerPacer.applyBackgroundThreadPriority()
    setForeground(initialForegroundInfo())
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
      orch.run(windowDays = 30, runConfig = runConfig) { progress ->
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
      StateBreadcrumb.mark(applicationContext, "worker_success", "run=$runId")
      androidx.work.ListenableWorker.Result.success()
    } catch (t: CancellationException) {
      StateBreadcrumb.mark(applicationContext, "worker_cancelled", "run=$runId ${t::class.java.simpleName}: ${t.message}")
      throw t
    } catch (t: Throwable) {
      StateBreadcrumb.mark(applicationContext, "worker_failed", "${t::class.java.simpleName}: ${t.message}")
      PipelineEventBus.publish(PipelineProgress.Failed(t.message ?: t::class.java.simpleName))
      androidx.work.ListenableWorker.Result.failure()
    } finally {
      ACTIVE.set(false)
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
      is PipelineProgress.SelectingEvents -> "selecting events ${p.selectedCount}/${p.candidateCount}"
      is PipelineProgress.IngestDone -> "${p.assetCount} assets / ${p.eventCount} events"
      is PipelineProgress.Perceiving -> "perceive ${p.current}/${p.total} ${p.assetName.ifBlank { p.mediaType }}"
      is PipelineProgress.Annotating -> "${if (p.phase == "start") "annotating" else "annotated"} ${p.current}/${p.total} ${p.assetName.ifBlank { p.mediaType }}"
      is PipelineProgress.AnnotationDone -> "annotation done ${p.annotated}/${p.total}"
      is PipelineProgress.EventStart -> "event ${p.index}/${p.total}: ${p.eventId}"
      is PipelineProgress.EventStage -> "${p.eventId}: ${p.stage}"
      is PipelineProgress.EventDone -> "${p.eventId}: done"
      is PipelineProgress.EventFailed -> "${p.eventId}: failed"
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
    private const val NOTIF_ID = 24601
    private const val CHANNEL_ID = "vlog_pilot_progress"
    private val ACTIVE = AtomicBoolean(false)
    private val RUN_SEQ = AtomicLong(0)

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
