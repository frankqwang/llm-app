/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Background asset indexing for the album page. It only updates perception/VLM
 * cache and never creates stories or renders videos, so indexing cannot be
 * mistaken for "making".
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.AgentRuntime
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.VlmAnnotator
import com.google.ai.edge.gallery.customtasks.vlogpilot.ingest.PhotoIngest
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.MediaLoader
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.PerceptionCache
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.PerceptionEngine
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.VideoFrameSheetBuilder
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.PowerPacer
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.VlogPilotModelRegistry
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
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

    val model = VlogPilotModelRegistry.resolve(applicationContext)
    val agent = model?.let { AgentRuntime(applicationContext, it) }
    try {
      agent?.ensureInitialized()
      val annotator = agent?.let(::VlmAnnotator)
      PerceptionEngine(applicationContext).use { engine ->
        assets.forEach { asset ->
          val base = PerceptionCache.get(applicationContext, asset) ?: engine.analyze(asset)
          val enriched = if (annotator != null && base.vlmTags.scene.isBlank()) {
            annotate(asset, base, annotator)
          } else {
            base
          }
          PerceptionCache.put(applicationContext, asset, enriched)
        }
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

  companion object {
    const val WORK_NAME = "vlog_pilot_asset_index"
    const val BACKGROUND_WORK_NAME = "vlog_pilot_asset_index_background"
    private const val KEY_ASSET_IDS = "vlog_pilot.index.asset_ids"
    private const val KEY_WINDOW_DAYS = "vlog_pilot.index.window_days"

    fun inputData(assetIds: List<String> = emptyList(), windowDays: Int = 0): Data =
      workDataOf(
        KEY_ASSET_IDS to assetIds.joinToString(","),
        KEY_WINDOW_DAYS to windowDays,
      )
  }
}
