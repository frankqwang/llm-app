/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Build the per-event contact sheet that step3_montage feeds the VLM. Each
 * cell is a thumbnail with a numbered badge in the top-left so the agent can
 * reference cells by index. The sheet's longest side is capped to 4096 px;
 * if the asset count would exceed that we split into two sheets (caller will
 * have to merge agent outputs).
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
import com.vlogcopilot.schemas.Asset
import kotlin.math.ceil
import kotlin.math.sqrt

object MontageBuilder {

  private const val CELL_W = 360
  private const val CELL_H = 480
  private const val MAX_SIDE = 4096

  data class Sheet(val bitmap: Bitmap, val assetIds: List<String>)

  /** Build one or more contact sheets covering all assets in the event. */
  fun build(context: Context, assets: List<Asset>): List<Sheet> {
    if (assets.isEmpty()) return emptyList()

    // First pass: how many cells fit per sheet at MAX_SIDE constraint?
    val cellsPerSheet = run {
      val sideCells = (MAX_SIDE / CELL_W).coerceAtLeast(2)
      sideCells * sideCells
    }
    val groups = assets.chunked(cellsPerSheet)

    return groups.map { chunk ->
      val gridSide = ceil(sqrt(chunk.size.toDouble())).toInt().coerceAtLeast(1)
      val rows = ceil(chunk.size.toDouble() / gridSide).toInt()
      val sheetW = gridSide * CELL_W
      val sheetH = rows * CELL_H
      val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(sheet).apply { drawColor(Color.BLACK) }
      chunk.forEachIndexed { i, asset ->
        val col = i % gridSide
        val row = i / gridSide
        val cx = col * CELL_W
        val cy = row * CELL_H
        renderCell(context, canvas, asset, cx, cy, i + 1)
      }
      Sheet(sheet, chunk.map { it.id })
    }
  }

  private fun renderCell(context: Context, canvas: Canvas, asset: Asset, x: Int, y: Int, badgeIndex: Int) {
    val cell = MediaLoader.loadImage(context, asset, maxSide = maxOf(CELL_W, CELL_H))
    if (cell != null) {
      val src = Rect(0, 0, cell.width, cell.height)
      val dst = Rect(x, y, x + CELL_W, y + CELL_H)
      canvas.drawBitmap(cell, src, dst, Paint(Paint.ANTI_ALIAS_FLAG))
      // Cell pixels are now baked onto the sheet bitmap — drop the source.
      cell.recycle()
    } else {
      val placeholder = Paint().apply { color = Color.DKGRAY }
      canvas.drawRect(x.toFloat(), y.toFloat(), (x + CELL_W).toFloat(), (y + CELL_H).toFloat(), placeholder)
    }

    drawBadge(canvas, x.toFloat(), y.toFloat(), badgeIndex)
  }

  private fun drawBadge(canvas: Canvas, x: Float, y: Float, index: Int) {
    val pad = 14f
    val radius = 22f
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 122, 24) }
    val rect = RectF(x + 8f, y + 8f, x + 8f + radius * 2, y + 8f + radius * 2)
    canvas.drawRoundRect(rect, radius, radius, bg)
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = pad * 1.6f
      textAlign = Paint.Align.CENTER
    }
    val cx = rect.centerX()
    val cy = rect.centerY() - (tp.descent() + tp.ascent()) / 2
    canvas.drawText(index.toString(), cx, cy, tp)
  }
}
