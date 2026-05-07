/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Bitmap → tensor preprocessing helpers. All four inference frameworks
 * (TFLite Java, ONNX Runtime, MediaPipe Tasks) take FloatBuffer/ByteBuffer
 * with model-specific normalization, so this file holds the actual byte math.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object BitmapPrep {

  /** Resize keeping aspect, then center-pad to a square `side`. Returns (sized, scale, padX, padY). */
  fun letterbox(src: Bitmap, side: Int, padColor: Int = Color.BLACK): LetterboxResult {
    val scale = (side.toFloat() / src.width.toFloat()).coerceAtMost(side.toFloat() / src.height.toFloat())
    val sw = (src.width * scale).toInt().coerceAtLeast(1)
    val sh = (src.height * scale).toInt().coerceAtLeast(1)
    val sized = Bitmap.createScaledBitmap(src, sw, sh, true)
    val padX = (side - sw) / 2
    val padY = (side - sh) / 2
    val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(padColor)
    canvas.drawBitmap(sized, padX.toFloat(), padY.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))
    return LetterboxResult(out, scale, padX, padY)
  }

  data class LetterboxResult(val bitmap: Bitmap, val scale: Float, val padX: Int, val padY: Int)

  /**
   * NHWC float32 in [0,1] (channels-last). Used by YOLO26 TFLite and most TFLite models.
   * Buffer layout: 1 * H * W * 3 floats.
   */
  fun toNhwcFloat01(bmp: Bitmap, side: Int): ByteBuffer {
    val src = if (bmp.width == side && bmp.height == side) bmp else Bitmap.createScaledBitmap(bmp, side, side, true)
    val buffer = ByteBuffer.allocateDirect(side * side * 3 * 4).order(ByteOrder.nativeOrder())
    val pixels = IntArray(side * side)
    src.getPixels(pixels, 0, side, 0, 0, side, side)
    for (p in pixels) {
      buffer.putFloat(((p shr 16) and 0xff) / 255f)
      buffer.putFloat(((p shr 8) and 0xff) / 255f)
      buffer.putFloat((p and 0xff) / 255f)
    }
    buffer.rewind()
    return buffer
  }

  /**
   * NCHW float32 with ImageNet mean/std. Used by NSFW ViT (AdamCodd) and most ONNX vision models.
   * mean=[0.485,0.456,0.406], std=[0.229,0.224,0.225].
   */
  fun toNchwImagenetFloat(bmp: Bitmap, side: Int): FloatBuffer {
    val src = if (bmp.width == side && bmp.height == side) bmp else Bitmap.createScaledBitmap(bmp, side, side, true)
    val buf = FloatBuffer.allocate(side * side * 3)
    val pixels = IntArray(side * side)
    src.getPixels(pixels, 0, side, 0, 0, side, side)
    val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    val std = floatArrayOf(0.229f, 0.224f, 0.225f)
    // R channel
    for (p in pixels) buf.put((((p shr 16) and 0xff) / 255f - mean[0]) / std[0])
    for (p in pixels) buf.put((((p shr 8) and 0xff) / 255f - mean[1]) / std[1])
    for (p in pixels) buf.put(((p and 0xff) / 255f - mean[2]) / std[2])
    buf.rewind()
    return buf
  }

  /**
   * NHWC float32 in [-1,1]. Used by MobileCLIP and MobileFaceNet.
   */
  fun toNhwcFloatNeg1to1(bmp: Bitmap, side: Int): ByteBuffer {
    val src = if (bmp.width == side && bmp.height == side) bmp else Bitmap.createScaledBitmap(bmp, side, side, true)
    val buffer = ByteBuffer.allocateDirect(side * side * 3 * 4).order(ByteOrder.nativeOrder())
    val pixels = IntArray(side * side)
    src.getPixels(pixels, 0, side, 0, 0, side, side)
    for (p in pixels) {
      buffer.putFloat((((p shr 16) and 0xff) / 127.5f) - 1f)
      buffer.putFloat((((p shr 8) and 0xff) / 127.5f) - 1f)
      buffer.putFloat(((p and 0xff) / 127.5f) - 1f)
    }
    buffer.rewind()
    return buffer
  }

  /** Crop a bitmap by normalized [0,1] xywh, optionally squaring it. */
  fun cropNorm(src: Bitmap, x: Float, y: Float, w: Float, h: Float, square: Boolean = false): Bitmap? {
    var px = (x * src.width).toInt().coerceAtLeast(0)
    var py = (y * src.height).toInt().coerceAtLeast(0)
    var pw = (w * src.width).toInt().coerceAtMost(src.width - px)
    var ph = (h * src.height).toInt().coerceAtMost(src.height - py)
    if (pw <= 0 || ph <= 0) return null
    if (square) {
      val s = minOf(pw, ph)
      val cx = px + pw / 2
      val cy = py + ph / 2
      px = (cx - s / 2).coerceIn(0, src.width - s)
      py = (cy - s / 2).coerceIn(0, src.height - s)
      pw = s; ph = s
    }
    return Bitmap.createBitmap(src, px, py, pw, ph)
  }
}
