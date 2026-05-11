/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Builds a compact contact sheet for one video / live photo. The sheet keeps
 * frame indices and timestamps visible so the VLM can describe motion across
 * the clip and point us at the best moment without another expensive call.
 */
package com.vlogcopilot.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.vlogcopilot.perception.MediaLoader
import com.vlogcopilot.perception.SceneCutDetector
import com.vlogcopilot.runtime.PowerPacer
import com.vlogcopilot.runtime.PowerProfile
import com.vlogcopilot.schemas.Asset
import com.vlogcopilot.schemas.MediaType
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

object VideoFrameSheetBuilder {

  // Smaller cells = fewer image tokens for Gemma's visual encoder, which is
  // the dominant cost in VLM annotation. Pixel count drops from 320×426
  // (~136k/cell) to 240×320 (~77k/cell), roughly halving encoder work.
  // 240px is still readable for scene/subject identification at the small
  // crop sizes in the contact sheet — verified against Gemma 4 E2B's
  // visual recall on 240px tiles.
  private const val CELL_W = 240
  private const val CELL_H = 320
  private const val MAX_LOW_POWER_FRAME_COUNT = 12
  private const val MAX_HIGH_QUALITY_FRAME_COUNT = 16

  data class Sheet(
    val bitmap: Bitmap,
    val frameTimestampsSec: List<Float>,
  )

  fun build(context: Context, asset: Asset, sceneCutsSec: List<Float> = emptyList()): Sheet? {
    if (asset.mediaType == MediaType.IMAGE) return null
    val timestamps = sampleTimestamps(asset, sceneCutsSec)
    if (timestamps.isEmpty()) return null
    val frames = MediaLoader.sampleVideoFramesAt(context, asset, timestamps, maxSide = maxOf(CELL_W, CELL_H))
    if (frames.isEmpty()) return null

    val cols = sheetColumns(frames.size)
    val rows = ceil(frames.size.toDouble() / cols.toDouble()).toInt()
    val sheet = Bitmap.createBitmap(cols * CELL_W, rows * CELL_H, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet).apply { drawColor(Color.BLACK) }

    frames.forEachIndexed { i, (timestampSec, bitmap) ->
      val col = i % cols
      val row = i / cols
      val x = col * CELL_W
      val y = row * CELL_H
      drawFrame(canvas, bitmap, x, y)
      drawBadge(canvas, x.toFloat(), y.toFloat(), i + 1, timestampSec)
      bitmap.recycle()
    }
    return Sheet(sheet, frames.map { it.first })
  }

  fun targetFrameCount(asset: Asset, powerProfile: PowerProfile = PowerPacer.currentProfile()): Int {
    val durSec = asset.durationMs / 1000f
    if (durSec <= 0f) return 0
    val maxFrames = if (powerProfile == PowerProfile.HIGH_QUALITY) {
      MAX_HIGH_QUALITY_FRAME_COUNT
    } else {
      MAX_LOW_POWER_FRAME_COUNT
    }
    return when {
      asset.mediaType == MediaType.LIVE_PHOTO && durSec <= 4f -> 4
      asset.mediaType == MediaType.LIVE_PHOTO -> 6
      durSec <= 6f -> 4
      durSec <= 15f -> 6
      durSec <= 30f -> 8
      durSec <= 60f -> 10
      durSec <= 180f -> if (powerProfile == PowerProfile.HIGH_QUALITY) 14 else 12
      else -> maxFrames
    }
  }

  private fun sampleTimestamps(asset: Asset, sceneCutsSec: List<Float>): List<Float> {
    val durSec = asset.durationMs / 1000f
    val target = targetFrameCount(asset)
    if (durSec <= 0f || target <= 0) return emptyList()
    if (durSec <= 0.5f) return listOf(durSec / 2f)

    val even = (0 until target).map { i -> durSec * (i + 0.5f) / target }
    val sceneCenters = sceneCenters(durSec, sceneCutsSec, limit = max(2, target * 2 / 3))
    val anchors = listOf(durSec * 0.12f, durSec * 0.50f, durSec * 0.88f)
    val minGapSec = (durSec / (target * 1.8f)).coerceIn(0.45f, 5f)

    val picked = mutableListOf<Float>()
    fun addIfUseful(tSec: Float, minGap: Float) {
      val t = tSec.coerceIn(0.05f, (durSec - 0.05f).coerceAtLeast(0.05f))
      if (picked.none { kotlin.math.abs(it - t) < minGap }) picked += t
    }

    (sceneCenters + even + anchors).forEach { addIfUseful(it, minGapSec) }
    if (picked.size < target) even.forEach { addIfUseful(it, minGapSec / 2f) }
    if (picked.size < target) anchors.forEach { addIfUseful(it, minGapSec / 3f) }

    return picked
      .sorted()
      .take(target)
  }

  private fun sceneCenters(durSec: Float, sceneCutsSec: List<Float>, limit: Int): List<Float> {
    val cuts = sceneCutsSec
      .filter { it > 0.3f && it < durSec - 0.3f }
      .distinct()
      .sorted()
    if (cuts.isEmpty() || limit <= 0) return emptyList()
    val centers = SceneCutDetector.boundaries(durSec, cuts)
      .filter { (start, end) -> end - start >= 0.6f }
      .map { (start, end) -> (start + end) / 2f }
    return selectSpread(centers, limit)
  }

  private fun selectSpread(values: List<Float>, limit: Int): List<Float> {
    if (values.size <= limit) return values
    return (0 until limit).map { i ->
      val idx = ((i + 0.5f) * values.size / limit).toInt().coerceIn(0, values.lastIndex)
      values[idx]
    }.distinct()
  }

  private fun sheetColumns(count: Int): Int = when {
    count <= 1 -> 1
    count <= 4 -> 2
    count <= 9 -> 3
    else -> 4
  }

  private fun drawFrame(canvas: Canvas, bitmap: Bitmap, x: Int, y: Int) {
    val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
    val dstAspect = CELL_W.toFloat() / CELL_H.toFloat()
    val src = if (srcAspect > dstAspect) {
      val cropW = (bitmap.height * dstAspect).toInt().coerceAtLeast(1)
      val left = ((bitmap.width - cropW) / 2).coerceAtLeast(0)
      Rect(left, 0, (left + cropW).coerceAtMost(bitmap.width), bitmap.height)
    } else {
      val cropH = (bitmap.width / dstAspect).toInt().coerceAtLeast(1)
      val top = ((bitmap.height - cropH) / 2).coerceAtLeast(0)
      Rect(0, top, bitmap.width, (top + cropH).coerceAtMost(bitmap.height))
    }
    val dst = Rect(x, y, x + CELL_W, y + CELL_H)
    canvas.drawBitmap(bitmap, src, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
  }

  private fun drawBadge(canvas: Canvas, x: Float, y: Float, index: Int, timestampSec: Float) {
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 255, 122, 24) }
    val rect = RectF(x + 10f, y + 10f, x + 104f, y + 48f)
    canvas.drawRoundRect(rect, 18f, 18f, bg)

    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = 21f
      textAlign = Paint.Align.CENTER
    }
    val text = "$index  ${"%.1fs".format(Locale.US, timestampSec)}"
    val cy = rect.centerY() - (tp.descent() + tp.ascent()) / 2f
    canvas.drawText(text, rect.centerX(), cy, tp)
  }
}
