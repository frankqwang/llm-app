/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Tag-based candidate recall for the editor agent. For a given ShotRequest,
 * filter the event's perceptions by quality + time-window, score by token
 * overlap between request.visualRequirements/moodTarget and the asset's
 * VlmTags, then return the top-K (default 8) for VLM curation.
 *
 * Pre-v4 this file used CLIP text-image cosine for ranking — but the CLIP
 * TFLite was never OTA-shipped (no canonical sub-50MB int8 source), and the
 * fallback (sharpness only) ranked everything roughly equal. VlmTags from
 * VlmAnnotator carry the semantic signal CLIP was supposed to provide; token
 * overlap on the tag fields is a strong-enough surrogate for the ranking
 * stage (the editor still asks Gemma to make the final pick from top-K).
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRequest
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRole
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.VlmTags

object Recall {

  data class Candidate(val asset: Asset, val perception: Perception, val score: Float)

  fun topK(
    request: ShotRequest,
    nBlueprint: Int,                            // total shots in DirectorBrief.shotBlueprint
    eventAssets: List<Asset>,
    perceptions: Map<String, Perception>,
    excludedAssetIds: Set<String> = emptySet(), // already used by another shot in this timeline
    k: Int = 8,
  ): List<Candidate> {
    val sorted = eventAssets.sortedBy { it.takenEpochMs }
    if (sorted.isEmpty()) return emptyList()

    // Position-derived time window: shot at position p of N → centered at (p-0.5)/N along the
    // event's time axis.
    val tStart = sorted.first().takenEpochMs
    val tEnd = sorted.last().takenEpochMs
    val span = (tEnd - tStart).coerceAtLeast(1L)
    val pNorm = ((request.position - 0.5f) / kotlin.math.max(1, nBlueprint).toFloat()).coerceIn(0f, 1f)
    val targetMs = tStart + (span * pNorm).toLong()
    val windowMs = (span * 0.4f).toLong().coerceAtLeast(60_000L) // ≥1 min flex window

    // Pre-tokenize the request once so we don't re-split per asset.
    val requestTokens = tokenize(request.visualRequirements + " " + request.moodTarget)

    val scored = sorted
      .asSequence()
      .filter { it.id !in excludedAssetIds }
      .filter { perceptions[it.id]?.isJunk != true }
      .map { asset ->
        val perc = perceptions[asset.id] ?: return@map Candidate(asset, blank(asset.id), 0f)
        val tagOverlap = vlmOverlap(perc.vlmTags, requestTokens)
        val timeDelta = kotlin.math.abs(asset.takenEpochMs - targetMs)
        val timeFit = (1f - (timeDelta.toFloat() / windowMs.coerceAtLeast(1L).toFloat())).coerceIn(0f, 1f)
        val durationFit = durationCompat(asset, request.durationSec)
        val roleHintFit = roleHintMatch(perc.vlmTags.narrativeRoleHint, request.role)
        val faceBonus = if (request.role == ShotRole.PORTRAIT && perc.faces.isNotEmpty()) 0.20f else 0f
        // Score weights: VLM tag overlap is the dominant semantic signal (the analogue of
        // CLIP cosine pre-v4). Time fit + duration fit are structural correctness.
        // Sharpness breaks ties between otherwise-equal candidates.
        val score = tagOverlap * 0.45f +
          timeFit * 0.18f +
          durationFit * 0.15f +
          roleHintFit * 0.10f +
          perc.sharpness * 0.07f +
          faceBonus +
          if (perc.vlmTags.scene.isBlank()) -0.10f else 0f  // soft penalty: VLM didn't tag this
        Candidate(asset, perc, score)
      }
      .sortedByDescending { it.score }
      .take(k)
      .toList()

    return scored.ifEmpty { relaxedFallback(request, eventAssets, perceptions, excludedAssetIds, k) }
  }

  /**
   * Token-overlap score between [requestTokens] (from director's
   * visual_requirements + mood) and the asset's VlmTags. Range [0, 1].
   * Returns 0 when tags are empty (annotation pass didn't run / failed).
   */
  private fun vlmOverlap(tags: VlmTags, requestTokens: Set<String>): Float {
    if (requestTokens.isEmpty()) return 0f
    if (tags.scene.isBlank() && tags.subjects.isEmpty() &&
        tags.action.isBlank() && tags.mood.isBlank() && tags.salient.isBlank()) {
      return 0f
    }
    val tagText = buildString {
      append(tags.scene); append(' ')
      tags.subjects.forEach { append(it); append(' ') }
      append(tags.action); append(' ')
      append(tags.mood); append(' ')
      append(tags.timeFeel); append(' ')
      append(tags.salient)
    }
    val tagTokens = tokenize(tagText)
    if (tagTokens.isEmpty()) return 0f
    val intersection = requestTokens.count { it in tagTokens }
    // Normalize by request size so a 5-token request with 2 hits scores higher than a
    // 20-token request with 4 hits. Cap at 1.0.
    return (intersection.toFloat() / requestTokens.size).coerceIn(0f, 1f)
  }

  /**
   * Hint match between VLM-suggested narrative role and the slot's actual role.
   * Returns 1.0 when they agree, 0 otherwise. The VLM is wrong often enough
   * (it's guessing from one image) that we don't penalize a mismatch — just
   * reward agreement.
   */
  private fun roleHintMatch(hint: String, slotRole: ShotRole): Float =
    if (hint.isNotBlank() && hint.equals(slotRole.name, ignoreCase = true)) 1f else 0f

  /**
   * Last-resort recall when the strict filter returns nothing — drops the
   * time-window constraint and just scores by sharpness + duration_fit + tag
   * overlap. Better than dropping a director-planned slot.
   */
  private fun relaxedFallback(
    request: ShotRequest,
    eventAssets: List<Asset>,
    perceptions: Map<String, Perception>,
    excludedAssetIds: Set<String>,
    k: Int,
  ): List<Candidate> {
    val requestTokens = tokenize(request.visualRequirements + " " + request.moodTarget)
    return eventAssets
      .asSequence()
      .filter { it.id !in excludedAssetIds }
      .mapNotNull { asset ->
        val perc = perceptions[asset.id] ?: return@mapNotNull null
        if (perc.isJunk) return@mapNotNull null
        val score = vlmOverlap(perc.vlmTags, requestTokens) * 0.45f +
          perc.sharpness * 0.30f +
          durationCompat(asset, request.durationSec) * 0.25f
        Candidate(asset, perc, score)
      }
      .sortedByDescending { it.score }
      .take(k)
      .toList()
  }

  /**
   * A 1.0 when the source can stretch to the requested duration without freezing,
   * falling off as freeze risk grows. Pure images return 0.6 (always renderable
   * as Ken Burns).
   */
  private fun durationCompat(asset: Asset, requestedSec: Float): Float {
    if (asset.mediaType == MediaType.IMAGE) return 0.6f
    val durMs = asset.durationMs
    if (durMs <= 0) return 0.6f
    val durSec = durMs / 1000f
    val ratio = durSec / requestedSec
    return when {
      ratio >= 1.0f -> 1.0f
      ratio >= 0.66f -> 0.85f                       // <=1.5x slow-mo
      ratio >= 0.4f -> 0.6f                         // <=2.5x slow-mo (highfps only)
      else -> 0.2f
    }
  }

  /**
   * Cheap token splitter for Chinese + English. Splits on whitespace + punctuation,
   * additionally breaks Chinese strings into 1-and-2-char shingles since BPE-free
   * Chinese is hard to tokenize otherwise. Returns lower-cased set so case doesn't
   * matter for English overlap.
   */
  private fun tokenize(text: String): Set<String> {
    if (text.isBlank()) return emptySet()
    val out = mutableSetOf<String>()
    // Whitespace + punctuation split for Latin chunks
    val pieces = text.lowercase().split(Regex("[\\s,，。:：;；、!?！？\"'“”‘’()（）/·…—\\-]+"))
    for (piece in pieces) {
      if (piece.isBlank()) continue
      // Pure Latin/digit token: keep as-is
      if (piece.all { it.code < 128 }) {
        if (piece.length >= 2) out += piece
        continue
      }
      // Chinese / mixed: emit single-char + bigram shingles. Stop chars are skipped above.
      val chars = piece.toCharArray()
      for (i in chars.indices) {
        val ch = chars[i].toString()
        if (ch.isBlank()) continue
        out += ch
        if (i + 1 < chars.size) {
          val bi = ch + chars[i + 1]
          if (!bi.any { it.isWhitespace() }) out += bi
        }
      }
    }
    return out
  }

  private fun blank(id: String) = Perception(assetId = id, isJunk = true, junkReason = "missing_perception")
}
