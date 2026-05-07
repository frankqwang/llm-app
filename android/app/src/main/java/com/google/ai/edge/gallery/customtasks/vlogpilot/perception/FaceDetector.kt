/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * MediaPipe Face Landmarker → bounding boxes + 478 landmarks. The landmark
 * min/max gives the tight face box (more accurate than detector-only
 * fallback). Used for portrait-shot weighting and "face present?" signals;
 * face identity / clustering moved to the VLM annotator in v4.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class FaceDetector(
  context: Context,
  modelAssetPath: String = "models/face_landmarker.task",
  numFaces: Int = 5,
) : Closeable {

  data class Hit(
    val xn: Float, val yn: Float, val wn: Float, val hn: Float, // normalized bbox [0,1]
    val confidence: Float,
    val landmarks: List<Pair<Float, Float>>,                    // normalized 478 points
  )

  private val landmarker: FaceLandmarker? = run {
    // MediaPipe's native createFromOptions does NOT throw when the model is
    // missing — it SEGVs in strlen() before Kotlin gets a chance to catch.
    // Resolve to a ByteBuffer first (filesDir OTA download → bundled asset),
    // and only call MediaPipe once we have real bytes.
    val baseOpts = resolveBase(context, modelAssetPath) ?: run {
      Log.w(TAG, "$modelAssetPath not found in filesDir/models or assets — face detection disabled")
      return@run null
    }
    try {
      val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
        .setBaseOptions(baseOpts)
        .setRunningMode(RunningMode.IMAGE)
        .setNumFaces(numFaces)
        .build()
      FaceLandmarker.createFromOptions(context, opts)
    } catch (t: Throwable) {
      Log.w(TAG, "FaceLandmarker init failed", t)
      null
    }
  }

  private fun resolveBase(context: Context, modelAssetPath: String): BaseOptions? {
    // (1) OTA-downloaded copy under filesDir/models/<basename>
    val basename = File(modelAssetPath).name
    val ota = File(context.filesDir, "models/$basename")
    if (ota.isFile && ota.length() > 0) {
      val buffer: ByteBuffer = FileInputStream(ota).channel.use { ch ->
        ch.map(FileChannel.MapMode.READ_ONLY, 0, ota.length())
      }
      return BaseOptions.builder().setModelAssetBuffer(buffer).build()
    }
    // (2) Bundled asset path
    val bundled = try {
      context.assets.open(modelAssetPath).close(); true
    } catch (_: Throwable) { false }
    if (bundled) return BaseOptions.builder().setModelAssetPath(modelAssetPath).build()
    return null
  }

  fun detect(bmp: Bitmap): List<Hit> {
    val lm = landmarker ?: return emptyList()
    val mp = BitmapImageBuilder(bmp).build()
    val result: FaceLandmarkerResult = try {
      lm.detect(mp)
    } catch (t: Throwable) {
      Log.w(TAG, "FaceLandmarker detect failed", t)
      return emptyList()
    }
    val out = mutableListOf<Hit>()
    val faces = result.faceLandmarks()
    for (i in 0 until faces.size) {
      val pts = faces[i]
      var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
      val pairs = ArrayList<Pair<Float, Float>>(pts.size)
      for (p in pts) {
        val x = p.x().coerceIn(0f, 1f); val y = p.y().coerceIn(0f, 1f)
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
        pairs += x to y
      }
      out += Hit(
        xn = minX, yn = minY, wn = (maxX - minX).coerceAtLeast(0.01f), hn = (maxY - minY).coerceAtLeast(0.01f),
        confidence = 1f, landmarks = pairs,
      )
    }
    return out
  }

  override fun close() { try { landmarker?.close() } catch (_: Throwable) {} }

  companion object { private const val TAG = "FaceDetector" }
}
