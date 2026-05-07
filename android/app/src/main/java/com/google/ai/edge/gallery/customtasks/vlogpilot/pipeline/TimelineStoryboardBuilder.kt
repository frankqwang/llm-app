/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Builds a visual storyboard of the current timeline for the Critic agent.
 * Each cell is the actual selected shot preview (video windows use their trim
 * center), with the shot order and duration overlaid.
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
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

object TimelineStoryboardBuilder {

  private const val CELL_W = 360
  private const val CELL_H = 480

  fun build(context: Context, timeline: Timeline, assets: Map<String, Asset>): Bitmap? {
    val shots = timeline.shots.sortedBy { it.order }
    if (shots.isEmpty()) return null

    val cols = min(3, shots.size).coerceAtLeast(1)
    val rows = ceil(shots.size.toDouble() / cols.toDouble()).toInt()
    val sheet = Bitmap.createBitmap(cols * CELL_W, rows * CELL_H, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet).apply { drawColor(Color.rgb(12, 12, 12)) }

    shots.forEachIndexed { i, shot ->
      val asset = assets[shot.assetId]
      val col = i % cols
      val row = i / cols
      val x = col * CELL_W
      val y = row * CELL_H
      val preview = asset?.let { loadPreview(context, it, shot) }
      if (preview != null) {
        drawCropped(canvas, preview, x, y)
        preview.recycle()
      } else {
        Paint().apply { color = Color.DKGRAY }.also { canvas.drawRect(x.toFloat(), y.toFloat(), (x + CELL_W).toFloat(), (y + CELL_H).toFloat(), it) }
      }
      drawBadge(canvas, x.toFloat(), y.toFloat(), shot)
    }
    return sheet
  }

  private fun loadPreview(context: Context, asset: Asset, shot: ShotSpec): Bitmap? {
    val trim = shot.videoTrim
    return if (asset.mediaType != MediaType.IMAGE && trim != null) {
      val tSec = (trim.startSec + trim.endSec) / 2f
      MediaLoader.loadVideoFrame(context, asset, tSec, maxSide = maxOf(CELL_W, CELL_H))
        ?: MediaLoader.loadImage(context, asset, maxSide = maxOf(CELL_W, CELL_H))
    } else {
      MediaLoader.loadImage(context, asset, maxSide = maxOf(CELL_W, CELL_H))
    }
  }

  private fun drawCropped(canvas: Canvas, bitmap: Bitmap, x: Int, y: Int) {
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
    canvas.drawBitmap(bitmap, src, Rect(x, y, x + CELL_W, y + CELL_H), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
  }

  private fun drawBadge(canvas: Canvas, x: Float, y: Float, shot: ShotSpec) {
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 20, 20, 20) }
    val rect = RectF(x + 10f, y + 10f, x + 132f, y + 50f)
    canvas.drawRoundRect(rect, 16f, 16f, bg)
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = 21f
      textAlign = Paint.Align.CENTER
    }
    val text = "#${shot.order}  ${"%.1fs".format(Locale.US, shot.durationSec)}"
    val cy = rect.centerY() - (tp.descent() + tp.ascent()) / 2f
    canvas.drawText(text, rect.centerX(), cy, tp)
  }
}
