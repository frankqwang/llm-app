/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Before each render, move the previous candidate MP4 (if any) into
 * candidates/archive/<eventId>__<yyyyMMdd_HHmmss>.mp4 so the user can A/B
 * compare iterations.
 */
package com.vlogcopilot.render

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VersionArchive {

  private val STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

  /** Move src to archive/<eventId>__<timestamp>.mp4 if it exists. */
  fun archivePrevious(candidatesDir: File, eventId: String) {
    val current = File(candidatesDir, "$eventId.mp4")
    if (!current.isFile) return
    val archiveDir = File(candidatesDir, "archive").apply { mkdirs() }
    val stamp = STAMP.format(Date(current.lastModified()))
    val target = File(archiveDir, "${eventId}__$stamp.mp4")
    if (!current.renameTo(target)) {
      // Fallback: copy then delete
      try {
        current.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        current.delete()
      } catch (_: Throwable) {
        // best-effort; leave the file alone if archiving fails
      }
    }
  }

  fun listArchive(candidatesDir: File, eventId: String): List<File> =
    File(candidatesDir, "archive").listFiles()
      ?.filter { it.name.startsWith("${eventId}__") && it.name.endsWith(".mp4") }
      ?.sortedDescending()
      .orEmpty()
}
