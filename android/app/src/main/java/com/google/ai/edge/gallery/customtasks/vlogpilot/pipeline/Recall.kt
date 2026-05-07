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
    nBlueprint: Int,                            // total shots in DirectorBrief.shotBlueprint
    eventAssets: List<Asset>,
    perceptions: Map<String, Perception>,
    queryEmbedding: FloatArray,                 // CLIP text-encoded visual_requirements
    excludedAssetIds: Set<String> = emptySet(), // already used by another shot in this timeline
    k: Int = 8,
  ): List<Candidate> {
    val sorted = eventAssets.sortedBy { it.takenEpochMs }
    if (sorted.isEmpty()) return emptyList()

    // Position-derived time window: shot at position p of N → centered at (p-0.5)/N along the
    // event's time axis. Earlier ports divided by `request.position` instead of `nBlueprint`,
    // collapsing every shot toward the end of the event.
    val tStart = sorted.first().takenEpochMs
    val tEnd = sorted.last().takenEpochMs
    val span = (tEnd - tStart).coerceAtLeast(1L)
    val pNorm = ((request.position - 0.5f) / kotlin.math.max(1, nBlueprint).toFloat()).coerceIn(0f, 1f)
    val targetMs = tStart + (span * pNorm).toLong()
    val windowMs = (span * 0.4f).toLong().coerceAtLeast(60_000L) // ≥1 min flex window
    val personConstraint = normalizePersonConstraint(request.personConstraint)
    val wantsNoPerson = request.personConstraint?.trim() == "无人物"

    // Score weights: when CLIP is available the dominant signal is text-image cosine, otherwise
    // (model not yet OTA-shipped) we fall back to a quality + duration-fit blend so callers still
    // get something better than random.
    val clipAvailable = queryEmbedding.isNotEmpty()

    return sorted
      .asSequence()
      .filter { it.id !in excludedAssetIds }
      .filter { perceptions[it.id]?.isJunk != true }
      .filter { asset ->
        if (!wantsNoPerson) return@filter true
        val faces = perceptions[asset.id]?.faces ?: return@filter false
        faces.none { !it.personId.isNullOrBlank() }
      }
      .map { asset ->
        val perc = perceptions[asset.id] ?: return@map Candidate(asset, blank(asset.id), 0f)
        val timeDelta = kotlin.math.abs(asset.takenEpochMs - targetMs)
        val timeFit = (1f - (timeDelta.toFloat() / windowMs.coerceAtLeast(1L).toFloat())).coerceIn(0f, 1f)
        val personFit = personFit(perc, personConstraint)
        val clipScore = if (clipAvailable && perc.clipEmbedding.isNotEmpty()) {
          cosine(queryEmbedding, perc.clipEmbedding.toFloatArray())
        } else 0f
        val durationFit = durationCompat(asset, request.durationSec)
        val score = if (clipAvailable) {
          clipScore * 0.58f + timeFit * 0.12f + personFit * 0.12f + perc.sharpness * 0.08f + durationFit * 0.10f
        } else {
          perc.sharpness * 0.35f + durationFit * 0.25f + timeFit * 0.15f + personFit * 0.15f +
            roleBonus(asset, perc, request.role) * 0.10f
        }
        Candidate(asset, perc, score)
      }
      .sortedByDescending { it.score }
      .take(k)
      .toList()
      .ifEmpty {
        relaxedFallback(request, eventAssets, perceptions, excludedAssetIds, k)
      }
  }

  /** Soft role-aware bonus used when CLIP isn't available. Mirrors pc-pilot's recall heuristics:
   *  portrait roles favor frames with detected faces; action/climax favor video clips. */
  private fun roleBonus(
    asset: Asset,
    perception: Perception,
    role: com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRole,
  ): Float {
    val isVideo = asset.mediaType != com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType.IMAGE
    return when (role) {
      com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRole.PORTRAIT ->
        if (perception.faces.isNotEmpty()) 0.8f else 0.1f
      com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRole.ACTION,
      com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRole.CLIMAX -> if (isVideo) 0.7f else 0.2f
      com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRole.CLOSING -> 0.4f
      else -> 0.3f
    }
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

  private fun personFit(perception: Perception, constraint: String?): Float {
    if (constraint.isNullOrBlank()) return 0.5f
    val ids = perception.faces.mapNotNull { it.personId?.uppercase() }.toSet()
    return if (constraint in ids) 1.0f else 0.0f
  }

  private fun normalizePersonConstraint(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    if (s.isBlank() || s.equals("null", ignoreCase = true) || s == "无人物") return null
    val token = Regex("[A-Za-z]+\\s*$").find(s)?.value?.trim()?.uppercase() ?: return null
    return token.removePrefix("PERSON").trim('_', '-', ' ').takeIf { it.isNotBlank() }
  }

  private fun relaxedFallback(
    request: ShotRequest,
    eventAssets: List<Asset>,
    perceptions: Map<String, Perception>,
    excludedAssetIds: Set<String>,
    k: Int,
  ): List<Candidate> =
    eventAssets
      .asSequence()
      .filter { it.id !in excludedAssetIds }
      .mapNotNull { asset ->
        val perc = perceptions[asset.id] ?: return@mapNotNull null
        if (perc.isJunk) return@mapNotNull null
        val score = perc.sharpness * 0.45f + durationCompat(asset, request.durationSec) * 0.35f +
          roleBonus(asset, perc, request.role) * 0.20f
        Candidate(asset, perc, score)
      }
      .sortedByDescending { it.score }
      .take(k)
      .toList()

  private fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size || a.isEmpty()) return 0f
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
  }
}
