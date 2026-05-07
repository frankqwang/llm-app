/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Sharpness-based clip-window picker. For a source video / live-photo, sample
 * 6 candidate frames evenly across the clip, score each by Laplacian variance
 * (ImageQuality.measure), and return a trim_start that centers the best frame
 * inside the requested window.
 *
 * Why not the VLM-based picker pc-pilot uses? On device, an extra Gemma 4 call
 * per shot would cost 8-15s; for a 5-shot timeline the wall-clock blow up is
 * 40-75s. Sharpness on its own catches motion blur + still frames in pans, the
 * two failure modes that "always take the middle" couldn't.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import android.content.Context
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.ImageQuality
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.MediaLoader
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType

object SharpestWindowPicker {

  private const val N_SAMPLES = 6

  /** @return seconds offset where the requested-duration window should start. */
  fun pick(context: Context, asset: Asset, wantDurSec: Float): Float {
    if (asset.mediaType == MediaType.IMAGE) return 0f
    val durMs = asset.durationMs
    if (durMs <= 0) return 0f
    val durSec = durMs / 1000f
    if (durSec <= wantDurSec) return 0f

    val frames = MediaLoader.sampleVideoFrames(context, asset, N_SAMPLES, maxSide = 360)
    if (frames.isEmpty()) return ((durSec - wantDurSec) / 2f).coerceAtLeast(0f)

    var bestT = frames.first().first
    var bestScore = -1f
    for ((tSec, bmp) in frames) {
      val s = ImageQuality.measure(bmp).sharpness
      if (s > bestScore) {
        bestScore = s
        bestT = tSec
      }
      runCatching { bmp.recycle() }
    }
    val rawStart = bestT - wantDurSec / 2f
    return rawStart.coerceIn(0f, (durSec - wantDurSec).coerceAtLeast(0f))
  }
}
