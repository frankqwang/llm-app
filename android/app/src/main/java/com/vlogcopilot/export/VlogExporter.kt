/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Closes the product loop: the rendered MP4 lives under filesDir/candidates/
 * and is invisible to the system gallery. This module exposes two operations
 * the UI calls from the result page —
 *   1. saveToGallery: copy the MP4 into MediaStore.Video so Photos / 小红书 /
 *      Wechat see it as a normal video the user can pick.
 *   2. buildShareIntent: wrap the MP4 in a FileProvider URI and build an
 *      ACTION_SEND intent so the user can share directly via the system
 *      chooser without going through gallery first.
 *
 * minSdk = 31 lets us drop the legacy DATA-column branch entirely; everything
 * goes through MediaStore on Android 12+ with RELATIVE_PATH + IS_PENDING.
 */
package com.vlogcopilot.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object VlogExporter {

  private const val TAG = "VlogExporter"
  // User-facing folder under shared Movies/. Visible in Photos / file manager
  // and most third-party social apps' video pickers.
  private const val GALLERY_SUBDIR = "VlogCopilot"

  data class SaveResult(val uri: Uri, val displayName: String, val coverUri: Uri? = null)

  /**
   * Copy the rendered MP4 into the public Movies/VlogCopilot/ folder via
   * MediaStore, and write a poster JPG (the first frame of the video) into
   * Pictures/VlogCopilot/ so social apps + file managers have a real cover.
   *
   * The MP4 itself also carries the vlog title via MediaStore.Video.Media.TITLE
   * so any gallery that surfaces metadata shows the human title (not just the
   * file name). Returns null when the source file is missing or the insert
   * fails. Cover write is best-effort — a failure there doesn't abort the
   * MP4 save (the user still gets the video).
   *
   * Should be called from a coroutine on Dispatchers.IO — does blocking I/O.
   */
  fun saveToGallery(context: Context, mp4Path: String, displayName: String): SaveResult? {
    val source = File(mp4Path)
    if (!source.isFile || source.length() <= 0L) {
      Log.w(TAG, "source mp4 missing or empty: $mp4Path")
      return null
    }
    val rawTitle = displayName.trim()
    val safeName = sanitizeFileName(displayName).ifBlank { "vlog_${System.currentTimeMillis()}" }
    val finalName = if (safeName.endsWith(".mp4", ignoreCase = true)) safeName else "$safeName.mp4"

    val resolver = context.contentResolver
    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val values = ContentValues().apply {
      put(MediaStore.Video.Media.DISPLAY_NAME, finalName)
      // TITLE is the human-readable name surfaced by metadata-aware galleries
      // (Google Photos, vivo's stock gallery). Without it, only the filename
      // shows — which has Chinese chars stripped by sanitizeFileName.
      put(MediaStore.Video.Media.TITLE, rawTitle.ifBlank { finalName })
      put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$GALLERY_SUBDIR")
      put(MediaStore.Video.Media.IS_PENDING, 1)
    }

    val uri = try {
      resolver.insert(collection, values)
    } catch (t: Throwable) {
      Log.e(TAG, "MediaStore insert failed: ${t.message}", t)
      null
    } ?: return null

    val ok = try {
      resolver.openOutputStream(uri)?.use { out ->
        source.inputStream().use { it.copyTo(out) }
      } ?: false.also { Log.w(TAG, "openOutputStream returned null for $uri") }
      true
    } catch (t: Throwable) {
      Log.e(TAG, "copy to MediaStore failed: ${t.message}", t)
      false
    }
    if (!ok) {
      // Best-effort clean up the half-written placeholder so we don't leave
      // a 0-byte entry in the gallery the user has to manually delete.
      runCatching { resolver.delete(uri, null, null) }
      return null
    }

    val finalize = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
    runCatching { resolver.update(uri, finalize, null, null) }

    // Best-effort cover poster — extracted from the video at ~1s in. If this
    // fails we still return success on the MP4; the user can always grab a
    // frame later.
    val coverName = if (safeName.endsWith(".mp4", ignoreCase = true)) {
      safeName.removeSuffix(".mp4").removeSuffix(".MP4")
    } else safeName
    val coverUri = runCatching { writeCoverPoster(context, mp4Path, coverName, rawTitle) }
      .onFailure { Log.w(TAG, "cover write failed: ${it.message}") }
      .getOrNull()
    return SaveResult(uri, finalName, coverUri)
  }

  /** Extract a single frame from the rendered MP4 and save it as a JPG into
   *  Pictures/VlogCopilot/ with the vlog title as TITLE metadata. Lets the user
   *  share the cover separately to platforms that take a custom cover (小红书,
   *  Instagram). Returns the inserted URI or null on failure. */
  private fun writeCoverPoster(
    context: Context,
    mp4Path: String,
    baseName: String,
    title: String,
  ): Uri? {
    val frame = extractFrame(mp4Path) ?: return null
    val resolver = context.contentResolver
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val coverFileName = "$baseName.jpg"
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, coverFileName)
      put(MediaStore.Images.Media.TITLE, title.ifBlank { baseName })
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_SUBDIR")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = try {
      resolver.insert(collection, values)
    } catch (t: Throwable) {
      Log.e(TAG, "cover insert failed: ${t.message}", t)
      null
    } ?: return null

    val wrote = try {
      resolver.openOutputStream(uri)?.use { out ->
        frame.compress(Bitmap.CompressFormat.JPEG, 92, out)
      } ?: false
      true
    } catch (t: Throwable) {
      Log.e(TAG, "cover write failed: ${t.message}", t)
      false
    } finally {
      frame.recycle()
    }
    if (!wrote) {
      runCatching { resolver.delete(uri, null, null) }
      return null
    }
    val finalize = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
    runCatching { resolver.update(uri, finalize, null, null) }
    return uri
  }

  /** Pull a frame ~1 second in from the rendered MP4. Falls back to the very
   *  first frame if the seek fails. Returns null when MediaMetadataRetriever
   *  can't open the file at all. */
  private fun extractFrame(mp4Path: String): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(mp4Path)
      retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (t: Throwable) {
      Log.w(TAG, "extractFrame failed for $mp4Path: ${t.message}")
      null
    } finally {
      runCatching { retriever.release() }
    }
  }

  /**
   * Build a system share-sheet Intent for the given MP4. The chooser includes
   * 小红书 / 微信 / 系统相册 / etc. depending on what the user has installed
   * with a video-receiving filter.
   *
   * Returns null when the source file is missing.
   */
  fun buildShareIntent(context: Context, mp4Path: String, subject: String): Intent? {
    val file = File(mp4Path)
    if (!file.isFile || file.length() <= 0L) return null
    val authority = "${context.packageName}.provider"
    val uri = try {
      FileProvider.getUriForFile(context, authority, file)
    } catch (t: Throwable) {
      Log.e(TAG, "FileProvider.getUriForFile failed: ${t.message}", t)
      return null
    }
    val send = Intent(Intent.ACTION_SEND).apply {
      type = "video/mp4"
      putExtra(Intent.EXTRA_STREAM, uri)
      if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "分享到").apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }

  // Strip path separators and characters MediaStore tends to reject. Whitespace
  // collapses to a single underscore so file managers don't render with awkward
  // double-spaces. Keeps Chinese / unicode letters as-is.
  private fun sanitizeFileName(raw: String): String =
    raw.trim()
      .replace(Regex("[\\\\/:*?\"<>|]"), "")
      .replace(Regex("\\s+"), "_")
      .take(80)
}
