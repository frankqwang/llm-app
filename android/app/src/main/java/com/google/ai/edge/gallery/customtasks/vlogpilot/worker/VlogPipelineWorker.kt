/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * CoroutineWorker host for the orchestrator. Runs as a foreground service so
 * Android doesn't kill it during the long Gemma 4 inference. Progress is
 * published to a Hilt singleton (PipelineEventBus) the UI observes.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.ai.edge.gallery.customtasks.vlogpilot.VlogPilotTask
import com.google.ai.edge.gallery.data.Model

class VlogPipelineWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

  override suspend fun doWork(): androidx.work.ListenableWorker.Result {
    setForeground(initialForegroundInfo())
    val gemma = buildGemmaModel()
    val orch = PipelineOrchestrator(applicationContext, gemma)
    return try {
      orch.run(windowDays = 30) { progress ->
        PipelineEventBus.publish(progress)
        setForeground(foregroundFor(progress))
      }
      androidx.work.ListenableWorker.Result.success()
    } catch (t: Throwable) {
      PipelineEventBus.publish(PipelineProgress.Failed(t.message ?: t::class.java.simpleName))
      androidx.work.ListenableWorker.Result.failure()
    }
  }

  // ---- notification + foreground ----

  private fun initialForegroundInfo(): ForegroundInfo {
    ensureChannel(applicationContext)
    return ForegroundInfo(NOTIF_ID, buildNotif("VlogPilot 启动…"))
  }

  private fun foregroundFor(p: PipelineProgress): ForegroundInfo {
    val text = when (p) {
      is PipelineProgress.DownloadingModels -> "下载模型 ${p.percent}% — ${p.label}"
      PipelineProgress.Ingesting -> "扫描相册…"
      is PipelineProgress.IngestDone -> "${p.assetCount} 张素材 / ${p.eventCount} 个事件"
      is PipelineProgress.Perceiving -> "感知 ${p.current}/${p.total}"
      is PipelineProgress.EventStart -> "事件 ${p.index}/${p.total}（${p.eventId}）"
      is PipelineProgress.EventStage -> "${p.eventId}: ${p.stage}"
      is PipelineProgress.EventDone -> "${p.eventId}: 完成"
      is PipelineProgress.EventFailed -> "${p.eventId}: 失败"
      PipelineProgress.AllDone -> "全部完成"
      is PipelineProgress.Failed -> "出错: ${p.message}"
    }
    return ForegroundInfo(NOTIF_ID, buildNotif(text))
  }

  private fun buildNotif(text: String): Notification {
    val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
      .setContentTitle("VlogPilot")
      .setContentText(text)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
    return builder.build()
  }

  private fun buildGemmaModel(): Model = Model(
    name = "gemma-4-E2B-it",
    displayName = "Gemma 4 E2B (multimodal)",
    url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
    downloadFileName = "gemma-4-E2B-it.litertlm",
    sizeInBytes = 2_590_000_000L,
    version = "1",
    isLlm = true,
    bestForTaskIds = listOf(VlogPilotTask.TASK_ID),
  )

  companion object {
    const val WORK_NAME = "vlog_pilot_pipeline"
    private const val NOTIF_ID = 24601
    private const val CHANNEL_ID = "vlog_pilot_progress"

    fun ensureChannel(context: Context) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      val mgr = context.getSystemService(NotificationManager::class.java) ?: return
      if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
      mgr.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "VlogPilot pipeline", NotificationManager.IMPORTANCE_LOW)
          .apply { description = "Long-running on-device vlog generation progress." }
      )
    }
  }
}
