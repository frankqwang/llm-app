/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Bridges VlogCopilotViewModel → VlogPipelineWorker. Two layers:
 *
 *  (1) Process-local: a @Volatile var `resolvedModel` holding the live Model
 *      object. Fast path; the Worker reads this directly when it runs in the
 *      same process the ViewModel was alive in (i.e. >95% of cases).
 *
 *  (2) Disk-backed: SharedPreferences holds enough model metadata to recreate
 *      a LiteRT-LM Model after process death. WorkManager can then retry a
 *      foreground run without depending on a ViewModel-owned in-memory object.
 */
package com.vlogcopilot.runtime

import android.content.Context
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.createLlmChatConfigs
import java.io.File

object VlogCopilotModelRegistry {

  private const val PREFS_NAME = "vlog_copilot_runtime"
  private const val KEY_MODEL_NAME = "last_enqueued_model_name"
  private const val KEY_DISPLAY_NAME = "last_enqueued_display_name"
  private const val KEY_DOWNLOAD_FILE_NAME = "last_enqueued_download_file_name"
  private const val KEY_VERSION = "last_enqueued_version"
  private const val KEY_IMPORTED = "last_enqueued_imported"
  private const val KEY_SIZE_BYTES = "last_enqueued_size_bytes"
  private const val KEY_RUNTIME_TYPE = "last_enqueued_runtime_type"
  private const val KEY_SUPPORT_IMAGE = "last_enqueued_support_image"
  private const val KEY_SUPPORT_AUDIO = "last_enqueued_support_audio"
  private const val KEY_MAX_TOKENS = "last_enqueued_max_tokens"
  private const val KEY_ACCELERATORS = "last_enqueued_accelerators"
  private const val KEY_VISION_ACCELERATOR = "last_enqueued_vision_accelerator"

  /** Live Model used by the Worker on the fast path. */
  @Volatile var resolvedModel: Model? = null

  fun stash(context: Context, model: Model) {
    resolvedModel = model
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_MODEL_NAME, model.name)
      .putString(KEY_DISPLAY_NAME, model.displayName)
      .putString(KEY_DOWNLOAD_FILE_NAME, model.downloadFileName)
      .putString(KEY_VERSION, model.version)
      .putBoolean(KEY_IMPORTED, model.imported)
      .putLong(KEY_SIZE_BYTES, model.sizeInBytes)
      .putString(KEY_RUNTIME_TYPE, model.runtimeType.name)
      .putBoolean(KEY_SUPPORT_IMAGE, model.llmSupportImage)
      .putBoolean(KEY_SUPPORT_AUDIO, model.llmSupportAudio)
      .putInt(KEY_MAX_TOKENS, model.llmMaxToken)
      .putString(KEY_ACCELERATORS, model.accelerators.joinToString(",") { it.label })
      .putString(KEY_VISION_ACCELERATOR, model.visionAccelerator.label)
      .apply()
  }

  fun resolve(context: Context): Model? {
    resolvedModel?.let { return it }
    return recreateFromPrefs(context)?.also { resolvedModel = it }
  }

  /** Read the disk-persisted last-enqueued model name. Returns null if no
   *  pipeline was ever started, or if the prefs file was wiped. */
  fun lastEnqueuedModelName(context: Context): String? =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getString(KEY_MODEL_NAME, null)

  private fun recreateFromPrefs(context: Context): Model? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val name = prefs.getString(KEY_MODEL_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
    val downloadFileName = prefs.getString(KEY_DOWNLOAD_FILE_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
    val accelerators = prefs.getString(KEY_ACCELERATORS, null)
      ?.split(',')
      ?.mapNotNull { parseAccelerator(it) }
      ?.takeIf { it.isNotEmpty() }
      ?: listOf(Accelerator.GPU)
    val runtimeType = runCatching {
      RuntimeType.valueOf(prefs.getString(KEY_RUNTIME_TYPE, RuntimeType.LITERT_LM.name) ?: RuntimeType.LITERT_LM.name)
    }.getOrDefault(RuntimeType.LITERT_LM)
    val maxTokens = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKEN).takeIf { it > 0 } ?: DEFAULT_MAX_TOKEN
    val model = Model(
      name = name,
      displayName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty(),
      url = "",
      configs = createLlmChatConfigs(defaultMaxToken = maxTokens, accelerators = accelerators),
      sizeInBytes = prefs.getLong(KEY_SIZE_BYTES, 0L),
      downloadFileName = downloadFileName,
      version = prefs.getString(KEY_VERSION, "_").orEmpty(),
      imported = prefs.getBoolean(KEY_IMPORTED, false),
      isLlm = true,
      runtimeType = runtimeType,
      llmSupportImage = prefs.getBoolean(KEY_SUPPORT_IMAGE, true),
      llmSupportAudio = prefs.getBoolean(KEY_SUPPORT_AUDIO, false),
      llmMaxToken = maxTokens,
      accelerators = accelerators,
      visionAccelerator = parseAccelerator(prefs.getString(KEY_VISION_ACCELERATOR, null)) ?: Accelerator.GPU,
    )
    model.preProcess()
    return model.takeIf { File(it.getPath(context)).exists() }
  }

  private fun parseAccelerator(label: String?): Accelerator? =
    Accelerator.entries.firstOrNull { it.label.equals(label?.trim(), ignoreCase = true) }
}
