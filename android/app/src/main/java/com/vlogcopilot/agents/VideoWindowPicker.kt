/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Video trim picker for selected video / live-photo shots. It uses the VLM
 * multi-frame pass as the semantic anchor, then scores local candidate windows
 * with sharpness, role-position fit, scene-cut stability, and bad-frame avoid
 * signals. This keeps one VLM call per video while avoiding the old "always
 * center the sharpest frame" behavior.
 */
package com.vlogcopilot.agents

import android.content.Context
import com.vlogcopilot.perception.ImageQuality
import com.vlogcopilot.perception.MediaLoader
import com.vlogcopilot.schemas.Asset
import com.vlogcopilot.schemas.MediaType
import com.vlogcopilot.schemas.Perception
import com.vlogcopilot.schemas.ShotRole
import kotlin.math.abs

object VideoWindowPicker {

  private const val N_SAMPLES = 8

  fun pick(
    context: Context,
    asset: Asset,
    wantDurSec: Float,
    perception: Perception?,
    role: ShotRole,
  ): Float {
    if (asset.mediaType == MediaType.IMAGE) return 0f
    val durMs = asset.durationMs
    if (durMs <= 0) return 0f
    val durSec = durMs / 1000f
    if (durSec <= wantDurSec) return 0f

    val maxStart = (durSec - wantDurSec).coerceAtLeast(0f)
    val frames = MediaLoader.sampleVideoFrames(context, asset, N_SAMPLES, maxSide = 360)
    if (frames.isEmpty()) return semanticFallbackStart(perception, maxStart, wantDurSec)

    val scoredFrames = frames.map { (tSec, bmp) ->
      val sharpness = ImageQuality.measure(bmp).sharpness
      runCatching { bmp.recycle() }
      FrameScore(tSec, sharpness)
    }
    val maxSharpness = scoredFrames.maxOfOrNull { it.sharpness }?.coerceAtLeast(0.001f) ?: 0.001f

    val bestMomentSec = perception?.bestMomentSecOrNull()
    val badMoments = perception?.badMomentSeconds().orEmpty()
    val starts = candidateStarts(
      durSec = durSec,
      wantDurSec = wantDurSec,
      maxStart = maxStart,
      frames = scoredFrames,
      perception = perception,
      role = role,
      bestMomentSec = bestMomentSec,
    )

    var bestStart = starts.firstOrNull() ?: 0f
    var bestScore = Float.NEGATIVE_INFINITY
    for (start in starts) {
      val end = start + wantDurSec
      val score = windowScore(
        start = start,
        end = end,
        durSec = durSec,
        role = role,
        frames = scoredFrames,
        maxSharpness = maxSharpness,
        bestMomentSec = bestMomentSec,
        badMoments = badMoments,
        sceneCuts = perception?.sceneCuts.orEmpty(),
      )
      if (score > bestScore) {
        bestScore = score
        bestStart = start
      }
    }
    return bestStart.coerceIn(0f, maxStart)
  }

  private fun candidateStarts(
    durSec: Float,
    wantDurSec: Float,
    maxStart: Float,
    frames: List<FrameScore>,
    perception: Perception?,
    role: ShotRole,
    bestMomentSec: Float?,
  ): List<Float> {
    val starts = mutableListOf<Float>()
    fun addCentered(tSec: Float) {
      starts += (tSec - wantDurSec / 2f).coerceIn(0f, maxStart)
    }
    frames.forEach { addCentered(it.tSec) }
    bestMomentWindowSec(perception)?.let { (startSec, endSec) ->
      starts += startSec.coerceIn(0f, maxStart)
      starts += (endSec - wantDurSec).coerceIn(0f, maxStart)
      starts += (((startSec + endSec) / 2f) - wantDurSec / 2f).coerceIn(0f, maxStart)
    }
    bestMomentSec?.let(::addCentered)
    starts += (maxStart * roleTarget(role)).coerceIn(0f, maxStart)
    starts += 0f
    starts += maxStart

    for (cut in perception?.sceneCuts.orEmpty()) {
      starts += (cut + 0.15f).coerceIn(0f, maxStart)
      starts += (cut - wantDurSec - 0.15f).coerceIn(0f, maxStart)
    }

    return starts
      .map { (it * 10f).toInt() / 10f }
      .distinct()
      .sorted()
      .ifEmpty { listOf(((durSec - wantDurSec) / 2f).coerceIn(0f, maxStart)) }
  }

  private fun windowScore(
    start: Float,
    end: Float,
    durSec: Float,
    role: ShotRole,
    frames: List<FrameScore>,
    maxSharpness: Float,
    bestMomentSec: Float?,
    badMoments: List<Float>,
    sceneCuts: List<Float>,
  ): Float {
    val inWindow = frames.filter { it.tSec in start..end }
    val sharpnessFit = ((inWindow.maxOfOrNull { it.sharpness } ?: nearestFrame(frames, (start + end) / 2f).sharpness) / maxSharpness)
      .coerceIn(0f, 1f)

    val center = (start + end) / 2f
    val semanticFit = bestMomentSec?.let {
      val distance = if (it in start..end) 0f else minOf(abs(it - start), abs(it - end))
      (1f - distance / end.minus(start).coerceAtLeast(0.1f)).coerceIn(0f, 1f)
    } ?: 0.35f

    val positionNorm = (center / durSec.coerceAtLeast(0.1f)).coerceIn(0f, 1f)
    val positionFit = (1f - abs(positionNorm - roleTarget(role)) / 0.55f).coerceIn(0f, 1f)

    val badPenalty = badMoments.count { it in start..end } * 0.30f
    val innerStart = start + (end - start) * 0.18f
    val innerEnd = end - (end - start) * 0.18f
    val cutPenalty = sceneCuts.count { it in innerStart..innerEnd } * 0.16f

    return semanticFit * 0.40f +
      sharpnessFit * 0.27f +
      positionFit * 0.18f +
      boundaryCutBonus(start, end, sceneCuts) * 0.07f -
      badPenalty -
      cutPenalty
  }

  private fun semanticFallbackStart(perception: Perception?, maxStart: Float, wantDurSec: Float): Float {
    val best = perception?.bestMomentSecOrNull()
    if (best != null) return (best - wantDurSec / 2f).coerceIn(0f, maxStart)
    return (maxStart / 2f).coerceIn(0f, maxStart)
  }

  private fun Perception.bestMomentSecOrNull(): Float? {
    val explicit = videoInsight.bestMomentSec.takeIf { it > 0f }
    if (explicit != null) return explicit
    val index = videoInsight.bestMomentIndex.takeIf { it > 0 } ?: return null
    return videoInsight.frameTimestampsSec.getOrNull(index - 1)
  }

  private fun Perception.badMomentSeconds(): List<Float> =
    videoInsight.badMomentIndices.mapNotNull { videoInsight.frameTimestampsSec.getOrNull(it - 1) }

  private fun bestMomentWindowSec(perception: Perception?): Pair<Float, Float>? {
    val insight = perception?.videoInsight ?: return null
    val startIdx = insight.bestMomentWindowStart.takeIf { it > 0 } ?: return null
    val endIdx = insight.bestMomentWindowEnd.takeIf { it > 0 } ?: startIdx
    val a = insight.frameTimestampsSec.getOrNull(minOf(startIdx, endIdx) - 1) ?: return null
    val b = insight.frameTimestampsSec.getOrNull(maxOf(startIdx, endIdx) - 1) ?: a
    return minOf(a, b) to maxOf(a, b)
  }

  private fun nearestFrame(frames: List<FrameScore>, targetSec: Float): FrameScore =
    frames.minByOrNull { abs(it.tSec - targetSec) } ?: FrameScore(targetSec, 0f)

  private fun roleTarget(role: ShotRole): Float = when (role) {
    ShotRole.OPENING -> 0.12f
    ShotRole.ESTABLISHING -> 0.16f
    ShotRole.PORTRAIT -> 0.45f
    ShotRole.ACTION -> 0.56f
    ShotRole.CLIMAX -> 0.70f
    ShotRole.TRANSITION -> 0.50f
    ShotRole.CLOSING -> 0.84f
  }

  private fun boundaryCutBonus(start: Float, end: Float, sceneCuts: List<Float>): Float {
    if (sceneCuts.isEmpty()) return 0f
    val nearStart = sceneCuts.any { abs(it - start) <= 0.35f }
    val nearEnd = sceneCuts.any { abs(it - end) <= 0.35f }
    return when {
      nearStart && nearEnd -> 1f
      nearStart || nearEnd -> 0.5f
      else -> 0f
    }
  }

  private data class FrameScore(val tSec: Float, val sharpness: Float)
}
