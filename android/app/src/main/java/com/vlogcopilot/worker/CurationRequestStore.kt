/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Stages a UserCurationRequest to disk so the background Worker can pick it
 * up by requestId. Mirrors IterationStore's pending-feedback mechanism but
 * for the user-curated story creation flow.
 *
 * Layout:
 *   filesDir/user_curation_requests/<requestId>.json   ← UserCurationRequest
 *
 * Idempotent: re-submitting the same request (same requestId) is a no-op
 * write. The worker reads but does NOT delete the request file — it stays
 * around for diagnostics and for the requestId-based output cache hit.
 */
package com.vlogcopilot.worker

import android.content.Context
import android.util.Log
import com.vlogcopilot.schemas.UserCurationRequest
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object CurationRequestStore {

  private const val TAG = "CurationRequestStore"
  private const val REQUEST_DIR = "user_curation_requests"
  private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

  /** Computes the stable requestId hash. Same input → same id, so users who
   *  re-submit identical curations hit the existing decisions/<eventId>/ +
   *  candidates/<eventId>.mp4 instead of regenerating. */
  fun computeRequestId(selectedAssetIds: List<String>, intentText: String): String {
    val payload = buildString {
      selectedAssetIds.sorted().forEach { append(it); append('\n') }
      append("---\n")
      append(intentText.trim())
    }
    val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return hex.substring(0, 12)
  }

  /** The eventId we'll use for this curation in DecisionStore / candidates / etc. */
  fun eventIdFor(requestId: String): String = "user_$requestId"

  fun stage(context: Context, request: UserCurationRequest) {
    try {
      val dir = File(context.filesDir, REQUEST_DIR).apply { mkdirs() }
      File(dir, "${request.requestId}.json").writeText(json.encodeToString(request))
    } catch (t: Throwable) {
      Log.w(TAG, "stage failed for ${request.requestId}: ${t.message}")
    }
  }

  fun load(context: Context, requestId: String): UserCurationRequest? {
    val file = File(File(context.filesDir, REQUEST_DIR), "$requestId.json")
    if (!file.isFile) return null
    return try {
      json.decodeFromString<UserCurationRequest>(file.readText())
    } catch (t: Throwable) {
      Log.w(TAG, "load failed for $requestId: ${t.message}")
      null
    }
  }

  fun listAll(context: Context): List<UserCurationRequest> {
    val dir = File(context.filesDir, REQUEST_DIR)
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
      ?.mapNotNull { f ->
        try { json.decodeFromString<UserCurationRequest>(f.readText()) }
        catch (_: Throwable) { null }
      }
      ?.sortedByDescending { it.createdAtMs }
      .orEmpty()
  }
}
