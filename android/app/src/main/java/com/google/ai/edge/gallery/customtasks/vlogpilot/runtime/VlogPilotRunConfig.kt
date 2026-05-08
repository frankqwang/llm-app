/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * User-facing generation controls for VlogPilot. Kept in SharedPreferences so
 * WorkManager jobs and the foreground UI share the same selection intent.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.runtime

import android.content.Context
import kotlinx.serialization.Serializable

@Serializable
enum class GenerationIntent(val label: String) {
  AUTO("\u81ea\u52a8"),
  TRAVEL("\u65c5\u884c"),
  ZOO("\u52a8\u7269\u56ed"),
  PEOPLE("\u4eba\u7269"),
  FOOD("\u7f8e\u98df"),
}

@Serializable
enum class PowerProfile(val label: String) {
  LOW_POWER("\u4f4e\u529f\u8017"),
  BALANCED("\u5747\u8861"),
  HIGH_QUALITY("\u9ad8\u8d28\u91cf"),
}

data class VlogPilotRunConfig(
  val intent: GenerationIntent = GenerationIntent.AUTO,
  val powerProfile: PowerProfile = PowerProfile.LOW_POWER,
  val pinnedEventIds: Set<String> = emptySet(),
  val excludedEventIds: Set<String> = emptySet(),
  val forceRegenerateEventIds: Set<String> = emptySet(),
  val onlySelectedEventIds: Set<String> = emptySet(),
) {
  fun save(context: Context) {
    prefs(context).edit()
      .putString(KEY_INTENT, intent.name)
      .putString(KEY_POWER, powerProfile.name)
      .putStringSet(KEY_PINNED, pinnedEventIds)
      .putStringSet(KEY_EXCLUDED, excludedEventIds)
      .putStringSet(KEY_FORCE, forceRegenerateEventIds)
      .putStringSet(KEY_ONLY, onlySelectedEventIds)
      .apply()
  }

  companion object {
    private const val PREFS = "vlog_pilot_run_config"
    private const val KEY_INTENT = "intent"
    private const val KEY_POWER = "power_profile"
    private const val KEY_PINNED = "pinned_event_ids"
    private const val KEY_EXCLUDED = "excluded_event_ids"
    private const val KEY_FORCE = "force_regenerate_event_ids"
    private const val KEY_ONLY = "only_selected_event_ids"

    fun load(context: Context): VlogPilotRunConfig {
      val p = prefs(context)
      return VlogPilotRunConfig(
        intent = enumValueOrDefault(p.getString(KEY_INTENT, null), GenerationIntent.AUTO),
        powerProfile = enumValueOrDefault(p.getString(KEY_POWER, null), PowerProfile.LOW_POWER),
        pinnedEventIds = p.getStringSet(KEY_PINNED, emptySet()).orEmpty().toSet(),
        excludedEventIds = p.getStringSet(KEY_EXCLUDED, emptySet()).orEmpty().toSet(),
        forceRegenerateEventIds = p.getStringSet(KEY_FORCE, emptySet()).orEmpty().toSet(),
        onlySelectedEventIds = p.getStringSet(KEY_ONLY, emptySet()).orEmpty().toSet(),
      )
    }

    private fun prefs(context: Context) =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
      runCatching { if (value == null) default else enumValueOf<T>(value) }.getOrDefault(default)
  }
}
