/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Persistent cache for per-asset Perception output. Key = asset.id (a stable
 * SHA-1 hash of the contentUri, so cache survives between runs). The first run
 * pays the full ~1-2s/asset cost; subsequent runs read JSON instantly. The
 * Perception schema is small so the JSON cache is a few KB per asset.
 *
 * Invalidation: cache is keyed by assetId only. If a user edits a photo
 * in-place keeping the same MediaStore id (rare on Android — most edits get
 * a new id), the cache will be stale; the simple workaround is to delete
 * filesDir/perception_cache/ when that matters.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object PerceptionCache {

  private const val TAG = "PerceptionCache"
  private val json = Json { ignoreUnknownKeys = true }

  fun get(context: Context, assetId: String): Perception? {
    val f = File(context.filesDir, "perception_cache/$assetId.json")
    if (!f.isFile) return null
    return try {
      json.decodeFromString<Perception>(f.readText())
    } catch (t: Throwable) {
      Log.w(TAG, "stale cache entry for $assetId: ${t.message}")
      f.delete()
      null
    }
  }

  fun put(context: Context, asset: Asset, p: Perception) {
    try {
      val f = File(context.filesDir, "perception_cache/${asset.id}.json")
      f.parentFile?.mkdirs()
      f.writeText(json.encodeToString(p))
    } catch (t: Throwable) {
      Log.w(TAG, "write failed for ${asset.id}: ${t.message}")
    }
  }
}
