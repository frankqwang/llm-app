/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Step 2 of render: take the per-shot intermediate MP4s, build an xfade chain
 * across them per ShotSpec.transitionIn, and mux a BGM track underneath.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.render

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.TransitionKind
import java.io.File

object CompositeRenderer {

  private const val TAG = "CompositeRenderer"
  private val XFADE_MAP = mapOf(
    TransitionKind.CUT to "fade",  // very short fade approximates a cut without strobing
    TransitionKind.FADE to "fade",
    TransitionKind.CROSSFADE to "fade",
    TransitionKind.FADEBLACK to "fadeblack",
    TransitionKind.FADEWHITE to "fadewhite",
    TransitionKind.SLIDELEFT to "slideleft",
    TransitionKind.SLIDERIGHT to "slideright",
    TransitionKind.CIRCLEOPEN to "circleopen",
    TransitionKind.CIRCLECLOSE to "circleclose",
    TransitionKind.ZOOMIN to "zoomin",
    TransitionKind.SMOOTHLEFT to "smoothleft",
    TransitionKind.SMOOTHRIGHT to "smoothright",
  )
  private val XFADE_DUR = mapOf(
    TransitionKind.CUT to 0.04f,
    TransitionKind.FADE to 0.28f,
    TransitionKind.CROSSFADE to 0.22f,
    TransitionKind.FADEBLACK to 0.32f,
    TransitionKind.FADEWHITE to 0.30f,
    TransitionKind.SLIDELEFT to 0.28f,
    TransitionKind.SLIDERIGHT to 0.28f,
    TransitionKind.CIRCLEOPEN to 0.28f,
    TransitionKind.CIRCLECLOSE to 0.28f,
    TransitionKind.ZOOMIN to 0.28f,
    TransitionKind.SMOOTHLEFT to 0.28f,
    TransitionKind.SMOOTHRIGHT to 0.28f,
  )

  data class ShotInput(val path: String, val durationSec: Float, val transition: TransitionKind)

  /**
   * @param shots intermediate MP4 paths in order, with their actual durations
   *   and transition-in kind (the first shot's transitionIn is ignored).
   * @param bgmPath optional path to BGM mp3
   * @param outFile destination MP4
   */
  fun composite(shots: List<ShotInput>, bgmPath: String?, outFile: File): Boolean {
    if (shots.isEmpty()) return false
    if (shots.size == 1) {
      val cmd = if (bgmPath == null) {
        "-y -i \"${shots[0].path}\" -c copy \"${outFile.absolutePath}\""
      } else {
        val dur = shots[0].durationSec
        val fadeStart = (dur - 0.6f).coerceAtLeast(0.1f)
        "-y -i \"${shots[0].path}\" -i \"$bgmPath\" -filter_complex " +
          "\"[1:a]aloop=loop=-1:size=2e9,atrim=0:$dur,afade=t=out:st=$fadeStart:d=0.6[a]\" " +
          "-map 0:v -map \"[a]\" -c:v copy -c:a aac -b:a 192k -shortest \"${outFile.absolutePath}\""
      }
      return runCmd(cmd, outFile)
    }

    val sb = StringBuilder()
    val inputs = StringBuilder()
    for (s in shots) inputs.append("-i \"${s.path}\" ")

    // Build cumulative offsets and xfade chain.
    val xfadeNames = mutableListOf<String>()
    var cumDuration = shots[0].durationSec
    var lastLabel = "[0:v]"
    for (i in 1 until shots.size) {
      val curr = shots[i]
      val transName = XFADE_MAP[curr.transition] ?: "fade"
      val requestedDur = XFADE_DUR[curr.transition] ?: 0.45f
      val transDur = requestedDur
        .coerceAtMost(cumDuration - 0.05f)
        .coerceAtMost(curr.durationSec - 0.05f)
        .coerceAtLeast(0.05f)
      val offset = (cumDuration - transDur).coerceAtLeast(0f)
      val out = "[v$i]"
      sb.append("$lastLabel[${i}:v]xfade=transition=$transName:duration=$transDur:offset=$offset$out;")
      xfadeNames += out
      cumDuration += curr.durationSec - transDur
      lastLabel = out
    }
    sb.append("${lastLabel}null[vout]")

    val totalDur = cumDuration
    val (audioMap, audioFilter) = if (bgmPath != null) {
      "[a]" to ";[${shots.size}:a]aloop=loop=-1:size=2e9,atrim=0:$totalDur,afade=t=out:st=${(totalDur - 0.6f).coerceAtLeast(0.1f)}:d=0.6[a]"
    } else {
      "" to ""
    }
    val bgmInput = if (bgmPath != null) "-i \"$bgmPath\" " else ""
    val mapAudio = if (bgmPath != null) "-map \"[a]\" -c:a aac -b:a 192k" else "-an"

    // EncoderProbe picks the best available encoder at runtime
    // (h264_mediacodec hardware → mpeg4 software fallback).
    val videoEncoder = EncoderProbe.videoEncoderArgs()
    val cmd = "-y $inputs$bgmInput-filter_complex \"${sb.append(audioFilter)}\" " +
      "-map \"[vout]\" $mapAudio $videoEncoder -pix_fmt yuv420p \"${outFile.absolutePath}\""
    return runCmd(cmd, outFile)
  }

  private fun runCmd(cmd: String, outFile: File): Boolean {
    Log.d(TAG, "composite cmd: $cmd")
    val session = FFmpegKit.execute(cmd)
    val ok = ReturnCode.isSuccess(session.returnCode)
    if (!ok) {
      Log.e(TAG, "composite failed: ${session.returnCode}")
      Log.e(TAG, "logs: ${session.allLogsAsString.takeLast(2000)}")
    }
    return ok && outFile.isFile && outFile.length() > 0
  }
}
