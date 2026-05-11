/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Wrapper around FFprobeKit. Returns duration in seconds and average fps for
 * a media file (image returns dur=0, fps=0). Used by render to decide between
 * normal speed, ≤1.5x slow-mo, or ≤2.5x slow-mo for ≥48fps high-fps source.
 */
package com.vlogcopilot.render

import com.arthenica.ffmpegkit.FFprobeKit

object FFmpegProbe {

  data class Probe(val durationSec: Float, val fps: Float, val widthPx: Int, val heightPx: Int)

  fun probe(path: String): Probe {
    return try {
      val session = FFprobeKit.getMediaInformation(path)
      val info = session.mediaInformation ?: return Probe(0f, 0f, 0, 0)
      val dur = info.duration?.toFloatOrNull() ?: 0f
      var fps = 0f
      var w = 0; var h = 0
      info.streams.firstOrNull { it.type == "video" }?.let { v ->
        v.averageFrameRate?.let { fps = parseFraction(it.toString()) }
        if (fps == 0f) v.realFrameRate?.let { fps = parseFraction(it.toString()) }
        v.width?.let { w = it.toInt() }
        v.height?.let { h = it.toInt() }
      }
      Probe(dur, fps, w, h)
    } catch (_: Throwable) {
      Probe(0f, 0f, 0, 0)
    }
  }

  private fun parseFraction(s: String): Float {
    val parts = s.split("/")
    return when (parts.size) {
      1 -> s.toFloatOrNull() ?: 0f
      2 -> {
        val n = parts[0].toFloatOrNull() ?: return 0f
        val d = parts[1].toFloatOrNull() ?: return 0f
        if (d == 0f) 0f else n / d
      }
      else -> 0f
    }
  }
}
