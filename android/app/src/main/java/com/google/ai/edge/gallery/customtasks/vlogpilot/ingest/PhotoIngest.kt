/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * step0 ingest, on-device port. Queries MediaStore for images + videos in a date
 * window, reads EXIF for DateTimeOriginal / GPS / orientation, and pairs vivo
 * Live Photo (image + sibling .mp4 with same stem) into LIVE_PHOTO assets.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.ingest

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PhotoIngest {

  /** What to keep when reading from MediaStore. Defaults: only camera originals
   *  (DCIM/), drop big videos, drop tiny garbage images. */
  data class Filter(
    val cameraOnly: Boolean = true,
    /** Additional substring matches against RELATIVE_PATH (case-insensitive).
     *  Useful for "include this device-specific folder too", e.g. "Pictures/Pocket3/". */
    val extraIncludePaths: List<String> = emptyList(),
    val maxVideoSizeBytes: Long = 512L * 1024 * 1024,   // keep normal travel clips; still skip huge raw files
    val maxImageSizeBytes: Long = 50L * 1024 * 1024,    // 50 MB images skipped
    val minImageSizeBytes: Long = 50L * 1024,           // <50 KB = thumbnail/junk, skip
  )

  /** Read all images + videos taken within `windowDays` of now, filtered by
   *  [filter]. Defaults skip non-camera (screenshots / WeChat / Downloads),
   *  oversized videos, and tiny / oversized images. */
  suspend fun loadRecent(
    context: Context,
    windowDays: Int = 30,
    filter: Filter = Filter(),
  ): List<Asset> =
    withContext(Dispatchers.IO) {
      val cutoffMs = System.currentTimeMillis() - windowDays.toLong() * 86_400_000L
      val images = queryImages(context, cutoffMs, filter)
      val videos = queryVideos(context, cutoffMs, filter)
      pairLivePhotos(images, videos).sortedBy { it.takenEpochMs }
    }

  private fun pathPasses(relativePath: String?, filter: Filter): Boolean {
    val p = (relativePath ?: "").lowercase(Locale.ROOT)
    if (filter.cameraOnly) {
      // DCIM/ is where camera-originals land on every Android phone.
      val isCamera = p.startsWith("dcim/")
      val isExtra = filter.extraIncludePaths.any { p.contains(it.lowercase(Locale.ROOT)) }
      return isCamera || isExtra
    }
    return true
  }

  // ---------- images ----------

  private fun queryImages(context: Context, cutoffMs: Long, filter: Filter): List<Asset> {
    val proj = arrayOf(
      MediaStore.Images.Media._ID,
      MediaStore.Images.Media.DISPLAY_NAME,
      MediaStore.Images.Media.DATE_TAKEN,
      MediaStore.Images.Media.DATE_MODIFIED,
      MediaStore.Images.Media.WIDTH,
      MediaStore.Images.Media.HEIGHT,
      MediaStore.Images.Media.SIZE,
      MediaStore.Images.Media.ORIENTATION,
      MediaStore.Images.Media.RELATIVE_PATH,
    )
    val sel = "${MediaStore.Images.Media.DATE_TAKEN} >= ?"
    val args = arrayOf(cutoffMs.toString())
    val out = mutableListOf<Asset>()
    context.contentResolver.query(
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null,
    )?.use { c ->
      val idx = ColIdx.of(c, proj)
      while (c.moveToNext()) {
        val id = c.getLong(idx[MediaStore.Images.Media._ID]!!)
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val name = c.getString(idx[MediaStore.Images.Media.DISPLAY_NAME]!!) ?: "img_$id"
        val takenDb = c.getLong(idx[MediaStore.Images.Media.DATE_TAKEN]!!)
        val taken = if (takenDb > 0) takenDb else c.getLong(idx[MediaStore.Images.Media.DATE_MODIFIED]!!) * 1000
        val w = c.getInt(idx[MediaStore.Images.Media.WIDTH]!!)
        val h = c.getInt(idx[MediaStore.Images.Media.HEIGHT]!!)
        val size = c.getLong(idx[MediaStore.Images.Media.SIZE]!!)
        val orient = c.getInt(idx[MediaStore.Images.Media.ORIENTATION]!!)
        val relPath = c.getString(idx[MediaStore.Images.Media.RELATIVE_PATH]!!)

        // Path / size filters — skip junk before paying EXIF read.
        if (!pathPasses(relPath, filter)) continue
        if (size in 1 until filter.minImageSizeBytes) continue
        if (size > filter.maxImageSizeBytes) continue

        // EXIF read for accurate timestamp and GPS (DATE_TAKEN can be wrong for synced files)
        val (exifTaken, lat, lon) = readExif(context, uri)
        val finalTaken = exifTaken ?: taken

        out += Asset(
          id = stableId(uri.toString()),
          contentUri = uri.toString(),
          displayName = name,
          mediaType = MediaType.IMAGE,
          takenEpochMs = finalTaken,
          widthPx = w, heightPx = h,
          sizeBytes = size,
          orientation = orient,
          latitude = lat, longitude = lon,
        )
      }
    }
    return out
  }

  // ---------- videos ----------

  private fun queryVideos(context: Context, cutoffMs: Long, filter: Filter): List<Asset> {
    val proj = arrayOf(
      MediaStore.Video.Media._ID,
      MediaStore.Video.Media.DISPLAY_NAME,
      MediaStore.Video.Media.DATE_TAKEN,
      MediaStore.Video.Media.DATE_MODIFIED,
      MediaStore.Video.Media.WIDTH,
      MediaStore.Video.Media.HEIGHT,
      MediaStore.Video.Media.DURATION,
      MediaStore.Video.Media.SIZE,
      MediaStore.Video.Media.RELATIVE_PATH,
    )
    val sel = "${MediaStore.Video.Media.DATE_TAKEN} >= ?"
    val args = arrayOf(cutoffMs.toString())
    val out = mutableListOf<Asset>()
    context.contentResolver.query(
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null,
    )?.use { c ->
      val idx = ColIdx.of(c, proj)
      while (c.moveToNext()) {
        val id = c.getLong(idx[MediaStore.Video.Media._ID]!!)
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        val name = c.getString(idx[MediaStore.Video.Media.DISPLAY_NAME]!!) ?: "vid_$id"
        val takenDb = c.getLong(idx[MediaStore.Video.Media.DATE_TAKEN]!!)
        val taken = if (takenDb > 0) takenDb else c.getLong(idx[MediaStore.Video.Media.DATE_MODIFIED]!!) * 1000
        val w = c.getInt(idx[MediaStore.Video.Media.WIDTH]!!)
        val h = c.getInt(idx[MediaStore.Video.Media.HEIGHT]!!)
        val dur = c.getLong(idx[MediaStore.Video.Media.DURATION]!!)
        val size = c.getLong(idx[MediaStore.Video.Media.SIZE]!!)
        val relPath = c.getString(idx[MediaStore.Video.Media.RELATIVE_PATH]!!)

        if (!pathPasses(relPath, filter)) continue
        if (size > filter.maxVideoSizeBytes) continue

        out += Asset(
          id = stableId(uri.toString()),
          contentUri = uri.toString(),
          displayName = name,
          mediaType = MediaType.VIDEO,
          takenEpochMs = taken,
          widthPx = w, heightPx = h,
          durationMs = dur,
          sizeBytes = size,
        )
      }
    }
    return out
  }

  // ---------- live photo pairing ----------

  /**
   * vivo motion photos: an image `IMG_20251201_143022.jpg` and a sibling video
   * `IMG_20251201_143022.mp4` with the same DISPLAY_NAME stem and a takenEpochMs
   * within 2 seconds. When found, demote the video and promote the image to
   * LIVE_PHOTO with `livePhotoVideoUri` pointing at the video.
   */
  private fun pairLivePhotos(images: List<Asset>, videos: List<Asset>): List<Asset> {
    val videoByStem = videos.groupBy { stem(it.displayName) }
    val pairedVideoIds = mutableSetOf<String>()
    val out = mutableListOf<Asset>()
    for (img in images) {
      val candidates = videoByStem[stem(img.displayName)].orEmpty()
      val pair = candidates.firstOrNull { kotlin.math.abs(it.takenEpochMs - img.takenEpochMs) < 2000 }
      if (pair != null) {
        out += img.copy(
          mediaType = MediaType.LIVE_PHOTO,
          livePhotoVideoUri = pair.contentUri,
          durationMs = pair.durationMs,
        )
        pairedVideoIds += pair.id
      } else {
        out += img
      }
    }
    out += videos.filter { it.id !in pairedVideoIds }
    return out
  }

  private fun stem(name: String): String =
    name.substringBeforeLast('.', name).lowercase(Locale.ROOT)

  // ---------- exif ----------

  private fun readExif(context: Context, uri: android.net.Uri): Triple<Long?, Double?, Double?> {
    return try {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        val exif = ExifInterface(stream)
        val tsStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
          ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        val ts = parseExifDate(tsStr)
        // Only touch latLong when GPS tags are actually present and non-empty.
        // exif.latLong internally parses the GPS rational fields and prints a
        // warning when they are 0/1,0/1,0/1 + blank ref (common for screenshots
        // / downloaded images). Checking the raw attribute first silences the
        // logspam during album scans.
        val hasGps = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)?.isNotBlank() == true &&
          exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)?.isNotBlank() == true
        val latLon = if (hasGps) exif.latLong else null
        Triple(ts, latLon?.get(0), latLon?.get(1))
      } ?: Triple(null, null, null)
    } catch (_: Throwable) {
      Triple(null, null, null)
    }
  }

  private fun parseExifDate(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    return try {
      // EXIF format: "yyyy:MM:dd HH:mm:ss" — local time, no zone
      val parts = s.split(" ")
      val date = parts[0].split(":").map { it.toInt() }
      val time = parts.getOrNull(1)?.split(":")?.map { it.toInt() } ?: listOf(0, 0, 0)
      val cal = java.util.Calendar.getInstance()
      cal.set(date[0], date[1] - 1, date[2], time[0], time[1], time.getOrElse(2) { 0 })
      cal.set(java.util.Calendar.MILLISECOND, 0)
      cal.timeInMillis
    } catch (_: Throwable) {
      null
    }
  }

  // ---------- helpers ----------

  private fun stableId(s: String): String {
    val md = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
    return md.take(8).joinToString("") { "%02x".format(it) }
  }

  private object ColIdx {
    fun of(c: android.database.Cursor, cols: Array<String>): Map<String, Int> =
      cols.associateWith { c.getColumnIndexOrThrow(it) }
  }
}
