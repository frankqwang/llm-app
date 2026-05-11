/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Decides which video encoder to use at runtime by asking ffmpeg what's
 * available. Preference order:
 *   1. h264_mediacodec    — hardware H.264 via Android MediaCodec API.
 *      Almost-zero-CPU, ~10x faster than software, uses the SoC's dedicated
 *      video encoder block. Output is real H.264 (universal compat).
 *   2. mpeg4              — universally bundled in every ffmpeg build (LGPL).
 *      Software, slower, file ~2x bigger at same quality. The safe fallback.
 *
 * libx264 isn't on the list because ffmpeg-kit-16kb min variant doesn't
 * bundle it (GPL). To enable libx264 swap to a full-gpl variant of the
 * dependency and add it as the top preference here.
 *
 * Probe runs once per process; result is cached.
 */
package com.vlogcopilot.render

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit

object EncoderProbe {

  private const val TAG = "EncoderProbe"

  @Volatile private var cachedArgs: String? = null
  @Volatile private var cachedName: String? = null

  /** ffmpeg `-c:v ... -<bitrate-or-quality-flag> ...` snippet ready to paste
   *  into a render command line. Calls into ffmpeg the first time, caches
   *  the result for the lifetime of the process. */
  fun videoEncoderArgs(): String {
    cachedArgs?.let { return it }
    return synchronized(this) {
      cachedArgs?.let { return it }
      val (name, args) = pickEncoder()
      cachedName = name
      cachedArgs = args
      Log.i(TAG, "selected video encoder: $name → $args")
      args
    }
  }

  /** "h264_mediacodec" / "mpeg4" / "libx264" — for diagnostics + perf logs. */
  fun selectedEncoderName(): String {
    if (cachedName == null) videoEncoderArgs()
    return cachedName ?: "unknown"
  }

  private fun pickEncoder(): Pair<String, String> {
    val available = listAvailableEncoders()
    return when {
      "h264_mediacodec" in available -> {
        // Hardware H.264. -b:v controls bitrate (CRF doesn't apply here).
        // 5 Mbps at 1080p30 is roughly equivalent to libx264 -crf 22.
        // -bf 0 disables B-frames; some MediaTek encoders are buggy with B-frames
        // in non-streaming mode. Costs ~10% size for much higher reliability.
        "h264_mediacodec" to "-c:v h264_mediacodec -b:v 5000k -bf 0"
      }
      "libx264" in available -> {
        "libx264" to "-c:v libx264 -preset veryfast -crf 22"
      }
      else -> {
        // mpeg4 is in literally every ffmpeg flavor.
        "mpeg4" to "-c:v mpeg4 -q:v 5"
      }
    }
  }

  /** Run `ffmpeg -encoders` and return the set of encoder short names we can ask for. */
  private fun listAvailableEncoders(): Set<String> {
    val session = try {
      FFmpegKit.execute("-hide_banner -encoders")
    } catch (t: Throwable) {
      Log.w(TAG, "ffmpeg -encoders failed: ${t.message}")
      return emptySet()
    }
    val out = session.allLogsAsString
    // Each encoder line looks like:  " V..... h264_mediacodec      H.264 ..."
    // The 7-char flag block tells you V/A/S type; we just need the name.
    val names = mutableSetOf<String>()
    for (line in out.lineSequence()) {
      val trimmed = line.trim()
      if (trimmed.length < 8 || !trimmed.startsWith("V")) continue
      val parts = trimmed.split(Regex("\\s+"), limit = 3)
      if (parts.size >= 2) names += parts[1]
    }
    Log.d(TAG, "available video encoders (${names.size}): ${names.take(15)}…")
    return names
  }
}
