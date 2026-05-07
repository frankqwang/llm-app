/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Rule-based candidate recall for the editor agent. For a given ShotRequest,
 * filter the event's perceptions by quality + person + time-window, then sort
 * by CLIP text-image similarity to `visual_requirements`. The top-K
 * (default 8) candidates are returned for VLM curate.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRequest

object Recall {

  data class Candidate(val asset: Asset, val perception: Perception, val score: Float)

  fun topK(
    request: ShotRequest,
    eventAssets: List<Asset>,
    perceptions: Map<String, Perception>,
    queryEmbedding: FloatArray,                 // CLIP text-encoded visual_requirements
    excludedAssetIds: Set<String> = emptySet(), // already used by another shot in this timeline
    k: Int = 8,
  ): List<Candidate> {
    val sorted = eventAssets.sortedBy { it.takenEpochMs }
    if (sorted.isEmpty()) return emptyList()

    // Position-derived time window: shot at position p of N → middle of (p-0.5)/N..(p+0.5)/N range,
    // expanded ±25% to allow flexibility.
    val tStart = sorted.first().takenEpochMs
    val tEnd = sorted.last().takenEpochMs
    val span = (tEnd - tStart).coerceAtLeast(1L)
    val pNorm = ((request.position - 0.5f) / kotlin.math.max(1, request.position).toFloat()).coerceIn(0f, 1f)
    val targetMs = tStart + (span * pNorm).toLong()
    val windowMs = (span * 0.4f).toLong().coerceAtLeast(60_000L) // ≥1 min flex window

    return sorted
      .asSequence()
      .filter { it.id !in excludedAssetIds }
      .filter { perceptions[it.id]?.isJunk != true }
      .filter { kotlin.math.abs(it.takenEpochMs - targetMs) <= windowMs }
      .filter { asset ->
        val pc = request.personConstraint ?: return@filter true
        val faces = perceptions[asset.id]?.faces ?: return@filter false
        faces.any { it.personId == pc }
      }
      .map { asset ->
        val perc = perceptions[asset.id] ?: return@map Candidate(asset, blank(asset.id), 0f)
        val clipScore = if (queryEmbedding.isNotEmpty() && perc.clipEmbedding.isNotEmpty()) {
          cosine(queryEmbedding, perc.clipEmbedding.toFloatArray())
        } else 0f
        val durationFit = durationCompat(asset, request.durationSec)
        Candidate(asset, perc, clipScore * 0.7f + perc.sharpness * 0.15f + durationFit * 0.15f)
      }
      .sortedByDescending { it.score }
      .take(k)
      .toList()
  }

  /** A 1.0 when the source can stretch to the requested duration without freezing,
   *  falling off as freeze risk grows. Pure images return 0.6 (always renderable as Ken Burns). */
  private fun durationCompat(asset: Asset, requestedSec: Float): Float {
    val durMs = asset.durationMs
    if (durMs <= 0) return 0.6f                     // image
    val durSec = durMs / 1000f
    val ratio = durSec / requestedSec               // ≥1: no slow-mo needed
    return when {
      ratio >= 1.0f -> 1.0f
      ratio >= 0.66f -> 0.85f                       // <=1.5x slow-mo
      ratio >= 0.4f -> 0.6f                         // <=2.5x slow-mo (highfps only)
      else -> 0.2f
    }
  }

  private fun blank(id: String) = Perception(assetId = id, isJunk = true, junkReason = "missing_perception")

  private fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size || a.isEmpty()) return 0f
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
  }
}
