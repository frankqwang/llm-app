/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * MobileCLIP2-S1 dual-tower TFLite — image and text encoders are separate .tflite
 * files (anton96vice/mobileclip2_tflite). Image side: 256×256 NHWC [-1,1] →
 * 512-D. Text side: BPE tokenized → 77 token ids → 512-D. We bundle the BPE
 * vocab as a JSON asset and run a lightweight tokenizer inline.
 *
 * Both embeddings are L2-normalized so cosine similarity is just a dot product.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.tensorflow.lite.Interpreter

class ClipEmbedder(
  context: Context,
  imageModelPath: String = "models/mobileclip2_s1_image.tflite",
  textModelPath: String = "models/mobileclip2_s1_text.tflite",
  private val tokenizer: ClipTokenizer? = ClipTokenizer.tryLoad(context),
  private val imageSide: Int = 256,
  private val embDim: Int = 512,
  private val seqLen: Int = 77,
) : Closeable {

  private val imageItp: Interpreter? = TfliteLoader.load(context, imageModelPath)
  private val textItp: Interpreter? = TfliteLoader.load(context, textModelPath)

  fun embedImage(bmp: Bitmap): FloatArray {
    val itp = imageItp ?: return FloatArray(0)
    val input = BitmapPrep.toNhwcFloatNeg1to1(bmp, imageSide)
    val output = ByteBuffer.allocateDirect(embDim * 4).order(ByteOrder.nativeOrder())
    return try {
      itp.run(input, output)
      output.rewind()
      l2norm(FloatArray(embDim).also { output.asFloatBuffer().get(it) })
    } catch (_: Throwable) {
      FloatArray(0)
    }
  }

  fun embedText(text: String): FloatArray {
    val itp = textItp ?: return FloatArray(0)
    val tok = tokenizer ?: return FloatArray(0)
    val ids = tok.encode(text, seqLen)
    val input = ByteBuffer.allocateDirect(seqLen * 4).order(ByteOrder.nativeOrder())
    for (id in ids) input.putInt(id)
    input.rewind()
    val output = ByteBuffer.allocateDirect(embDim * 4).order(ByteOrder.nativeOrder())
    return try {
      itp.run(input, output)
      output.rewind()
      l2norm(FloatArray(embDim).also { output.asFloatBuffer().get(it) })
    } catch (_: Throwable) {
      FloatArray(0)
    }
  }

  /** cosine similarity for two equally-sized L2-normalized vectors. */
  fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
  }

  override fun close() {
    try { imageItp?.close() } catch (_: Throwable) {}
    try { textItp?.close() } catch (_: Throwable) {}
  }

  private fun l2norm(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += x * x
    val n = sqrt(s).toFloat().coerceAtLeast(1e-8f)
    val out = FloatArray(v.size)
    for (i in v.indices) out[i] = v[i] / n
    return out
  }
}
