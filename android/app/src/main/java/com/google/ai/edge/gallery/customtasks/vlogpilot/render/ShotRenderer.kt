/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Render one shot to a self-contained intermediate MP4 (1080×1920, h264, 30fps,
 * silent). Handles:
 *  - Image / Live Photo / Video all uniformly
 *  - Blurred-bg compose so any source aspect lands on a 9:16 frame
 *  - Smart slow-mo (1.5x cap normal, 2.5x cap when source ≥ 48 fps)
 *  - Ken Burns drift on still images
 *  - Caption + color grade + light polish layer
 *
 * Returns (path, actualDurationSec) — the duration may differ from request when
 * source is shorter than requested and slow-mo has hit its cap.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.render

import android.content.Context
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import java.io.File
import java.util.Locale

object ShotRenderer {

  private const val TAG = "ShotRenderer"
  private const val OUT_W = 1080
  private const val OUT_H = 1920
  private const val FPS = 30
  private const val MAX_SLOWMO_NORMAL = 1.15f
  private const val MAX_SLOWMO_HIGHFPS = 1.75f
  private const val MAX_SPEEDUP = 2.0f
  private const val HIGHFPS_THRESHOLD = 48f

  // Video encoder is picked at runtime by EncoderProbe — prefers
  // h264_mediacodec (hardware) when available, falls back to mpeg4 (always
  // available). Computed lazily and cached for the process lifetime.
  private val VIDEO_ENCODER: String get() = EncoderProbe.videoEncoderArgs()

  data class Result(val path: String, val actualDurationSec: Float)

  fun render(
    context: Context,
    asset: Asset,
    spec: ShotSpec,
    fontPath: String?,
    workDir: File,
  ): Result? {
    workDir.mkdirs()
    val outFile = File(workDir, "shot_${spec.order}.mp4")
    outFile.delete()

    val sourcePath = resolveSourcePath(context, asset) ?: run {
      Log.w(TAG, "cannot resolve source for ${asset.id}")
      return null
    }

    val cmd = when (spec.mediaType) {
      MediaType.IMAGE -> imageCmd(sourcePath, outFile.absolutePath, spec, fontPath)
      MediaType.VIDEO, MediaType.LIVE_PHOTO -> videoCmd(sourcePath, outFile.absolutePath, spec, fontPath)
    }
    Log.d(TAG, "shot ${spec.order} cmd: $cmd")
    val session = FFmpegKit.execute(cmd)
    if (!ReturnCode.isSuccess(session.returnCode)) {
      Log.e(TAG, "shot ${spec.order} ffmpeg failed: ${session.returnCode} ${session.failStackTrace}")
      return null
    }
    val probe = FFmpegProbe.probe(outFile.absolutePath)
    return Result(outFile.absolutePath, probe.durationSec.takeIf { it > 0 } ?: spec.durationSec)
  }

  // ---- image ----

  // zoompan iterates `on` from 0..durFrames-1. zoom-in/out drift linearly to
  // the requested kbZoom level over the shot's duration; pan modes hold zoom
  // at kbZoom and sweep x across the `iw - iw/zoom` margin. y stays centered.
  // Director controls the travel intensity via ShotSpec.kenBurnsZoom (range
  // ~1.0..1.20); we clamp here to keep the crop margin numerically safe.
  private fun kenBurnsExpr(mode: String, durFrames: Int, requestedZoom: Float): String {
    val maxOn = (durFrames - 1).coerceAtLeast(1)
    val zoom = requestedZoom.coerceIn(1.0f, 1.20f)
    val delta = (zoom - 1.0f).coerceAtLeast(0.001f)
    val zoomS = "%.4f".format(Locale.US, zoom)
    val deltaS = "%.4f".format(Locale.US, delta)
    return when (mode) {
      "in" -> "z='1+$deltaS*on/$maxOn'"
      "out" -> "z='1+$deltaS*(1-on/$maxOn)'"
      "pan_left" -> "z=$zoomS:x='(iw-iw/zoom)*(1-on/$maxOn)':y='(ih-ih/zoom)/2'"
      "pan_right" -> "z=$zoomS:x='(iw-iw/zoom)*on/$maxOn':y='(ih-ih/zoom)/2'"
      else -> "z=1.025"
    }
  }

  private fun imageCmd(input: String, output: String, spec: ShotSpec, fontPath: String?): String {
    val dur = spec.durationSec
    val durFrames = (dur * FPS).toInt().coerceAtLeast(2)
    val kbExpr = kenBurnsExpr(spec.kenBurns, durFrames, spec.kenBurnsZoom)
    val zoompan = "zoompan=$kbExpr:d=$durFrames:s=${OUT_W}x${OUT_H}:fps=$FPS"
    val grade = ColorGradeFilter.forGrade(spec.colorGrade).let { if (it.isEmpty()) "" else ",$it" }
    val polish = ",${ColorGradeFilter.polishLayer()}"
    val caption = CaptionFilter.build(spec.caption, dur, fontPath).let { if (it.isEmpty()) "" else ",$it" }
    val vf = "[0:v]$zoompan${grade}${polish}${caption}[v]"
    return "-y -loop 1 -i \"$input\" -filter_complex \"$vf\" -map \"[v]\" -t $dur " +
      "$VIDEO_ENCODER -pix_fmt yuv420p -r $FPS \"$output\""
  }

  // ---- video / live photo ----

  private fun videoCmd(input: String, output: String, spec: ShotSpec, fontPath: String?): String {
    val probe = FFmpegProbe.probe(input)
    val srcDur = probe.durationSec.takeIf { it > 0 } ?: spec.durationSec
    val srcFps = probe.fps
    val cap = if (srcFps >= HIGHFPS_THRESHOLD) MAX_SLOWMO_HIGHFPS else MAX_SLOWMO_NORMAL

    val trim = spec.videoTrim
    val ssArg = trim?.let { "-ss ${"%.3f".format(it.startSec)}" }.orEmpty()
    val srcWindow = trim?.let { (it.endSec - it.startSec).coerceAtLeast(0.4f) } ?: srcDur

    val want = spec.durationSec
    // setpts factor: >1 = slow, <1 = fast. Existing logic auto-stretches when
    // source < target (rawStretch >= 1). Director's speedFactor (1/x model:
    // 0.5 = half-speed, 2.0 = double-speed) layers a multiplicative bias on
    // top, then we clamp into [1/MAX_SPEEDUP .. cap] so the encoder doesn't
    // get asked for impossible rates.
    val sf = spec.speedFactor.coerceIn(0.5f, MAX_SPEEDUP)
    val rawStretch = (want / srcWindow).coerceAtLeast(1f)
    val ptsFactor = (rawStretch / sf).coerceIn(1f / MAX_SPEEDUP, cap)
    val effectiveDur = (srcWindow * ptsFactor).coerceAtMost(want)

    val needsSlowmo = ptsFactor > 1.01f
    val isSpeedup = ptsFactor < 0.99f
    val setpts = when {
      needsSlowmo -> "setpts=${"%.3f".format(Locale.US, ptsFactor)}*PTS,minterpolate=fps=$FPS:mi_mode=blend"
      isSpeedup -> "setpts=${"%.3f".format(Locale.US, ptsFactor)}*PTS,fps=$FPS"
      else -> "fps=$FPS"
    }
    // 9:16 blurred-bg compose: split, blur+scale to 1080x1920, scale fg to fit, overlay center.
    val blurredCompose = "split[bg][fg];" +
      "[bg]scale=$OUT_W:$OUT_H:force_original_aspect_ratio=increase,crop=$OUT_W:$OUT_H,boxblur=24:1[bg2];" +
      "[fg]scale=$OUT_W:$OUT_H:force_original_aspect_ratio=decrease[fg2];" +
      "[bg2][fg2]overlay=(W-w)/2:(H-h)/2"
    val grade = ColorGradeFilter.forGrade(spec.colorGrade).let { if (it.isEmpty()) "" else ",$it" }
    val polish = ",${ColorGradeFilter.polishLayer()}"
    val caption = CaptionFilter.build(spec.caption, effectiveDur, fontPath).let { if (it.isEmpty()) "" else ",$it" }

    val vf = "[0:v]$setpts,$blurredCompose${grade}${polish}${caption}[v]"
    return "-y $ssArg -i \"$input\" -filter_complex \"$vf\" -map \"[v]\" -an " +
      "-t $effectiveDur $VIDEO_ENCODER -pix_fmt yuv420p -r $FPS \"$output\""
  }

  private fun resolveSourcePath(context: Context, asset: Asset): String? {
    val uri = when (asset.mediaType) {
      MediaType.LIVE_PHOTO -> asset.livePhotoVideoUri ?: asset.contentUri
      else -> asset.contentUri
    }
    val parsed = Uri.parse(uri)
    if (parsed.scheme == "file") return parsed.path
    // ffmpeg-kit can read content:// URIs on Android via SAF helper, but copying first is more reliable.
    return try {
      val ext = if (asset.mediaType == MediaType.IMAGE) "jpg" else "mp4"
      val tmp = File(context.cacheDir, "vlog_src/${asset.id}.$ext")
      tmp.parentFile?.mkdirs()
      if (!tmp.isFile || tmp.length() == 0L) {
        context.contentResolver.openInputStream(parsed)?.use { input ->
          tmp.outputStream().use { input.copyTo(it) }
        } ?: return null
      }
      tmp.absolutePath
    } catch (t: Throwable) {
      Log.w(TAG, "copy to cache failed: ${t.message}")
      null
    }
  }
}
