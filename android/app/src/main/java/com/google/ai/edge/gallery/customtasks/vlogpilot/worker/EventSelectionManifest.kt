/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Durable event-selection explanation shown in the VlogPilot "事件选择" tab.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.GenerationIntent
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.PowerProfile
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EventSelectionStatus {
  @SerialName("selected") SELECTED,
  @SerialName("resume") RESUME,
  @SerialName("fresh") FRESH,
  @SerialName("completed") COMPLETED,
  @SerialName("excluded") EXCLUDED,
  @SerialName("not_selected") NOT_SELECTED,
}

@Serializable
data class EventCandidateSnapshot(
  val event: Event,
  val assets: List<Asset>,
  val status: EventSelectionStatus,
  val valueScore: Float,
  val travelScore: Float,
  val mediaScore: Float,
  val storyScore: Float,
  val qualityScore: Float,
  val recencyScore: Float,
  val realVideoCount: Int,
  val longVideoCount: Int,
  val realVideoSeconds: Float,
  val gpsAssetCount: Int,
  val spanHours: Float,
  val reasons: List<String> = emptyList(),
  val rankingMode: String = "metadata_only",
  val scoutEventType: String = "",
  val scoutSummary: String = "",
  val scoutStoryValue: Float = 0f,
  val scoutVisualValue: Float = 0f,
  val scoutSubjectValue: Float = 0f,
  val scoutRecommended: Boolean = false,
  val scoutBestAssetIds: List<String> = emptyList(),
  val scoutRejectReasons: List<String> = emptyList(),
  val scoutPageCount: Int = 0,
  val scoutSampled: Boolean = false,
) {
  val eventId: String get() = event.eventId
}

@Serializable
data class EventSelectionManifest(
  val generatedAtMs: Long,
  val intent: GenerationIntent,
  val powerProfile: PowerProfile,
  val candidateCount: Int,
  val selectedEventIds: List<String>,
  val candidates: List<EventCandidateSnapshot>,
)
