/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Cheap, no-LLM perception pass: per-asset junk filter (sharpness + brightness
 * + NSFW) + face bbox via MediaPipe FaceLandmarker. Runs BEFORE Gemma 4 boots
 * so it doesn't compete with the LLM for memory.
 *
 * Semantic understanding (scene, subjects, action, mood) is filled in later
 * by VlmAnnotator after Gemma is up. Recall scoring composes the cheap
 * signals + VLM tags from the resulting Perception JSON.
 *
 * Models that fail to load (file missing) are silently skipped — the
 * resulting Perception is degraded but the pipeline keeps moving so a
 * missing asset bundle doesn't brick the whole run.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.FaceBox
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import java.io.Closeable

class PerceptionEngine(
  private val context: Context,
  private val nsfwThreshold: Float = 0.6f,
  private val sharpnessJunkThresh: Float = 0.05f,
  private val brightnessJunkLow: Float = 0.05f,
  private val brightnessJunkHigh: Float = 0.97f,
) : Closeable {

  private val faceDetector = FaceDetector(context)
  private val nsfw = NsfwClassifier(context)

  /**
   * Cheap no-LLM perception. Returns a Perception with sharpness, brightness,
   * faces, nsfw, sceneCuts (video only) populated. `vlmTags` is left empty —
   * the VLM annotation pass fills it in after Gemma 4 boots.
   */
  fun analyze(asset: Asset): Perception {
    val cover: Bitmap = MediaLoader.loadImage(context, asset, maxSide = 1024)
      ?: run {
        Log.w(TAG, "loadImage failed: ${asset.contentUri}")
        return Perception(assetId = asset.id, isJunk = true, junkReason = "decode_failed")
      }

    val q = ImageQuality.measure(cover)
    val nsfwScore = nsfw.score(cover)

    val faces = faceDetector.detect(cover).map { hit ->
      val faceArea = hit.wn * hit.hn
      FaceBox(
        x = hit.xn, y = hit.yn, w = hit.wn, h = hit.hn,
        quality = (q.sharpness * faceArea).coerceIn(0f, 1f),
      )
    }

    val sceneCuts = if (asset.mediaType != MediaType.IMAGE) {
      try { SceneCutDetector.detect(context, asset) } catch (_: Throwable) { emptyList() }
    } else emptyList()
    val fps = 0f  // not measured — render layer probes separately when needed

    val (junk, reason) = decideJunk(q, nsfwScore)

    // Free the cover bitmap before returning. After this point only the
    // VlmAnnotator phase needs a thumbnail (which it loads at smaller size
    // separately). Without recycling here, peak heap during a 100-asset run
    // climbs past 1 GB.
    cover.recycle()

    return Perception(
      assetId = asset.id,
      isJunk = junk,
      junkReason = reason,
      sharpness = q.sharpness,
      brightness = q.brightness,
      faces = faces,
      nsfwScore = nsfwScore,
      sceneCuts = sceneCuts,
      fps = fps,
    )
  }

  private fun decideJunk(q: ImageQuality.Quality, nsfwScore: Float): Pair<Boolean, String> = when {
    nsfwScore >= nsfwThreshold -> true to "nsfw"
    q.sharpness < sharpnessJunkThresh -> true to "blurry"
    q.brightness < brightnessJunkLow -> true to "too_dark"
    q.brightness > brightnessJunkHigh -> true to "too_bright"
    else -> false to ""
  }

  override fun close() {
    runCatching { faceDetector.close() }
    runCatching { nsfw.close() }
  }

  companion object { private const val TAG = "PerceptionEngine" }
}
