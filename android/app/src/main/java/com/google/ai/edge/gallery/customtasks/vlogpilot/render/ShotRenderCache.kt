/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Persistent cache for per-shot intermediate MP4s. A Timeline iteration often
 * changes only captions, grade, BGM, or one picked asset; unchanged shots should
 * not pay the FFmpeg render cost again.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.render

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import java.io.File
import java.security.MessageDigest
import java.util.Locale

object ShotRenderCache {
  private const val TAG = "ShotRenderCache"
  private const val CACHE_DIR = "vlog_shot_cache"
  // Bump when ShotSpec gains fields that affect the rendered MP4.
  // v2: added speedFactor / kenBurnsZoom (V2.1).
  private const val CACHE_VERSION = 2

  data class Entry(val file: File)

  fun entry(context: Context, asset: Asset, spec: ShotSpec, fontAvailable: Boolean): Entry {
    val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
    val key = sha256(signature(asset, spec, fontAvailable))
    return Entry(File(dir, "$key.mp4"))
  }

  fun load(entry: Entry): ShotRenderer.Result? {
    val file = entry.file
    if (!file.isFile || file.length() <= 0L) return null
    val probe = FFmpegProbe.probe(file.absolutePath)
    val duration = probe.durationSec.takeIf { it > 0f } ?: return null
    return ShotRenderer.Result(file.absolutePath, duration)
  }

  fun store(entry: Entry, rendered: ShotRenderer.Result): ShotRenderer.Result {
    val source = File(rendered.path)
    val target = entry.file
    return try {
      if (source.absolutePath != target.absolutePath && source.isFile && source.length() > 0L) {
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
      }
      load(entry) ?: rendered
    } catch (t: Throwable) {
      Log.w(TAG, "failed to store shot cache: ${t.message}")
      rendered
    }
  }

  private fun signature(asset: Asset, spec: ShotSpec, fontAvailable: Boolean): String {
    val trim = spec.videoTrim
    return buildString {
      append("v=").append(CACHE_VERSION).append('\n')
      append("asset=").append(asset.id).append('\n')
      append("uri=").append(asset.contentUri).append('\n')
      append("live=").append(asset.livePhotoVideoUri.orEmpty()).append('\n')
      append("media=").append(asset.mediaType.name).append('\n')
      append("size=").append(asset.sizeBytes).append('\n')
      append("durationMs=").append(asset.durationMs).append('\n')
      append("orientation=").append(asset.orientation).append('\n')
      append("w=").append(asset.widthPx).append(",h=").append(asset.heightPx).append('\n')
      append("shotMedia=").append(spec.mediaType.name).append('\n')
      append("shotDuration=").append(spec.durationSec.fmt()).append('\n')
      append("trim=").append(trim?.startSec?.fmt()).append('-').append(trim?.endSec?.fmt()).append('\n')
      append("kenBurns=").append(spec.kenBurns).append('\n')
      append("kbZoom=").append(spec.kenBurnsZoom.fmt()).append('\n')
      append("speedFactor=").append(spec.speedFactor.fmt()).append('\n')
      append("grade=").append(spec.colorGrade.name).append('\n')
      append("caption=").append(spec.caption).append('\n')
      append("font=").append(fontAvailable).append('\n')
    }
  }

  private fun Float.fmt(): String = String.format(Locale.US, "%.3f", this)

  private fun sha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}
