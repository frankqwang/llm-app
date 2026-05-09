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
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.EventScout
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.GenerationIntent
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
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
    val scout: EventScout? = null,
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
    scoutFor: (String) -> EventScout? = { null },
    intent: GenerationIntent = GenerationIntent.AUTO,
    nowMs: Long = System.currentTimeMillis(),
  ): List<Candidate> =
    events.map { event ->
      val assets = event.assetIds.mapNotNull { assetMap[it] }
      score(event, assets, perceptionFor, scoutFor(event.eventId), intent, nowMs)
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
    scout: EventScout?,
    intent: GenerationIntent,
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
        scout = scout,
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
    val intentScore = intentScore(intent, assets, perceptions)
    val scoutScore = scoutScore(intent, scout)
    val ageDays = ((nowMs - event.endEpochMs).coerceAtLeast(0L) / 86_400_000f)
    val recencyScore = (1f - ageDays / 90f).coerceIn(0f, 1f)
    val gpsAssetCount = assets.count { it.latitude != null && it.longitude != null }
    val lowSignalPenalty = lowSignalPenalty(
      assets = assets,
      travelScore = travelScore,
      realVideoSeconds = realVideoSeconds,
      gpsAssetCount = gpsAssetCount,
    )
    val baseScore = (
      travelScore * 0.30f +
        mediaScore * 0.30f +
        storyScore * 0.18f +
        qualityScore * 0.12f +
        recencyScore * 0.10f -
        lowSignalPenalty
      ).coerceIn(0f, 1f)
    val valueScore = if (scout != null) {
      scoutAdjustedValueScore(baseScore, scoutScore, scout)
    } else if (intent == GenerationIntent.AUTO) {
      baseScore
    } else {
      (baseScore * 0.78f + intentScore * 0.22f).coerceIn(0f, 1f)
    }

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
        intent = intent,
        intentScore = intentScore,
        scout = scout,
        realVideoSeconds = realVideoSeconds,
        longVideoCount = longVideoCount,
        gpsAssetCount = gpsAssetCount,
      ),
      scout = scout,
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
    intent: GenerationIntent,
    intentScore: Float,
    scout: EventScout?,
    realVideoSeconds: Float,
    longVideoCount: Int,
    gpsAssetCount: Int,
  ): List<String> {
    val out = mutableListOf<String>()
    if (scout != null) {
      out += "vlm-scout"
      if (scout.eventType.isNotBlank() && scout.eventType != "unknown") out += scout.eventType
      if (scout.sampled) out += "sampled-scout"
      if (!scout.recommended) out += "scout-caution"
      if (scout.eventType.equals("junk", ignoreCase = true)) out += "scout-junk"
    } else {
      out += "metadata-only"
    }
    if (travelScore >= 0.35f) out += "travel"
    if (gpsAssetCount > 0) out += "gps"
    if (realVideoSeconds >= 30f) out += "video"
    if (longVideoCount > 0) out += "long-video"
    if (assets.size >= 10) out += "many-assets"
    if (storyScore >= 0.65f) out += "story-span"
    when {
      intent == GenerationIntent.ZOO && intentScore >= 0.25f -> out += "zoo"
      intent == GenerationIntent.PEOPLE && intentScore >= 0.25f -> out += "people"
      intent == GenerationIntent.FOOD && intentScore >= 0.25f -> out += "food"
      intent == GenerationIntent.TRAVEL && intentScore >= 0.25f -> out += "intent-travel"
    }
    if (mediaScore < 0.08f && travelScore < 0.15f && gpsAssetCount == 0) out += "low-signal"
    return out
  }

  private fun scoutScore(intent: GenerationIntent, scout: EventScout?): Float {
    if (scout == null) return 0f
    if (scout.eventType.equals("junk", ignoreCase = true)) return 0f
    val typeScore = when (intent) {
      GenerationIntent.AUTO -> maxOf(
        scout.travelScore,
        scout.zooScore,
        scout.peopleScore,
        scout.foodScore,
        if (scout.eventType.equals("daily", ignoreCase = true)) 0.35f else 0f,
      )
      GenerationIntent.TRAVEL -> scout.travelScore
      GenerationIntent.ZOO -> scout.zooScore
      GenerationIntent.PEOPLE -> scout.peopleScore
      GenerationIntent.FOOD -> scout.foodScore
    }
    val base = (
      scout.storyValue * 0.34f +
        scout.visualValue * 0.30f +
        scout.subjectValue * 0.18f +
        typeScore * 0.18f
      ).coerceIn(0f, 1f)
    val recommendation = when {
      scout.recommended -> 0.10f
      scout.rejectReasons.isNotEmpty() -> -0.12f
      else -> 0f
    }
    return (base + recommendation).coerceIn(0f, 1f)
  }

  private fun scoutAdjustedValueScore(baseScore: Float, scoutScore: Float, scout: EventScout): Float {
    if (scout.eventType.equals("junk", ignoreCase = true)) return 0.02f
    val blended = (baseScore * 0.42f + scoutScore * 0.58f).coerceIn(0f, 1f)
    return if (!scout.recommended && scout.rejectReasons.isNotEmpty()) {
      blended.coerceAtMost(0.28f)
    } else {
      blended
    }
  }

  private fun intentScore(
    intent: GenerationIntent,
    assets: List<Asset>,
    perceptions: Map<String, Perception?>,
  ): Float {
    if (intent == GenerationIntent.AUTO || assets.isEmpty()) return 0f
    val text = assets.joinToString(" ") { asset -> semanticText(asset, perceptions[asset.id]) }
    val keywordScore = when (intent) {
      GenerationIntent.AUTO -> 0f
      GenerationIntent.TRAVEL -> keywordScore(text, travelKeywords)
      GenerationIntent.ZOO -> keywordScore(text, zooKeywords)
      GenerationIntent.PEOPLE -> keywordScore(text, peopleKeywords)
      GenerationIntent.FOOD -> keywordScore(text, foodKeywords)
    }
    val faceScore = if (intent == GenerationIntent.PEOPLE) {
      (perceptions.values.filterNotNull().sumOf { it.faces.size } / 4f).coerceIn(0f, 1f)
    } else {
      0f
    }
    val videoSeconds = assets.filter { it.mediaType == MediaType.VIDEO }
      .sumOf { it.durationMs.coerceAtLeast(0L) }
      .toFloat() / 1000f
    val videoBonus = when (intent) {
      GenerationIntent.ZOO, GenerationIntent.TRAVEL -> (videoSeconds / 90f).coerceIn(0f, 0.28f)
      GenerationIntent.PEOPLE, GenerationIntent.FOOD -> (videoSeconds / 60f).coerceIn(0f, 0.18f)
      GenerationIntent.AUTO -> 0f
    }
    val gpsBonus = if (intent == GenerationIntent.TRAVEL && assets.any { it.latitude != null && it.longitude != null }) 0.18f else 0f
    return (keywordScore + faceScore * 0.45f + videoBonus + gpsBonus).coerceIn(0f, 1f)
  }

  private fun keywordScore(text: String, keywords: List<String>): Float {
    val hits = keywords.count { it in text }
    return if (hits == 0) 0f else (0.18f + min(hits, 5) * 0.12f).coerceAtMost(1f)
  }

  private fun semanticText(asset: Asset, perception: Perception?): String {
    val tags = perception?.vlmTags
    val video = perception?.videoInsight
    return buildString {
      append(asset.displayName); append(' ')
      append(tags?.visualDescription.orEmpty()); append(' ')
      append(tags?.scene.orEmpty()); append(' ')
      tags?.subjects.orEmpty().forEach { append(it); append(' ') }
      append(tags?.action.orEmpty()); append(' ')
      append(tags?.mood.orEmpty()); append(' ')
      append(tags?.salient.orEmpty()); append(' ')
      append(tags?.composition.orEmpty()); append(' ')
      append(tags?.lighting.orEmpty()); append(' ')
      append(tags?.motionHint.orEmpty()); append(' ')
      append(video?.visualDescription.orEmpty()); append(' ')
      append(video?.summary.orEmpty()); append(' ')
      append(video?.actionArc.orEmpty()); append(' ')
      append(video?.cameraWork.orEmpty()); append(' ')
      append(video?.pacing.orEmpty()); append(' ')
      append(video?.audioVisualHint.orEmpty())
    }.lowercase(Locale.ROOT)
  }

  private val travelKeywords = listOf(
    "旅行", "旅游", "景区", "景点", "风景", "户外", "城市", "街景", "机场", "车站",
    "酒店", "行李", "路牌", "landmark", "travel", "scenery", "outdoor",
  )

  private val zooKeywords = listOf(
    "动物园", "野生动物", "海洋馆", "水族馆", "动物", "熊猫", "长颈鹿", "大象",
    "猴子", "zoo", "safari", "animal", "wildlife", "aquarium", "panda",
  )

  private val peopleKeywords = listOf(
    "人", "朋友", "孩子", "小孩", "家人", "合影", "互动", "笑", "表情", "跳舞",
    "portrait", "people", "friend", "family", "child", "smile",
  )

  private val foodKeywords = listOf(
    "美食", "餐厅", "菜", "饭", "咖啡", "甜点", "火锅", "烧烤", "聚餐", "桌面",
    "food", "restaurant", "meal", "coffee", "dessert", "dinner",
  )

  private fun percent(v: Float): String =
    String.format(Locale.ROOT, "%02d", (v.coerceIn(0f, 1f) * 100f).roundToInt())
}
