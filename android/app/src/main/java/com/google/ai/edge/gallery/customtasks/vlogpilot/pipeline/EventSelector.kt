/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Event-level gate before any expensive agent/VLM work. The selector ranks
 * full events, not individual assets, so compute is spent on coherent and
 * footage-rich moments rather than simply the newest MediaStore cluster.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object EventSelector {

  data class Candidate(
    val event: Event,
    val assets: List<Asset>,
    val valueScore: Float,
    val travelScore: Float,
    val mediaScore: Float,
    val storyScore: Float,
    val qualityScore: Float,
    val recencyScore: Float,
    val realVideoCount: Int,
    val longVideoCount: Int,
    val realVideoSeconds: Float,
    val gpsAssetCount: Int,
    val spanHours: Float,
    val reasons: List<String>,
  ) {
    val eventId: String get() = event.eventId

    fun compactSummary(): String =
      buildString {
        append(eventId)
        append(":value="); append(percent(valueScore))
        append(" travel="); append(percent(travelScore))
        append(" media="); append(percent(mediaScore))
        append(" story="); append(percent(storyScore))
        append(" assets="); append(assets.size)
        append(" videos="); append(realVideoCount)
        append('/'); append(realVideoSeconds.roundToInt()); append('s')
        append(" gps="); append(gpsAssetCount)
        append(" span="); append(spanHours.roundToInt()); append('h')
        if (reasons.isNotEmpty()) {
          append(" reason=")
          append(reasons.joinToString("+"))
        }
      }
  }

  fun rank(
    events: List<Event>,
    assetMap: Map<String, Asset>,
    perceptionFor: (String) -> Perception?,
    nowMs: Long = System.currentTimeMillis(),
  ): List<Candidate> =
    events.map { event ->
      val assets = event.assetIds.mapNotNull { assetMap[it] }
      score(event, assets, perceptionFor, nowMs)
    }.sortedWith(
      compareByDescending<Candidate> { it.valueScore }
        .thenByDescending { it.mediaScore }
        .thenByDescending { it.travelScore }
        .thenByDescending { it.event.endEpochMs },
    )

  private fun score(
    event: Event,
    assets: List<Asset>,
    perceptionFor: (String) -> Perception?,
    nowMs: Long,
  ): Candidate {
    if (assets.isEmpty()) {
      return Candidate(
        event = event,
        assets = assets,
        valueScore = 0f,
        travelScore = 0f,
        mediaScore = 0f,
        storyScore = 0f,
        qualityScore = 0f,
        recencyScore = 0f,
        realVideoCount = 0,
        longVideoCount = 0,
        realVideoSeconds = 0f,
        gpsAssetCount = 0,
        spanHours = 0f,
        reasons = listOf("empty"),
      )
    }

    val perceptions = assets.associate { it.id to perceptionFor(it.id) }
    val travelScore = TravelSceneScorer.eventScore(event, assets) { perceptions[it] }
    val videoAssets = assets.filter { it.mediaType == MediaType.VIDEO }
    val livePhotoCount = assets.count { it.mediaType == MediaType.LIVE_PHOTO }
    val realVideoSeconds = videoAssets.sumOf { it.durationMs.coerceAtLeast(0L) }.toFloat() / 1000f
    val meaningfulVideoCount = videoAssets.count { it.durationMs >= 5_000L }
    val longVideoCount = videoAssets.count { it.durationMs >= 15_000L }
    val mediaScore = mediaScore(
      realVideoSeconds = realVideoSeconds,
      meaningfulVideoCount = meaningfulVideoCount,
      longVideoCount = longVideoCount,
      livePhotoCount = livePhotoCount,
    )
    val spanHours = ((event.endEpochMs - event.startEpochMs).coerceAtLeast(0L) / 3_600_000f)
    val storyScore = storyScore(assets, spanHours)
    val qualityScore = qualityScore(perceptions.values.filterNotNull())
    val ageDays = ((nowMs - event.endEpochMs).coerceAtLeast(0L) / 86_400_000f)
    val recencyScore = (1f - ageDays / 30f).coerceIn(0f, 1f)
    val gpsAssetCount = assets.count { it.latitude != null && it.longitude != null }
    val lowSignalPenalty = lowSignalPenalty(
      assets = assets,
      travelScore = travelScore,
      realVideoSeconds = realVideoSeconds,
      gpsAssetCount = gpsAssetCount,
    )
    val valueScore = (
      travelScore * 0.30f +
        mediaScore * 0.30f +
        storyScore * 0.18f +
        qualityScore * 0.12f +
        recencyScore * 0.10f -
        lowSignalPenalty
      ).coerceIn(0f, 1f)

    return Candidate(
      event = event,
      assets = assets,
      valueScore = valueScore,
      travelScore = travelScore,
      mediaScore = mediaScore,
      storyScore = storyScore,
      qualityScore = qualityScore,
      recencyScore = recencyScore,
      realVideoCount = videoAssets.size,
      longVideoCount = longVideoCount,
      realVideoSeconds = realVideoSeconds,
      gpsAssetCount = gpsAssetCount,
      spanHours = spanHours,
      reasons = reasons(
        assets = assets,
        travelScore = travelScore,
        mediaScore = mediaScore,
        storyScore = storyScore,
        realVideoSeconds = realVideoSeconds,
        longVideoCount = longVideoCount,
        gpsAssetCount = gpsAssetCount,
      ),
    )
  }

  private fun mediaScore(
    realVideoSeconds: Float,
    meaningfulVideoCount: Int,
    longVideoCount: Int,
    livePhotoCount: Int,
  ): Float {
    val durationScore = (realVideoSeconds / 120f).coerceIn(0f, 1f)
    val countScore = (meaningfulVideoCount / 4f).coerceIn(0f, 1f)
    val longVideoScore = (longVideoCount / 2f).coerceIn(0f, 1f)
    val livePhotoScore = (livePhotoCount / 8f).coerceIn(0f, 0.35f)
    return (durationScore * 0.45f +
      countScore * 0.35f +
      longVideoScore * 0.15f +
      livePhotoScore * 0.05f).coerceIn(0f, 1f)
  }

  private fun storyScore(assets: List<Asset>, spanHours: Float): Float {
    val volumeScore = (assets.size / 18f).coerceIn(0f, 1f)
    val spanScore = when {
      spanHours < 0.25f -> 0.08f
      spanHours <= 8f -> (spanHours / 8f).coerceIn(0f, 1f)
      spanHours <= 24f -> 1f
      else -> (1f - (spanHours - 24f) / 72f).coerceIn(0.25f, 1f)
    }
    val timeBuckets = assets.map { ((it.takenEpochMs / 3_600_000L) / 3L) }.distinct().size
    val timeDiversityScore = (timeBuckets / 4f).coerceIn(0f, 1f)
    val hasStill = assets.any { it.mediaType == MediaType.IMAGE || it.mediaType == MediaType.LIVE_PHOTO }
    val hasVideo = assets.any { it.mediaType == MediaType.VIDEO }
    val mixedMediaScore = if (hasStill && hasVideo) 1f else 0.4f
    return (volumeScore * 0.35f +
      spanScore * 0.25f +
      timeDiversityScore * 0.25f +
      mixedMediaScore * 0.15f).coerceIn(0f, 1f)
  }

  private fun qualityScore(perceptions: List<Perception>): Float {
    if (perceptions.isEmpty()) return 0.5f
    val usableScore = perceptions.count { !it.isJunk }.toFloat() / perceptions.size
    val sharpnessScore = perceptions.map { it.sharpness.coerceIn(0f, 1f) }.average().toFloat()
    val exposureScore = perceptions.map {
      (1f - abs(it.brightness.coerceIn(0f, 1f) - 0.5f) / 0.5f).coerceIn(0f, 1f)
    }.average().toFloat()
    return (usableScore * 0.45f + sharpnessScore * 0.35f + exposureScore * 0.20f).coerceIn(0f, 1f)
  }

  private fun lowSignalPenalty(
    assets: List<Asset>,
    travelScore: Float,
    realVideoSeconds: Float,
    gpsAssetCount: Int,
  ): Float {
    val mostlyMotionPhotos = assets.count { it.mediaType == MediaType.LIVE_PHOTO }.toFloat() / assets.size >= 0.5f
    return when {
      realVideoSeconds < 3f && travelScore < 0.15f && gpsAssetCount == 0 -> 0.12f
      mostlyMotionPhotos && travelScore < 0.18f && gpsAssetCount == 0 -> 0.10f
      realVideoSeconds < 10f && travelScore < 0.12f && assets.size < 5 -> 0.08f
      else -> 0f
    }
  }

  private fun reasons(
    assets: List<Asset>,
    travelScore: Float,
    mediaScore: Float,
    storyScore: Float,
    realVideoSeconds: Float,
    longVideoCount: Int,
    gpsAssetCount: Int,
  ): List<String> {
    val out = mutableListOf<String>()
    if (travelScore >= 0.35f) out += "travel"
    if (gpsAssetCount > 0) out += "gps"
    if (realVideoSeconds >= 30f) out += "video"
    if (longVideoCount > 0) out += "long-video"
    if (assets.size >= 10) out += "many-assets"
    if (storyScore >= 0.65f) out += "story-span"
    if (mediaScore < 0.08f && travelScore < 0.15f && gpsAssetCount == 0) out += "low-signal"
    return out
  }

  private fun percent(v: Float): String =
    String.format(Locale.ROOT, "%02d", (v.coerceIn(0f, 1f) * 100f).roundToInt())
}
