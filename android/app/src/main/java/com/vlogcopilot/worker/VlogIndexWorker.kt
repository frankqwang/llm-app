/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Background asset indexing for the album page. It only updates perception/VLM
 * cache and never creates stories or renders videos, so indexing cannot be
 * mistaken for "making".
 */
package com.vlogcopilot.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vlogcopilot.agents.AgentRuntime
import com.vlogcopilot.agents.VlmAnnotator
import com.vlogcopilot.ingest.PhotoIngest
import com.vlogcopilot.perception.MediaLoader
import com.vlogcopilot.perception.PerceptionCache
import com.vlogcopilot.perception.PerceptionEngine
import com.vlogcopilot.pipeline.VideoFrameSheetBuilder
import com.vlogcopilot.runtime.PowerPacer
import com.vlogcopilot.runtime.VlogCopilotModelRegistry
import com.vlogcopilot.schemas.Asset
import com.vlogcopilot.schemas.MediaType
import com.vlogcopilot.schemas.Perception
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VlogIndexWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    PowerPacer.applyBackgroundThreadPriority()
    val ids = inputData.getString(KEY_ASSET_IDS)
      ?.split(',')
      ?.map { it.trim() }
      ?.filter { it.isNotBlank() }
      ?.toSet()
      .orEmpty()
    val singleAssetRequest = ids.size == 1
    val forceAnnotation = inputData.getBoolean(KEY_FORCE_ANNOTATION, false)
    val windowDays = inputData.getInt(KEY_WINDOW_DAYS, 0)
    val assets = PhotoIngest.loadRecent(
      context = applicationContext,
      windowDays = windowDays,
      filter = PhotoIngest.Filter(
        cameraOnly = false,
        readExif = false,
        maxVideoSizeBytes = Long.MAX_VALUE,
        maxImageSizeBytes = Long.MAX_VALUE,
        minImageSizeBytes = 1L,
      ),
    ).let { loaded ->
      if (ids.isEmpty()) loaded else loaded.filter { it.id in ids }
    }
    if (assets.isEmpty()) return@withContext Result.success()

    val model = VlogCopilotModelRegistry.resolve(applicationContext)
    val agent = model?.let { AgentRuntime(applicationContext, it) }
    try {
      agent?.ensureInitialized()
      val annotator = agent?.let(::VlmAnnotator)
      var annotatedCount = 0
      PerceptionEngine(applicationContext).use { engine ->
        assets.forEach { asset ->
          val base = PerceptionCache.get(applicationContext, asset)
            ?: PerceptionCache.get(applicationContext, asset.id)
            ?: engine.analyze(asset)
          val enriched = if (annotator != null && (forceAnnotation || !hasSemanticAnnotation(base))) {
            annotate(asset, base, annotator)
          } else {
            base
          }
          if (hasSemanticAnnotation(enriched)) {
            annotatedCount += 1
          }
          PerceptionCache.put(applicationContext, asset, enriched)
        }
      }
      if (singleAssetRequest) {
        postCompletionNotification(assets.first(), annotatedCount > 0, model != null)
      }
      Result.success()
    } finally {
      agent?.close()
    }
  }

  private suspend fun annotate(
    asset: Asset,
    base: Perception,
    annotator: VlmAnnotator,
  ): Perception {
    val annotation = if (asset.mediaType == MediaType.IMAGE) {
      val thumb = MediaLoader.loadImage(applicationContext, asset, maxSide = 512) ?: return base
      try {
        annotator.annotate(thumb, asset.mediaType.name.lowercase())
      } finally {
        runCatching { thumb.recycle() }
      }
    } else {
      val sheet = VideoFrameSheetBuilder.build(applicationContext, asset, base.sceneCuts)
      if (sheet != null) {
        try {
          annotator.annotateVideo(sheet.bitmap, sheet.frameTimestampsSec, asset.mediaType.name.lowercase())
        } finally {
          runCatching { sheet.bitmap.recycle() }
        }
      } else {
        val thumb = MediaLoader.loadImage(applicationContext, asset, maxSide = 512) ?: return base
        try {
          annotator.annotate(thumb, asset.mediaType.name.lowercase())
        } finally {
          runCatching { thumb.recycle() }
        }
      }
    }
    return base.copy(vlmTags = annotation.tags, videoInsight = annotation.videoInsight)
  }

  private fun hasSemanticAnnotation(perception: Perception): Boolean =
    perception.vlmTags.scene.isNotBlank() ||
      perception.vlmTags.subjects.isNotEmpty() ||
      perception.vlmTags.action.isNotBlank() ||
      perception.vlmTags.mood.isNotBlank() ||
      perception.vlmTags.salient.isNotBlank() ||
      perception.vlmTags.visualDescription.isNotBlank() ||
      perception.videoInsight.summary.isNotBlank() ||
      perception.videoInsight.visualDescription.isNotBlank() ||
      perception.videoInsight.actionArc.isNotBlank()

  private fun postCompletionNotification(asset: Asset, annotated: Boolean, modelAvailable: Boolean) {
    ensureIndexChannel()
    val mgr = applicationContext.getSystemService(NotificationManager::class.java) ?: return
    val text = when {
      !modelAvailable -> "没有可用模型，只完成基础检测"
      annotated -> asset.displayName.ifBlank { "素材已标注" }
      else -> "已完成基础检测，未得到语义标签"
    }
    val notification = NotificationCompat.Builder(applicationContext, INDEX_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setContentTitle("素材标注完成")
      .setContentText(text)
      .setAutoCancel(true)
      .build()
    runCatching {
      mgr.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
  }

  private fun ensureIndexChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val mgr = applicationContext.getSystemService(NotificationManager::class.java) ?: return
    if (mgr.getNotificationChannel(INDEX_CHANNEL_ID) != null) return
    mgr.createNotificationChannel(
      NotificationChannel(
        INDEX_CHANNEL_ID,
        "VlogCopilot asset annotation",
        NotificationManager.IMPORTANCE_DEFAULT,
      ).apply {
        description = "Single asset annotation completion."
      },
    )
  }

  companion object {
    const val WORK_NAME = "vlog_copilot_asset_index"
    const val BACKGROUND_WORK_NAME = "vlog_copilot_asset_index_background"
    private const val SINGLE_WORK_PREFIX = "vlog_copilot_asset_index_single_"
    private const val INDEX_CHANNEL_ID = "vlog_copilot_asset_annotation"
    private const val KEY_ASSET_IDS = "vlog_copilot.index.asset_ids"
    private const val KEY_WINDOW_DAYS = "vlog_copilot.index.window_days"
    private const val KEY_FORCE_ANNOTATION = "vlog_copilot.index.force_annotation"

    fun singleAssetWorkName(assetId: String): String =
      "$SINGLE_WORK_PREFIX${assetId.hashCode()}"

    fun inputData(
      assetIds: List<String> = emptyList(),
      windowDays: Int = 0,
      forceAnnotation: Boolean = false,
    ): Data =
      workDataOf(
        KEY_ASSET_IDS to assetIds.joinToString(","),
        KEY_WINDOW_DAYS to windowDays,
        KEY_FORCE_ANNOTATION to forceAnnotation,
      )
  }
}
