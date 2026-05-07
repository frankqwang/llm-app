/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Lightweight scene-cut detector for videos: sample N frames, compute per-frame
 * 64-bin grayscale histogram, detect cuts where chi-square distance between
 * adjacent histograms exceeds threshold. Cheaper than PySceneDetect's content
 * detector but good enough for our shot-segmentation needs.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import kotlin.math.max

object SceneCutDetector {

  /** Returns timestamps (sec) at which scene changes occur. */
  fun detect(
    context: Context,
    asset: Asset,
    sampleHz: Float = 4f,
    threshold: Float = 0.6f,
  ): List<Float> {
    val durSec = asset.durationMs / 1000f
    if (durSec < 8f) return emptyList()
    val effectiveHz = minOf(sampleHz, adaptiveSampleHz(durSec))
    val n = (durSec * effectiveHz).toInt()
      .coerceAtLeast(2)
      .coerceAtMost(maxFrames(durSec))
    val frames = MediaLoader.sampleVideoFrames(context, asset, n, maxSide = 256)
    if (frames.size < 2) return emptyList()

    val hists = frames.map { (_, bmp) ->
      try {
        grayHistogram(bmp, bins = 64)
      } finally {
        runCatching { bmp.recycle() }
      }
    }
    val cuts = mutableListOf<Float>()
    for (i in 1 until hists.size) {
      val dist = chiSquare(hists[i - 1], hists[i])
      if (dist > threshold) cuts += frames[i].first
    }
    return cuts
  }

  private fun adaptiveSampleHz(durSec: Float): Float = when {
    durSec <= 30f -> 2f
    durSec <= 120f -> 1f
    else -> 0.5f
  }

  private fun maxFrames(durSec: Float): Int = when {
    durSec <= 30f -> 32
    durSec <= 120f -> 48
    else -> 60
  }

  private fun grayHistogram(bmp: Bitmap, bins: Int): FloatArray {
    val w = bmp.width; val h = bmp.height
    val pix = IntArray(w * h)
    bmp.getPixels(pix, 0, w, 0, 0, w, h)
    val hist = FloatArray(bins)
    val binWidth = 256f / bins
    for (p in pix) {
      val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
      val y = (299 * r + 587 * g + 114 * b) / 1000
      val idx = (y / binWidth).toInt().coerceIn(0, bins - 1)
      hist[idx] += 1f
    }
    val total = (w * h).toFloat()
    for (i in hist.indices) hist[i] /= total
    return hist
  }

  /** Symmetric chi-square distance, returns roughly [0, 1]. */
  private fun chiSquare(a: FloatArray, b: FloatArray): Float {
    var d = 0.0
    for (i in a.indices) {
      val s = (a[i] + b[i]).toDouble()
      if (s > 0) {
        val diff = (a[i] - b[i]).toDouble()
        d += diff * diff / s
      }
    }
    return (d / 2).toFloat().coerceAtMost(1f)
  }

  /** Convenience: convert raw cut timestamps to scene boundaries [(start, end), ...]. */
  fun boundaries(durationSec: Float, cuts: List<Float>): List<Pair<Float, Float>> {
    val all = (listOf(0f) + cuts + listOf(durationSec)).distinct().sorted()
    val out = mutableListOf<Pair<Float, Float>>()
    for (i in 0 until all.size - 1) {
      val s = all[i]; val e = all[i + 1]
      if (e - s >= 0.4f) out += s to e   // drop sub-frame slivers
    }
    return out
  }

  /** Free-standing helper used by callers that already have raw bitmaps. */
  fun maxLuma(bmp: Bitmap): Int {
    val pix = IntArray(bmp.width * bmp.height)
    bmp.getPixels(pix, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    var m = 0
    for (p in pix) {
      val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
      m = max(m, (299 * r + 587 * g + 114 * b) / 1000)
    }
    return m
  }
}
