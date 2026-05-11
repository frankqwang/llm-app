/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Bitmap loaders for images and video frames. Centralizes EXIF-rotation,
 * downscale, and color-space concerns so the model wrappers don't repeat them.
 */
package com.vlogcopilot.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.vlogcopilot.runtime.PowerPacer
import com.vlogcopilot.schemas.Asset
import com.vlogcopilot.schemas.MediaType
import kotlin.math.max

object MediaLoader {

  /** Decode the asset to a single Bitmap with the longest side ≤ maxSide.
   *  - IMAGE / LIVE_PHOTO: decode the still (contentUri).
   *  - VIDEO: pull a frame from the middle of the clip via MediaMetadataRetriever.
   *           BitmapFactory.decodeStream cannot decode an MP4 container, which is
   *           why all `content://media/external/video/...` URIs were failing here.
   *  @param preferRgb565 when true, decodes at 16-bit color (half the memory + ~30%
   *     faster decode). Use for grid thumbnails where ARGB_8888 is overkill. */
  fun loadImage(
    context: Context,
    asset: Asset,
    maxSide: Int = 1024,
    preferRgb565: Boolean = false,
  ): Bitmap? {
    return when (asset.mediaType) {
      MediaType.IMAGE, MediaType.LIVE_PHOTO -> decodeStillImage(context, asset, maxSide, preferRgb565)
      MediaType.VIDEO -> {
        val midSec = if (asset.durationMs > 0) (asset.durationMs / 2_000f) else 0.5f
        loadVideoFrame(context, asset, midSec, maxSide)
      }
    }
  }

  private fun decodeStillImage(
    context: Context,
    asset: Asset,
    maxSide: Int,
    preferRgb565: Boolean,
  ): Bitmap? {
    val uri = Uri.parse(asset.contentUri)
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
      val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
      val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = if (preferRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
      }
      val raw = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
      } ?: return null
      val rotated = applyExifRotation(context, uri, raw, asset.orientation)
      if (max(rotated.width, rotated.height) <= maxSide) rotated
      else scaleLongSide(rotated, maxSide)
    } catch (_: Throwable) {
      null
    }
  }

  /** Extract a single frame from a video / live-photo at the given second. */
  fun loadVideoFrame(context: Context, asset: Asset, atSec: Float, maxSide: Int = 1024): Bitmap? {
    val uri = videoUri(asset) ?: return null
    val mmr = MediaMetadataRetriever()
    return try {
      PowerPacer.applyBackgroundThreadPriority()
      mmr.setDataSource(context, uri)
      val timeUs = (atSec * 1_000_000).toLong()
      val raw = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
      scaleLongSideOrOriginal(raw, maxSide)
    } catch (_: Throwable) {
      null
    } finally {
      try { mmr.release() } catch (_: Throwable) {}
    }
  }

  /** Sample N evenly spaced frames from a video, returns (timestampSec, bitmap) pairs. */
  fun sampleVideoFrames(context: Context, asset: Asset, n: Int, maxSide: Int = 1024): List<Pair<Float, Bitmap>> {
    val durMs = asset.durationMs.takeIf { it > 0 } ?: return emptyList()
    val durSec = durMs / 1000f
    val timestamps = (0 until n.coerceAtLeast(1)).map { i -> durSec * (i + 0.5f) / n.coerceAtLeast(1) }
    return sampleVideoFramesAt(context, asset, timestamps, maxSide)
  }

  /** Sample frames at explicit timestamps using one retriever session. */
  fun sampleVideoFramesAt(
    context: Context,
    asset: Asset,
    timestampsSec: List<Float>,
    maxSide: Int = 1024,
  ): List<Pair<Float, Bitmap>> {
    val uri = videoUri(asset) ?: return emptyList()
    val durSec = asset.durationMs.takeIf { it > 0 }?.let { it / 1000f } ?: return emptyList()
    val safeTimestamps = timestampsSec
      .asSequence()
      .map { it.coerceIn(0f, durSec) }
      .map { ((it * 10f).toInt() / 10f).coerceIn(0f, durSec) }
      .distinct()
      .sorted()
      .toList()
    if (safeTimestamps.isEmpty()) return emptyList()

    val mmr = MediaMetadataRetriever()
    return try {
      PowerPacer.applyBackgroundThreadPriority()
      mmr.setDataSource(context, uri)
      val out = mutableListOf<Pair<Float, Bitmap>>()
      for ((index, tSec) in safeTimestamps.withIndex()) {
        val raw = mmr.getFrameAtTime((tSec * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
          ?: continue
        out += tSec to scaleLongSideOrOriginal(raw, maxSide)
        PowerPacer.afterFrameDecode(index, safeTimestamps.size)
      }
      out
    } catch (_: Throwable) {
      emptyList()
    } finally {
      try { mmr.release() } catch (_: Throwable) {}
    }
  }

  // ----- helpers -----

  private fun videoUri(asset: Asset): Uri? =
    when {
      asset.mediaType == MediaType.LIVE_PHOTO -> asset.livePhotoVideoUri?.let(Uri::parse)
      asset.mediaType == MediaType.VIDEO -> Uri.parse(asset.contentUri)
      else -> null
    }

  private fun computeSampleSize(w: Int, h: Int, maxSide: Int): Int {
    var s = 1
    while (max(w, h) / s > maxSide * 2) s *= 2
    return s.coerceAtLeast(1)
  }

  private fun applyExifRotation(context: Context, uri: Uri, bmp: Bitmap, fallbackOrientation: Int): Bitmap {
    val deg = try {
      context.contentResolver.openInputStream(uri)?.use { input ->
        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
          ExifInterface.ORIENTATION_ROTATE_90 -> 90
          ExifInterface.ORIENTATION_ROTATE_180 -> 180
          ExifInterface.ORIENTATION_ROTATE_270 -> 270
          else -> 0
        }
      } ?: fallbackOrientation
    } catch (_: Throwable) {
      fallbackOrientation
    }
    if (deg == 0) return bmp
    val m = Matrix().apply { postRotate(deg.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
  }

  private fun scaleLongSide(bmp: Bitmap, maxSide: Int): Bitmap {
    val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
    val w = (bmp.width * scale).toInt().coerceAtLeast(1)
    val h = (bmp.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bmp, w, h, true)
  }

  private fun scaleLongSideOrOriginal(bmp: Bitmap, maxSide: Int): Bitmap {
    if (max(bmp.width, bmp.height) <= maxSide) return bmp
    val scaled = scaleLongSide(bmp, maxSide)
    if (scaled !== bmp) runCatching { bmp.recycle() }
    return scaled
  }
}
