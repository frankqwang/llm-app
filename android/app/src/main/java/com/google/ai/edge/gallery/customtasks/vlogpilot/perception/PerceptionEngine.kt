/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Orchestrates step1_perceive on-device: for each Asset, run quality, NSFW,
 * YOLO, face landmark+embedding+cluster, scene cuts (video only), and
 * MobileCLIP image embedding. Models that fail to load (file missing) are
 * silently skipped — the resulting Perception is degraded but the pipeline
 * keeps moving so a missing asset bundle doesn't brick the whole run.
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

  private val yolo = YoloDetector(context, "models/yolo26n_int8.tflite")
  private val faceDetector = FaceDetector(context)
  private val faceEmbedder = FaceEmbedder(context)
  private val clip = ClipEmbedder(context)
  private val nsfw = NsfwClassifier(context)
  private val clusterer = FaceClusterer()

  fun analyze(asset: Asset): Perception {
    val cover: Bitmap = MediaLoader.loadImage(context, asset, maxSide = 1024)
      ?: run {
        Log.w(TAG, "loadImage failed: ${asset.contentUri}")
        return Perception(assetId = asset.id, isJunk = true, junkReason = "decode_failed")
      }

    val q = ImageQuality.measure(cover)
    val nsfwScore = nsfw.score(cover)

    val faces = faceDetector.detect(cover).map { hit ->
      val crop = BitmapPrep.cropNorm(cover, hit.xn, hit.yn, hit.wn, hit.hn, square = true)
      val emb = if (crop != null) faceEmbedder.embed(crop) else FloatArray(0)
      val pid = if (emb.isNotEmpty()) clusterer.assign(emb) else FaceClusterer.UNK
      val faceArea = hit.wn * hit.hn
      FaceBox(
        x = hit.xn, y = hit.yn, w = hit.wn, h = hit.hn,
        embedding = emb.toList(),
        personId = pid,
        quality = (q.sharpness * faceArea).coerceIn(0f, 1f),
      )
    }

    val yoloObjs = yolo.detect(cover)
    val sceneClass = yoloObjs.maxByOrNull { it.conf }?.cls ?: "unknown"
    val clipEmb = clip.embedImage(cover)

    val sceneCuts = if (asset.mediaType != MediaType.IMAGE) {
      try { SceneCutDetector.detect(context, asset) } catch (_: Throwable) { emptyList() }
    } else emptyList()
    val fps = if (asset.mediaType != MediaType.IMAGE && asset.durationMs > 0) {
      // We don't have a cheap FPS probe without ffprobe — leave 0 unless a renderer fills it.
      0f
    } else 0f

    val (junk, reason) = decideJunk(q, nsfwScore)

    return Perception(
      assetId = asset.id,
      isJunk = junk,
      junkReason = reason,
      sharpness = q.sharpness,
      brightness = q.brightness,
      faces = faces,
      yoloObjs = yoloObjs,
      sceneClass = sceneClass,
      clipEmbedding = clipEmb.toList(),
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

  /** CLIP text embedding bridge so editor recall can score "visual_requirements" prompts. */
  fun embedText(text: String): FloatArray = clip.embedText(text)

  override fun close() {
    yolo.close()
    faceDetector.close()
    faceEmbedder.close()
    clip.close()
    nsfw.close()
  }

  companion object { private const val TAG = "PerceptionEngine" }
}
