/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * In-memory LRU cache for grid thumbnails. Without this, the album grid
 * re-decodes every visible thumbnail on every recompose / scroll spike,
 * which is the dominant source of jank when scrolling 60+ assets at 60fps.
 *
 * Sized as a fraction of the heap budget (default 1/8) so the cache can hold
 * a few hundred thumbs without pressuring GC. Bitmaps stored here SHOULD NOT
 * be recycled by callers — the cache reuses them across composables.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import android.content.Context
import android.graphics.Bitmap
import androidx.collection.LruCache
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ThumbnailCache {

  /** Cache bucketed by (assetId, maxSide). Two callers asking for different
   *  sizes get separate entries. */
  private data class Key(val assetId: String, val maxSide: Int)

  // 1/8 of available heap, in bytes. On a 256 MB-heap device that's ~32 MB,
  // enough for ~500 RGB_565 thumbs at 240px.
  private val cache: LruCache<Key, Bitmap> = run {
    val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    val cacheKb = (maxKb / 8).coerceAtLeast(8 * 1024) // at least 8 MB
    object : LruCache<Key, Bitmap>(cacheKb) {
      override fun sizeOf(key: Key, value: Bitmap): Int = value.byteCount / 1024
    }
  }

  /** Returns the cached bitmap, or decodes via [MediaLoader] on Dispatchers.IO
   *  and stores the result. Suspending so callers don't block the main thread.
   *
   *  @param maxSide longest-side target in pixels
   *  @param highQuality when false (default), uses RGB_565 for grid thumbs;
   *      pass true when the bitmap will be the hero in a detail view. */
  suspend fun loadOrDecode(
    context: Context,
    asset: Asset,
    maxSide: Int,
    highQuality: Boolean = false,
  ): Bitmap? {
    val key = Key(asset.id, maxSide)
    cache.get(key)?.let { return it }
    val bitmap = withContext(Dispatchers.IO) {
      MediaLoader.loadImage(context, asset, maxSide = maxSide, preferRgb565 = !highQuality)
    } ?: return null
    cache.put(key, bitmap)
    return bitmap
  }

  /** Synchronous lookup only — null if not cached. Used by hot paths that
   *  want to skip the suspending decode and let a placeholder render until
   *  the async path catches up. */
  fun peek(asset: Asset, maxSide: Int): Bitmap? = cache.get(Key(asset.id, maxSide))

  /** Drop everything. Call when permissions change (re-scan triggers fresh
   *  Asset ids), or under explicit memory pressure. */
  fun clear() {
    cache.evictAll()
  }
}
