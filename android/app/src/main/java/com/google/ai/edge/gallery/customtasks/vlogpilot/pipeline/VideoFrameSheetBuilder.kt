/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Builds a compact contact sheet for one video / live photo. The sheet keeps
 * frame indices and timestamps visible so the VLM can describe motion across
 * the clip and point us at the best moment without another expensive call.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.MediaLoader
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

object VideoFrameSheetBuilder {

  private const val FRAME_COUNT = 6
  private const val CELL_W = 360
  private const val CELL_H = 480

  data class Sheet(
    val bitmap: Bitmap,
    val frameTimestampsSec: List<Float>,
  )

  fun build(context: Context, asset: Asset): Sheet? {
    if (asset.mediaType == MediaType.IMAGE) return null
    val frames = MediaLoader.sampleVideoFrames(context, asset, FRAME_COUNT, maxSide = maxOf(CELL_W, CELL_H))
    if (frames.isEmpty()) return null

    val cols = min(3, frames.size).coerceAtLeast(1)
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
