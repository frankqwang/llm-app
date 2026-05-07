/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * OTA downloader for the perception models that aren't bundled in assets/.
 * Each model lives under <filesDir>/models/<name>; if the file already exists
 * with the expected size we skip. Resume on partial download via Range header.
 *
 * Gemma 4 itself is downloaded by the gallery's existing model-manager flow
 * (DownloadRepository). This downloader handles the smaller perception models.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

object ModelDownloader {

  private const val TAG = "ModelDownloader"
  private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.SECONDS)
    .build()

  data class ModelSpec(val fileName: String, val url: String, val sizeBytes: Long)

  /** Smaller perception models — Gemma 4 is handled separately by the gallery model manager. */
  val PERCEPTION_MODELS = listOf(
    ModelSpec(
      fileName = "mobileclip2_s1_image.tflite",
      url = "https://huggingface.co/anton96vice/mobileclip2_tflite/resolve/main/mobileclip_s1_datacompdr_image.tflite",
      sizeBytes = 26_000_000L,
    ),
    ModelSpec(
      fileName = "mobileclip2_s1_text.tflite",
      url = "https://huggingface.co/anton96vice/mobileclip2_tflite/resolve/main/mobileclip_s1_datacompdr_text.tflite",
      sizeBytes = 130_000_000L,
    ),
    ModelSpec(
      fileName = "nsfw_vit_int8.onnx",
      url = "https://huggingface.co/AdamCodd/vit-base-nsfw-detector/resolve/main/onnx/model_int8.onnx",
      sizeBytes = 88_500_000L,
    ),
    ModelSpec(
      fileName = "clip_vocab.json",
      url = "https://huggingface.co/openai/clip-vit-base-patch32/resolve/main/vocab.json",
      sizeBytes = 1_000_000L,
    ),
    ModelSpec(
      fileName = "clip_merges.txt",
      url = "https://huggingface.co/openai/clip-vit-base-patch32/resolve/main/merges.txt",
      sizeBytes = 525_000L,
    ),
  )

  /** Stream progress as a percentage (0..100) per model, then "done". */
  fun downloadAll(context: Context): Flow<Pair<Int, String>> = flow {
    val baseDir = File(context.filesDir, "models").apply { mkdirs() }
    val totalBytes = PERCEPTION_MODELS.sumOf { it.sizeBytes }
    var bytesSoFar = 0L
    for (spec in PERCEPTION_MODELS) {
      val target = File(baseDir, spec.fileName)
      if (target.isFile && target.length() >= spec.sizeBytes * 0.95) {
        bytesSoFar += spec.sizeBytes
        emit(((bytesSoFar * 100) / totalBytes).toInt() to spec.fileName)
        continue
      }
      try {
        val downloaded = downloadOne(spec, target)
        bytesSoFar += downloaded
        emit(((bytesSoFar * 100) / totalBytes).toInt() to spec.fileName)
      } catch (t: Throwable) {
        Log.w(TAG, "download failed for ${spec.fileName}: ${t.message}")
        // Pipeline can run with degraded perception — keep going.
      }
    }
    emit(100 to "done")
  }.flowOn(Dispatchers.IO)

  private fun downloadOne(spec: ModelSpec, target: File): Long {
    val tmp = File(target.parentFile, "${target.name}.part")
    val req = Request.Builder().url(spec.url).build()
    client.newCall(req).execute().use { response ->
      if (!response.isSuccessful) error("HTTP ${response.code}")
      val body = response.body ?: error("empty body")
      tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
    }
    if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true).also { tmp.delete() }
    return target.length()
  }
}
