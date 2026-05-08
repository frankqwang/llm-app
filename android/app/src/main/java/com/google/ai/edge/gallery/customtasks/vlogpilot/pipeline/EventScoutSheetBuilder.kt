/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Builds 3x3 event scout sheets. A cell is a visual unit: either one still
 * image thumbnail or one sampled video/live-photo frame. Large events are
 * sampled by time so VLM gets an overview without unreadable tiny thumbnails.
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
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.PowerProfile
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import java.util.Locale
import kotlin.math.roundToInt

object EventScoutSheetBuilder {

  const val SCOUT_VERSION = 1
  private const val CELLS_PER_PAGE = 9
  private const val CELL_W = 320
  private const val CELL_H = 426

  data class VisualUnit(
    val asset: Asset,
    val frameSec: Float? = null,
  )

  data class Sheet(
    val bitmap: Bitmap,
    val units: List<VisualUnit>,
    val pageIndex: Int,
    val pageCount: Int,
    val totalAssetCount: Int,
    val totalVisualUnitCount: Int,
    val sampled: Boolean,
  )

  fun build(
    context: Context,
    assets: List<Asset>,
    powerProfile: PowerProfile,
  ): List<Sheet> {
    if (assets.isEmpty()) return emptyList()
    val allUnits = assets.sortedBy { it.takenEpochMs }.flatMap { visualUnitsFor(it, powerProfile) }
    val maxUnits = maxVisualUnits(powerProfile)
    val units = if (allUnits.size > maxUnits) selectSpread(allUnits, maxUnits) else allUnits
    val pageCount = ((units.size + CELLS_PER_PAGE - 1) / CELLS_PER_PAGE).coerceAtLeast(1)
    return units.chunked(CELLS_PER_PAGE).mapIndexedNotNull { pageIdx, chunk ->
      val bitmap = drawSheet(context, chunk, pageIdx + 1)
      Sheet(
        bitmap = bitmap,
        units = chunk,
        pageIndex = pageIdx + 1,
        pageCount = pageCount,
        totalAssetCount = assets.size,
        totalVisualUnitCount = allUnits.size,
        sampled = allUnits.size > units.size,
      )
    }
  }

  fun signature(assets: List<Asset>, powerProfile: PowerProfile): String =
    buildString {
      append(SCOUT_VERSION).append('|').append(powerProfile.name)
      assets.sortedBy { it.id }.forEach { asset ->
        append('|')
        append(asset.id).append(',')
        append(asset.contentUri).append(',')
        append(asset.mediaType.name).append(',')
        append(asset.takenEpochMs).append(',')
        append(asset.durationMs).append(',')
        append(asset.sizeBytes).append(',')
        append(asset.widthPx).append('x').append(asset.heightPx)
      }
    }

  private fun maxVisualUnits(powerProfile: PowerProfile): Int =
    when (powerProfile) {
      PowerProfile.LOW_POWER -> 36
      PowerProfile.BALANCED -> 54
      PowerProfile.HIGH_QUALITY -> 72
    }

  private fun visualUnitsFor(asset: Asset, powerProfile: PowerProfile): List<VisualUnit> {
    if (asset.mediaType == MediaType.IMAGE || asset.durationMs <= 0L) return listOf(VisualUnit(asset))
    val durSec = asset.durationMs / 1000f
    val base = when {
      asset.mediaType == MediaType.LIVE_PHOTO && durSec <= 4f -> 2
      asset.mediaType == MediaType.LIVE_PHOTO -> 3
      durSec <= 6f -> 2
      durSec <= 15f -> 3
      durSec <= 30f -> 4
      durSec <= 60f -> 5
      else -> 6
    }
    val count = when (powerProfile) {
      PowerProfile.LOW_POWER -> base
      PowerProfile.BALANCED -> (base + 1).coerceAtMost(7)
      PowerProfile.HIGH_QUALITY -> (base + 2).coerceAtMost(8)
    }
    return (0 until count).map { idx ->
      VisualUnit(asset, frameSec = durSec * (idx + 0.5f) / count)
    }
  }

  private fun drawSheet(context: Context, units: List<VisualUnit>, pageIndex: Int): Bitmap {
    val sheet = Bitmap.createBitmap(CELL_W * 3, CELL_H * 3, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet).apply { drawColor(Color.BLACK) }
    units.forEachIndexed { i, unit ->
      val col = i % 3
      val row = i / 3
      val x = col * CELL_W
      val y = row * CELL_H
      renderCell(context, canvas, unit, x, y, i + 1)
    }
    drawPageBadge(canvas, pageIndex)
    return sheet
  }

  private fun renderCell(context: Context, canvas: Canvas, unit: VisualUnit, x: Int, y: Int, index: Int) {
    val bmp = if (unit.frameSec != null) {
      MediaLoader.loadVideoFrame(context, unit.asset, unit.frameSec, maxSide = maxOf(CELL_W, CELL_H))
    } else {
      MediaLoader.loadImage(context, unit.asset, maxSide = maxOf(CELL_W, CELL_H))
    }
    if (bmp != null) {
      val src = cropRect(bmp)
      val dst = Rect(x, y, x + CELL_W, y + CELL_H)
      canvas.drawBitmap(bmp, src, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
      bmp.recycle()
    } else {
      Paint().apply { color = Color.DKGRAY }.also {
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + CELL_W).toFloat(), (y + CELL_H).toFloat(), it)
      }
    }
    drawCellBadge(canvas, x.toFloat(), y.toFloat(), index, unit)
  }

  private fun cropRect(bitmap: Bitmap): Rect {
    val dstAspect = CELL_W.toFloat() / CELL_H.toFloat()
    val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
    return if (srcAspect > dstAspect) {
      val cropW = (bitmap.height * dstAspect).toInt().coerceAtLeast(1)
      val left = ((bitmap.width - cropW) / 2).coerceAtLeast(0)
      Rect(left, 0, (left + cropW).coerceAtMost(bitmap.width), bitmap.height)
    } else {
      val cropH = (bitmap.width / dstAspect).toInt().coerceAtLeast(1)
      val top = ((bitmap.height - cropH) / 2).coerceAtLeast(0)
      Rect(0, top, bitmap.width, (top + cropH).coerceAtMost(bitmap.height))
    }
  }

  private fun drawCellBadge(canvas: Canvas, x: Float, y: Float, index: Int, unit: VisualUnit) {
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 255, 122, 24) }
    val rect = RectF(x + 8f, y + 8f, x + 118f, y + 48f)
    canvas.drawRoundRect(rect, 18f, 18f, bg)
    val text = buildString {
      append(index)
      if (unit.asset.mediaType != MediaType.IMAGE) {
        append(' ')
        append("%.1fs".format(Locale.US, unit.frameSec ?: 0f))
      }
    }
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = 20f
      textAlign = Paint.Align.CENTER
    }
    val cy = rect.centerY() - (tp.descent() + tp.ascent()) / 2f
    canvas.drawText(text, rect.centerX(), cy, tp)

    val typePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.argb(205, 0, 0, 0)
      textSize = 16f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val kind = when (unit.asset.mediaType) {
      MediaType.IMAGE -> "IMG"
      MediaType.VIDEO -> "VID"
      MediaType.LIVE_PHOTO -> "LIVE"
    }
    canvas.drawText(kind, x + 10f, y + CELL_H - 14f, typePaint)
  }

  private fun drawPageBadge(canvas: Canvas, pageIndex: Int) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.argb(190, 0, 0, 0)
      textSize = 22f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawText("page $pageIndex", 12f, (CELL_H * 3 - 18).toFloat(), paint)
  }

  private fun <T> selectSpread(values: List<T>, limit: Int): List<T> {
    if (values.size <= limit) return values
    return (0 until limit).map { i ->
      val idx = ((i + 0.5f) * values.size / limit).roundToInt().coerceIn(0, values.lastIndex)
      values[idx]
    }.distinct()
  }
}
