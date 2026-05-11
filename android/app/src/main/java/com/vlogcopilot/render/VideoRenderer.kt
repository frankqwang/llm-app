/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Top-level renderer. Takes a Timeline + the source Assets, produces the
 * final candidate MP4. Sequential: render each shot, then composite + BGM.
 *
 * After a successful render the timeline's per-shot durationSec is overwritten
 * with the actual probed value so step6 critic can see ground truth.
 */
package com.vlogcopilot.render

import android.content.Context
import android.util.Log
import com.vlogcopilot.schemas.Asset
import com.vlogcopilot.schemas.Timeline
import java.io.File

class VideoRenderer(private val context: Context) {

  companion object {
    private const val TAG = "VideoRenderer"
    const val CANDIDATES_DIR = "candidates"
    const val FONT_FILE = "fonts/SourceHanSansSC-Bold.otf"

    /** Unpack the bundled CJK font into filesDir on first use; null if missing. */
    fun ensureFont(context: Context): File? {
      val target = File(context.filesDir, FONT_FILE)
      if (target.isFile) return target
      return try {
        target.parentFile?.mkdirs()
        context.assets.open(FONT_FILE).use { input ->
          target.outputStream().use { input.copyTo(it) }
        }
        target
      } catch (_: Throwable) {
        Log.w(TAG, "font asset missing — captions will use ffmpeg's default font (may render boxes for CJK)")
        null
      }
    }
  }

  data class RenderOutput(val outputPath: String, val timeline: Timeline)

  fun render(timeline: Timeline, assets: Map<String, Asset>, tone: String): RenderOutput? {
    val candidatesDir = File(context.filesDir, CANDIDATES_DIR).apply { mkdirs() }
    VersionArchive.archivePrevious(candidatesDir, timeline.eventId)
    val outFile = File(candidatesDir, "${timeline.eventId}.mp4")

    val workDir = File(context.cacheDir, "vlog_intermediate/${timeline.eventId}").apply {
      deleteRecursively(); mkdirs()
    }
    val fontPath = ensureFont(context)?.absolutePath

    // Render each shot
    val intermediates = mutableListOf<CompositeRenderer.ShotInput>()
    val updatedShots = mutableListOf<com.vlogcopilot.schemas.ShotSpec>()
    for (spec in timeline.shots) {
      val asset = assets[spec.assetId]
      if (asset == null) {
        Log.w(TAG, "asset missing for shot ${spec.order}")
        continue
      }
      val cacheEntry = ShotRenderCache.entry(context, asset, spec, fontPath != null)
      val r = ShotRenderCache.load(cacheEntry)
        ?: ShotRenderer.render(context, asset, spec, fontPath, workDir)
          ?.let { ShotRenderCache.store(cacheEntry, it) }
      if (r == null) {
        Log.w(TAG, "shot ${spec.order} render failed")
        continue
      }
      intermediates += CompositeRenderer.ShotInput(r.path, r.actualDurationSec, spec.transitionIn)
      updatedShots += spec.copy(durationSec = r.actualDurationSec)
    }
    if (intermediates.isEmpty()) return null

    val bgmPath = BgmManager.pickFor(context, tone)
    val ok = CompositeRenderer.composite(intermediates, bgmPath, outFile)
    if (!ok) return null

    return RenderOutput(outFile.absolutePath, timeline.copy(shots = updatedShots, final = true))
  }

}
