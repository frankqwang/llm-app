/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * AdamCodd/vit-base-nsfw-detector ONNX int8. Two-class classifier (sfw, nsfw)
 * with ImageNet preprocessing 224×224 NCHW. Model file sits at
 * filesDir/models/nsfw_vit_int8.onnx (downloaded on first launch).
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.Closeable
import java.io.File
import kotlin.math.exp

class NsfwClassifier(
  context: Context,
  modelPath: String = "models/nsfw_vit_int8.onnx",
  private val side: Int = 224,
) : Closeable {

  private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
  private val session: OrtSession? = try {
    val resolved = resolveModelPath(context, modelPath) ?: error("model not found")
    val opts = OrtSession.SessionOptions().apply {
      setIntraOpNumThreads(2)
      setInterOpNumThreads(1)
    }
    env.createSession(resolved.absolutePath, opts)
  } catch (t: Throwable) {
    Log.w(TAG, "NSFW model load failed: ${t.message}")
    null
  }

  private val inputName: String = session?.inputNames?.iterator()?.next() ?: "pixel_values"

  /** Returns NSFW probability in [0, 1]. 0 if model missing. */
  fun score(bmp: Bitmap): Float {
    val s = session ?: return 0f
    val floats = BitmapPrep.toNchwImagenetFloat(bmp, side)
    val shape = longArrayOf(1, 3, side.toLong(), side.toLong())
    return try {
      OnnxTensor.createTensor(env, floats, shape).use { tensor ->
        s.run(mapOf(inputName to tensor)).use { result ->
          val raw = result[0].value as Array<FloatArray>
          val logits = raw[0]
          // AdamCodd order: [sfw, nsfw]. Use softmax index 1.
          val (a, b) = logits[0] to logits[1]
          val ea = exp((a - maxOf(a, b)).toDouble())
          val eb = exp((b - maxOf(a, b)).toDouble())
          (eb / (ea + eb)).toFloat().coerceIn(0f, 1f)
        }
      }
    } catch (e: OrtException) {
      Log.w(TAG, "NSFW infer failed: ${e.message}")
      0f
    }
  }

  override fun close() { try { session?.close() } catch (_: Throwable) {} }

  private fun resolveModelPath(context: Context, name: String): File? {
    File(name).takeIf { it.isFile }?.let { return it }
    val basename = File(name).name
    File(context.filesDir, "models/$basename").takeIf { it.isFile }?.let { return it }
    // ONNX Runtime can't mmap directly out of an asset — caller must copy first;
    // we look for a pre-extracted copy under filesDir as a fallback.
    return null
  }

  companion object { private const val TAG = "NsfwClassifier" }
}
