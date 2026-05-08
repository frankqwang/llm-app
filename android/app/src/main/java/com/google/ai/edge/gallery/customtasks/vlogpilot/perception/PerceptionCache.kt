/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Persistent cache for per-asset Perception output. Key = asset.id, with an
 * explicit algorithm version + media signature inside the file so pipeline
 * upgrades do not silently reuse stale video sampling / scene-cut results.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object PerceptionCache {

  private const val TAG = "PerceptionCache"
  private const val CACHE_VERSION = 3
  private val json = Json { ignoreUnknownKeys = true }

  fun get(context: Context, assetId: String): Perception? {
    val f = cacheFile(context, assetId)
    if (!f.isFile) return null
    return readEntry(f)?.perception
  }

  fun get(context: Context, asset: Asset): Perception? {
    val f = cacheFile(context, asset.id)
    if (!f.isFile) return null
    val entry = readEntry(f) ?: return null
    if (entry.version != CACHE_VERSION || entry.assetSignature != signature(asset)) {
      Log.i(TAG, "invalidate ${asset.id}: version=${entry.version} current=$CACHE_VERSION")
      f.delete()
      return null
    }
    return entry.perception
  }

  private fun readEntry(f: File): CacheEntry? {
    return try {
      json.decodeFromString<CacheEntry>(f.readText())
    } catch (t: Throwable) {
      Log.w(TAG, "stale cache entry ${f.name}: ${t.message}")
      f.delete()
      null
    }
  }

  fun put(context: Context, asset: Asset, p: Perception) {
    try {
      val f = cacheFile(context, asset.id)
      f.parentFile?.mkdirs()
      f.writeText(json.encodeToString(CacheEntry(CACHE_VERSION, signature(asset), p)))
    } catch (t: Throwable) {
      Log.w(TAG, "write failed for ${asset.id}: ${t.message}")
    }
  }

  private fun cacheFile(context: Context, assetId: String): File =
    File(context.filesDir, "perception_cache/$assetId.json")

  private fun signature(asset: Asset): String =
    listOf(
      asset.contentUri,
      asset.mediaType.name,
      asset.takenEpochMs,
      asset.durationMs,
      asset.sizeBytes,
      asset.widthPx,
      asset.heightPx,
      asset.livePhotoVideoUri.orEmpty(),
    ).joinToString("|")

  @Serializable
  private data class CacheEntry(
    val version: Int,
    val assetSignature: String,
    val perception: Perception,
  )
}
