/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * BGM picker. Looks under assets/bgm/<tone>.mp3, falling back to neutral.mp3
 * when an event-specific tone isn't bundled. Caller copies the chosen track
 * into filesDir so ffmpeg can read it via path.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.render

import android.content.Context
import android.util.Log
import java.io.File

object BgmManager {

  private const val TAG = "BgmManager"
  private val TONE_TO_FILE = mapOf(
    "warm" to "warm.mp3", "warm_father_son" to "warm.mp3", "tender" to "warm.mp3",
    "cool" to "cool.mp3", "calm" to "cool.mp3",
    "vibrant" to "vibrant.mp3", "energetic" to "vibrant.mp3", "playful" to "vibrant.mp3",
    "muted" to "muted.mp3", "wistful" to "muted.mp3",
    "cinematic" to "cinematic.mp3", "epic" to "cinematic.mp3",
    "vintage" to "vintage.mp3", "nostalgic" to "vintage.mp3",
    "neutral" to "neutral.mp3",
  )
  private const val FALLBACK = "neutral.mp3"

  /** Returns absolute path to a BGM file under filesDir, or null if no BGM bundled. */
  fun pickFor(context: Context, tone: String): String? {
    val toneKey = toneKey(tone)
    val candidate = TONE_TO_FILE[toneKey] ?: FALLBACK
    return copyAssetToCache(context, candidate)
      ?: if (candidate != FALLBACK) copyAssetToCache(context, FALLBACK) else null
  }

  private fun copyAssetToCache(context: Context, fileName: String): String? {
    val cached = File(context.filesDir, "bgm/$fileName")
    if (cached.isFile) return cached.absolutePath
    return try {
      cached.parentFile?.mkdirs()
      context.assets.open("bgm/$fileName").use { input ->
        cached.outputStream().use { input.copyTo(it) }
      }
      cached.absolutePath
    } catch (e: Throwable) {
      Log.w(TAG, "BGM not found: bgm/$fileName (${e.message})")
      null
    }
  }

  private fun toneKey(tone: String): String {
    val lc = tone.lowercase()
    return when {
      listOf("温馨", "温暖", "夕阳", "黄昏", "亲情", "治愈", "warm", "tender").any { lc.contains(it) } -> "warm"
      listOf("夜", "城市", "冷静", "雨", "蓝调", "cool", "calm").any { lc.contains(it) } -> "cool"
      listOf("活力", "鲜艳", "童年", "派对", "playful", "vibrant", "energetic").any { lc.contains(it) } -> "vibrant"
      listOf("文艺", "宁静", "留白", "muted", "wistful").any { lc.contains(it) } -> "muted"
      listOf("电影", "氛围", "戏剧", "cinematic", "epic").any { lc.contains(it) } -> "cinematic"
      listOf("复古", "胶片", "怀旧", "vintage", "nostalgic").any { lc.contains(it) } -> "vintage"
      else -> lc.split(Regex("[^a-z]+")).firstOrNull { it.isNotEmpty() }.orEmpty()
    }
  }
}
