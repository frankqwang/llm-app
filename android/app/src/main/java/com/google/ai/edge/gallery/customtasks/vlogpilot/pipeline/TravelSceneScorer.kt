/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Lightweight travel-scene prior used before agent reasoning. It does not try
 * to classify the whole album; it only nudges event and shot recall toward
 * footage that looks like a trip: location changes, outdoor/landmark scenery,
 * transit, hotel/restaurant moments, and wide contextual shots.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object TravelSceneScorer {

  private val positiveKeywords = listOf(
    "旅行", "旅游", "景区", "景点", "风景", "户外", "远景", "全景", "天空", "云",
    "日出", "日落", "夜景", "海边", "沙滩", "海滩", "湖", "河", "瀑布", "雪山",
    "山路", "登山", "徒步", "露营", "街道", "街景", "城市", "古镇", "广场",
    "桥", "公园", "寺", "庙", "博物馆", "展览", "建筑", "机场", "车站",
    "火车", "高铁", "飞机", "酒店", "民宿", "餐厅", "咖啡", "行李", "路牌",
    "动物园", "野生动物", "熊猫", "长颈鹿", "大象", "猴子", "海洋馆", "水族馆",
    "码头", "游船", "beach", "mountain", "street", "city", "hotel", "airport",
    "station", "train", "flight", "landmark", "museum", "restaurant", "travel",
    "scenery", "landscape", "sunset", "outdoor", "zoo", "safari", "animal",
    "wildlife", "aquarium", "panda", "elephant", "giraffe", "monkey",
  )

  private val negativeKeywords = listOf(
    "截图", "屏幕", "文档", "发票", "二维码", "自拍", "镜子", "卧室", "办公室",
    "聊天", "证件", "screenshot", "screen", "document", "invoice", "qr", "selfie",
    "mirror", "bedroom", "office",
  )

  fun eventScore(
    event: Event,
    assets: List<Asset>,
    perceptionFor: (String) -> Perception?,
  ): Float {
    if (assets.isEmpty()) return 0f
    val assetPrior = assets.map { assetScore(it, perceptionFor(it.id)) }.average().toFloat()
    val gpsAssets = assets.filter { it.latitude != null && it.longitude != null }.sortedBy { it.takenEpochMs }
    val geoDiversity = geoDiversityScore(gpsAssets)
    val routeScore = (routeKm(gpsAssets) / 3f).coerceIn(0f, 1f)
    val spanHours = ((event.endEpochMs - event.startEpochMs).coerceAtLeast(0L) / 3_600_000f)
    val spanScore = ((spanHours - 1f) / 12f).coerceIn(0f, 1f)
    val realVideos = assets.filter { it.mediaType == MediaType.VIDEO }
    val realVideoSeconds = realVideos.sumOf { it.durationMs.coerceAtLeast(0L) }.toFloat() / 1000f
    val meaningfulVideos = realVideos.count { it.durationMs >= 5_000L }
    val livePhotoRatio = assets.count { it.mediaType == MediaType.LIVE_PHOTO }.toFloat() / assets.size
    val videoScore = if (realVideos.isNotEmpty()) {
      ((realVideoSeconds / 90f) * 0.65f + (meaningfulVideos / 3f) * 0.35f).coerceIn(0f, 1f)
    } else {
      (livePhotoRatio * 0.25f).coerceIn(0f, 0.25f)
    }
    val volumeScore = (assets.size / 12f).coerceIn(0f, 1f)

    return (assetPrior * 0.48f +
      geoDiversity * 0.20f +
      routeScore * 0.14f +
      videoScore * 0.08f +
      spanScore * 0.06f +
      volumeScore * 0.04f).coerceIn(0f, 1f)
  }

  fun assetScore(asset: Asset, perception: Perception?): Float {
    val text = semanticText(asset, perception)
    val positiveHits = positiveKeywords.count { it in text }
    val negativeHits = negativeKeywords.count { it in text }
    var score = 0f
    if (asset.latitude != null && asset.longitude != null) score += 0.18f
    when (asset.mediaType) {
      MediaType.VIDEO -> score += if (asset.durationMs >= 5_000L) 0.12f else 0.02f
      MediaType.LIVE_PHOTO -> score += 0.03f
      MediaType.IMAGE -> Unit
    }
    if (asset.widthPx > asset.heightPx && asset.widthPx > 0) score += 0.04f
    if (positiveHits > 0) score += (0.22f + min(positiveHits, 4) * 0.05f).coerceAtMost(0.45f)
    if (positiveHits == 0 && negativeHits > 0) score -= min(negativeHits, 3) * 0.12f
    return score.coerceIn(0f, 1f)
  }

  private fun semanticText(asset: Asset, perception: Perception?): String {
    val tags = perception?.vlmTags
    val video = perception?.videoInsight
    return buildString {
      append(asset.displayName)
      append(' ')
      append(tags?.scene.orEmpty()); append(' ')
      tags?.subjects.orEmpty().forEach { append(it); append(' ') }
      append(tags?.action.orEmpty()); append(' ')
      append(tags?.mood.orEmpty()); append(' ')
      append(tags?.timeFeel.orEmpty()); append(' ')
      append(tags?.salient.orEmpty()); append(' ')
      append(tags?.narrativeRoleHint.orEmpty()); append(' ')
      append(video?.summary.orEmpty()); append(' ')
      append(video?.actionArc.orEmpty())
    }.lowercase()
  }

  private fun geoDiversityScore(gpsAssets: List<Asset>): Float {
    if (gpsAssets.isEmpty()) return 0f
    val cells = gpsAssets.map {
      ((it.latitude ?: 0.0) * 50.0).roundToInt() to ((it.longitude ?: 0.0) * 50.0).roundToInt()
    }.distinct().size
    return when {
      cells >= 3 -> 1f
      cells == 2 -> 0.55f
      else -> 0.25f
    }
  }

  private fun routeKm(gpsAssets: List<Asset>): Float =
    gpsAssets.zipWithNext().sumOf { (a, b) ->
      haversineKm(a.latitude ?: 0.0, a.longitude ?: 0.0, b.latitude ?: 0.0, b.longitude ?: 0.0).toDouble()
    }.toFloat()

  private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val rLat1 = Math.toRadians(lat1)
    val rLat2 = Math.toRadians(lat2)
    val a = sin(dLat / 2) * sin(dLat / 2) +
      cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
    return (2.0 * 6371.0 * asin(sqrt(a))).toFloat()
  }
}
