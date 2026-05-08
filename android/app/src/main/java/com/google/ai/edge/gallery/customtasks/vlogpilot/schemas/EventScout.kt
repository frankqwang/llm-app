/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Event-level VLM scout output used before final event selection. This is
 * intentionally coarser than per-asset vlmTags: it answers "is this event
 * worth spending generation compute on?" from paged 3x3 contact sheets.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.schemas

import kotlinx.serialization.Serializable

@Serializable
data class ScoutMoment(
  val assetId: String,
  val pageIndex: Int,
  val cellIndex: Int,
  val why: String = "",
)

@Serializable
data class EventScout(
  val eventId: String,
  val scoutVersion: Int,
  val assetSignature: String,
  val generatedAtMs: Long,
  val eventType: String = "unknown",
  val summary: String = "",
  val storyValue: Float = 0f,
  val visualValue: Float = 0f,
  val subjectValue: Float = 0f,
  val travelScore: Float = 0f,
  val zooScore: Float = 0f,
  val peopleScore: Float = 0f,
  val foodScore: Float = 0f,
  val recommended: Boolean = false,
  val bestMoments: List<ScoutMoment> = emptyList(),
  val rejectReasons: List<String> = emptyList(),
  val pageSummaries: List<String> = emptyList(),
  val pageCount: Int = 0,
  val visualUnitCount: Int = 0,
  val totalAssetCount: Int = 0,
  val sampled: Boolean = false,
) {
  val bestAssetIds: List<String> get() = bestMoments.map { it.assetId }.distinct()
}
