/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * No-OpenCV image quality metrics — pure Kotlin sharpness + brightness.
 * Sharpness via 3×3 Laplacian variance on luminance. Brightness via mean luma.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import android.graphics.Bitmap

object ImageQuality {

  data class Quality(val sharpness: Float, val brightness: Float)

  fun measure(bmp: Bitmap, sampleSide: Int = 256): Quality {
    val src = if (maxOf(bmp.width, bmp.height) <= sampleSide) bmp
              else Bitmap.createScaledBitmap(bmp, sampleSide, sampleSide, true)
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    val luma = IntArray(w * h)
    var sumLuma = 0L
    for (i in pixels.indices) {
      val p = pixels[i]
      val r = (p shr 16) and 0xff
      val g = (p shr 8) and 0xff
      val b = p and 0xff
      val y = (299 * r + 587 * g + 114 * b) / 1000
      luma[i] = y
      sumLuma += y
    }
    val brightness = (sumLuma.toFloat() / (w * h)) / 255f
    val sharpness = laplacianVariance(luma, w, h)
    return Quality(sharpness, brightness)
  }

  /** Sum of (Laplacian) values is normalized; return variance / (255^2 * 8) so output is roughly [0,1]. */
  private fun laplacianVariance(luma: IntArray, w: Int, h: Int): Float {
    if (w < 3 || h < 3) return 0f
    var sum = 0L
    var sumSq = 0L
    var count = 0
    for (y in 1 until h - 1) {
      for (x in 1 until w - 1) {
        val c = luma[y * w + x]
        val u = luma[(y - 1) * w + x]
        val d = luma[(y + 1) * w + x]
        val l = luma[y * w + x - 1]
        val r = luma[y * w + x + 1]
        val lap = u + d + l + r - 4 * c
        sum += lap
        sumSq += (lap.toLong() * lap.toLong())
        count++
      }
    }
    val mean = sum.toDouble() / count
    val variance = (sumSq.toDouble() / count) - (mean * mean)
    // Normalize: an in-focus 256x256 photo typically lands in [200, 2000] of raw variance;
    // divide by 2000 to get a roughly [0,1] sharpness score with clipping.
    return (variance / 2000.0).toFloat().coerceIn(0f, 1f)
  }
}
