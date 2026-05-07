/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * MobileFaceNet TFLite — 112×112 face crop in [-1,1] → 192-D L2-normalized
 * embedding. Cosine similarity > 0.4 ≈ same person empirically.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.tensorflow.lite.Interpreter

class FaceEmbedder(
  context: Context,
  modelPath: String = "models/mobilefacenet.tflite",
  private val side: Int = 112,
  private val embDim: Int = 192,
) : Closeable {

  private val interpreter: Interpreter? = TfliteLoader.load(context, modelPath)

  /** Returns L2-normalized embedding, or empty list if model missing / inference failed. */
  fun embed(faceCrop: Bitmap): FloatArray {
    val itp = interpreter ?: return FloatArray(0)
    val input = BitmapPrep.toNhwcFloatNeg1to1(faceCrop, side)
    val output = ByteBuffer.allocateDirect(embDim * 4).order(ByteOrder.nativeOrder())
    return try {
      itp.run(input, output)
      output.rewind()
      val arr = FloatArray(embDim).also { output.asFloatBuffer().get(it) }
      l2norm(arr)
    } catch (_: Throwable) {
      FloatArray(0)
    }
  }

  override fun close() { try { interpreter?.close() } catch (_: Throwable) {} }

  private fun l2norm(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += x * x
    val n = sqrt(s).toFloat().coerceAtLeast(1e-8f)
    val out = FloatArray(v.size)
    for (i in v.indices) out[i] = v[i] / n
    return out
  }
}
