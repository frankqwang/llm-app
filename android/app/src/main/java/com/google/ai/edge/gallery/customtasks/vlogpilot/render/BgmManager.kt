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
    val toneKey = tone.lowercase().split(Regex("[^a-z]+")).firstOrNull { it.isNotEmpty() }.orEmpty()
    val candidate = TONE_TO_FILE[toneKey] ?: FALLBACK
    val cached = File(context.filesDir, "bgm/$candidate")
    if (cached.isFile) return cached.absolutePath
    return try {
      cached.parentFile?.mkdirs()
      context.assets.open("bgm/$candidate").use { input ->
        cached.outputStream().use { input.copyTo(it) }
      }
      cached.absolutePath
    } catch (e: Throwable) {
      Log.w(TAG, "BGM not found: bgm/$candidate (${e.message})")
      null
    }
  }
}
