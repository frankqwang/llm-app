/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Bitmap loaders for images and video frames. Centralizes EXIF-rotation,
 * downscale, and color-space concerns so the model wrappers don't repeat them.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import kotlin.math.max

object MediaLoader {

  /** Decode the asset to a single Bitmap with the longest side ≤ maxSide.
   *  - IMAGE / LIVE_PHOTO: decode the still (contentUri).
   *  - VIDEO: pull a frame from the middle of the clip via MediaMetadataRetriever.
   *           BitmapFactory.decodeStream cannot decode an MP4 container, which is
   *           why all `content://media/external/video/...` URIs were failing here. */
  fun loadImage(context: Context, asset: Asset, maxSide: Int = 1024): Bitmap? {
    return when (asset.mediaType) {
      MediaType.IMAGE, MediaType.LIVE_PHOTO -> decodeStillImage(context, asset, maxSide)
      MediaType.VIDEO -> {
        val midSec = if (asset.durationMs > 0) (asset.durationMs / 2_000f) else 0.5f
        loadVideoFrame(context, asset, midSec, maxSide)
      }
    }
  }

  private fun decodeStillImage(context: Context, asset: Asset, maxSide: Int): Bitmap? {
    val uri = Uri.parse(asset.contentUri)
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
      val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
      val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.ARGB_8888 }
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
    val uri = when {
      asset.mediaType == MediaType.LIVE_PHOTO -> asset.livePhotoVideoUri?.let(Uri::parse)
      else -> Uri.parse(asset.contentUri)
    } ?: return null
    val mmr = MediaMetadataRetriever()
    return try {
      mmr.setDataSource(context, uri)
      val timeUs = (atSec * 1_000_000).toLong()
      val raw = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
      if (max(raw.width, raw.height) <= maxSide) raw else scaleLongSide(raw, maxSide)
    } catch (_: Throwable) {
      null
    } finally {
      try { mmr.release() } catch (_: Throwable) {}
    }
  }

  /** Sample N evenly spaced frames from a video, returns (timestampSec, bitmap) pairs. */
  fun sampleVideoFrames(context: Context, asset: Asset, n: Int, maxSide: Int = 1024): List<Pair<Float, Bitmap>> {
    val durMs = asset.durationMs.takeIf { it > 0 } ?: return emptyList()
    val out = mutableListOf<Pair<Float, Bitmap>>()
    for (i in 0 until n) {
      val tSec = durMs / 1000f * (i + 0.5f) / n
      val bmp = loadVideoFrame(context, asset, tSec, maxSide)
      if (bmp != null) out += tSec to bmp
    }
    return out
  }

  // ----- helpers -----

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
}
