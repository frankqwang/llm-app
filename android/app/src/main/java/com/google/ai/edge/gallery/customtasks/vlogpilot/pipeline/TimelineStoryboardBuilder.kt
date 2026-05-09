/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Builds a visual storyboard of the current timeline for the Critic agent.
 * Each cell is the actual selected shot preview with order/duration badge.
 * On top, we overlay editing-language hints so Critic can judge the cut
 * quality (not just the picked frame): transition kind entering this shot,
 * Ken Burns motion direction, and a caption preview rendered in the real
 * CJK font so Critic can spot length/readability issues.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.MediaLoader
import com.google.ai.edge.gallery.customtasks.vlogpilot.render.VideoRenderer
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.TransitionKind
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

    val cjkTypeface = VideoRenderer.ensureFont(context)?.let {
      runCatching { Typeface.createFromFile(it) }.getOrNull()
    }

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
      drawHeaderBadge(canvas, x.toFloat(), y.toFloat(), shot)
      if (shot.order > 1) {
        drawTransitionBadge(canvas, x.toFloat(), y.toFloat(), shot.transitionIn)
      }
      drawCaptionPreview(canvas, x.toFloat(), y.toFloat(), shot.caption, cjkTypeface)
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

  private fun drawHeaderBadge(canvas: Canvas, x: Float, y: Float, shot: ShotSpec) {
    val kbTag = kenBurnsLabel(shot.kenBurns)
    val speedTag = speedLabel(shot.speedFactor)
    val text = buildString {
      append('#').append(shot.order)
      append("  ").append("%.1fs".format(Locale.US, shot.durationSec))
      if (kbTag.isNotEmpty()) append("  ").append(kbTag)
      if (speedTag.isNotEmpty()) append("  ").append(speedTag)
    }
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = 21f
    }
    val padX = 14f
    val padY = 8f
    val textW = tp.measureText(text)
    val rect = RectF(x + 10f, y + 10f, x + 10f + textW + padX * 2, y + 10f + tp.textSize + padY * 2)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 20, 20, 20) }
    canvas.drawRoundRect(rect, 16f, 16f, bg)
    val cy = rect.centerY() - (tp.descent() + tp.ascent()) / 2f
    canvas.drawText(text, rect.left + padX, cy, tp)
  }

  private fun drawTransitionBadge(canvas: Canvas, x: Float, y: Float, kind: TransitionKind) {
    val text = transitionLabel(kind)
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = 22f
    }
    val padX = 14f
    val padY = 8f
    val textW = tp.measureText(text)
    val right = x + CELL_W - 10f
    val rect = RectF(right - textW - padX * 2, y + 10f, right, y + 10f + tp.textSize + padY * 2)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = transitionTint(kind) }
    canvas.drawRoundRect(rect, 14f, 14f, bg)
    val cy = rect.centerY() - (tp.descent() + tp.ascent()) / 2f
    canvas.drawText(text, rect.left + padX, cy, tp)
  }

  // Caption preview at the bottom of each cell so Critic can judge length /
  // readability. Falls back gracefully if the CJK font isn't available — we
  // skip drawing rather than show tofu boxes.
  private fun drawCaptionPreview(canvas: Canvas, x: Float, y: Float, caption: String, font: Typeface?) {
    if (caption.isBlank() || font == null) return
    val stripH = 64f
    val stripTop = y + CELL_H - stripH
    val gradient = Paint().apply {
      shader = LinearGradient(
        x, stripTop, x, y + CELL_H,
        Color.argb(0, 0, 0, 0), Color.argb(220, 0, 0, 0),
        Shader.TileMode.CLAMP,
      )
    }
    canvas.drawRect(x, stripTop, x + CELL_W, y + CELL_H, gradient)
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      typeface = font
      textSize = 26f
      textAlign = Paint.Align.CENTER
      isFakeBoldText = true
    }
    // Truncate to one line that fits comfortably.
    val maxW = CELL_W - 32f
    var text = caption
    if (tp.measureText(text) > maxW) {
      while (text.length > 1 && tp.measureText("$text…") > maxW) text = text.dropLast(1)
      text = "$text…"
    }
    val cy = y + CELL_H - 18f
    canvas.drawText(text, x + CELL_W / 2f, cy, tp)
  }

  private fun kenBurnsLabel(kb: String): String = when (kb) {
    "in" -> "↗放"
    "out" -> "↘缩"
    "pan_left" -> "←移"
    "pan_right" -> "移→"
    else -> ""
  }

  // Speed badge — only render when Director chose a non-default rate, so
  // common shots stay uncluttered. <0.95 = slow, >1.05 = fast.
  private fun speedLabel(speedFactor: Float): String = when {
    speedFactor < 0.95f -> "%.1fx⏪".format(Locale.US, speedFactor)
    speedFactor > 1.05f -> "%.1fx⏩".format(Locale.US, speedFactor)
    else -> ""
  }

  // Compact Chinese labels — Gemma reads these reliably and they fit the
  // narrow top-right corner. Color tint hints at category (warm = soft cuts,
  // cool = abrupt, purple = decorative).
  private fun transitionLabel(kind: TransitionKind): String = when (kind) {
    TransitionKind.CUT -> "硬切"
    TransitionKind.FADE -> "淡入"
    TransitionKind.CROSSFADE -> "叠化"
    TransitionKind.FADEBLACK -> "黑场"
    TransitionKind.FADEWHITE -> "白闪"
    TransitionKind.SLIDELEFT -> "左滑"
    TransitionKind.SLIDERIGHT -> "右滑"
    TransitionKind.CIRCLEOPEN -> "圆开"
    TransitionKind.CIRCLECLOSE -> "圆合"
    TransitionKind.ZOOMIN -> "推近"
    TransitionKind.SMOOTHLEFT -> "柔←"
    TransitionKind.SMOOTHRIGHT -> "柔→"
  }

  private fun transitionTint(kind: TransitionKind): Int = when (kind) {
    TransitionKind.CUT -> Color.argb(225, 110, 30, 30)
    TransitionKind.FADE, TransitionKind.CROSSFADE -> Color.argb(225, 40, 60, 90)
    TransitionKind.FADEBLACK -> Color.argb(225, 25, 25, 25)
    TransitionKind.FADEWHITE -> Color.argb(225, 180, 180, 180)
    TransitionKind.SLIDELEFT, TransitionKind.SLIDERIGHT,
    TransitionKind.SMOOTHLEFT, TransitionKind.SMOOTHRIGHT -> Color.argb(225, 70, 90, 50)
    TransitionKind.CIRCLEOPEN, TransitionKind.CIRCLECLOSE -> Color.argb(225, 90, 50, 110)
    TransitionKind.ZOOMIN -> Color.argb(225, 130, 70, 30)
  }
}
