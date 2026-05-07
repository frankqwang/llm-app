/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * One-stop TFLite Interpreter loader. Tries (1) absolute path on disk, (2)
 * file under filesDir/models, (3) packaged asset under assets/models. Returns
 * null if none resolve so callers can degrade to no-op rather than crash.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

object TfliteLoader {
  private const val TAG = "TfliteLoader"

  fun load(context: Context, nameOrPath: String, useGpu: Boolean = false): Interpreter? {
    val buffer = resolveBuffer(context, nameOrPath) ?: run {
      Log.w(TAG, "TFLite model not found anywhere: $nameOrPath")
      return null
    }
    val opts = Interpreter.Options().apply {
      setNumThreads(2)
      // GPU delegate is left off by default — adding it requires play-services-tflite-gpu
      // bridging to org.tensorflow.lite.Interpreter, which the gallery already has wired.
    }
    return try {
      Interpreter(buffer, opts)
    } catch (t: Throwable) {
      Log.e(TAG, "TFLite Interpreter init failed for $nameOrPath", t)
      null
    }
  }

  private fun resolveBuffer(context: Context, nameOrPath: String): ByteBuffer? {
    // (1) Absolute path
    File(nameOrPath).takeIf { it.isFile }?.let { return mmap(it) }
    // (2) filesDir/models/<basename>
    val basename = File(nameOrPath).name
    File(context.filesDir, "models/$basename").takeIf { it.isFile }?.let { return mmap(it) }
    // (3) asset
    val assetPath = if (nameOrPath.startsWith("models/")) nameOrPath else "models/$basename"
    return try {
      context.assets.openFd(assetPath).use { fd ->
        FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
      }
    } catch (_: Throwable) {
      null
    }
  }

  private fun mmap(file: File): MappedByteBuffer =
    FileInputStream(file).channel.use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }
}
