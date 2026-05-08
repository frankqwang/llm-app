/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Shared event-selection policy for both the foreground "refresh candidates"
 * action and the background generation worker. Keeping the policy here avoids
 * the UI explaining one ranking while the worker quietly runs another.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

import android.content.Context
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.PerceptionCache
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.EventSelector
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.VlogPilotRunConfig
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.EventScout
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import java.io.File

object EventSelectionPlanner {

  data class Plan(
    val manifest: EventSelectionManifest,
    val selectedEvents: List<Event>,
    val rankedCandidates: List<EventSelector.Candidate>,
    val resumeEventIds: Set<String>,
  )

  fun plan(
    context: Context,
    assets: List<Asset>,
    events: List<Event>,
    runConfig: VlogPilotRunConfig,
    scouts: Map<String, EventScout> = emptyMap(),
    maxEvents: Int = 2,
    manifestLimit: Int = 12,
    nowMs: Long = System.currentTimeMillis(),
  ): Plan {
    val assetMap = assets.associateBy { it.id }
    val selectionPerceptions = HashMap<String, Perception?>()
    fun selectionPerception(assetId: String): Perception? =
      selectionPerceptions.getOrPut(assetId) { assetMap[assetId]?.let { PerceptionCache.get(context, it) } }

    val ranked = EventSelector.rank(
      events = events,
      assetMap = assetMap,
      perceptionFor = ::selectionPerception,
      scoutFor = { scouts[it] },
      intent = runConfig.intent,
      nowMs = nowMs,
    )
    val excludedIds = runConfig.excludedEventIds
    val selectable = ranked.filter { it.eventId !in excludedIds }
    val selectedCandidates = selectCandidates(context, selectable, runConfig, maxEvents)
    val selectedIds = selectedCandidates.map { it.eventId }.toSet()
    val resumeIds = selectable.filter { isResumable(context, it.eventId) }.map { it.eventId }.toSet()
    val manifestCandidates = manifestCandidates(
      ranked = ranked,
      selectedIds = selectedIds,
      runConfig = runConfig,
      manifestLimit = manifestLimit,
    ).map { candidate ->
      candidate.toSnapshot(
        context = context,
        selectedIds = selectedIds,
        excludedIds = excludedIds,
      )
    }

    return Plan(
      manifest = EventSelectionManifest(
        generatedAtMs = nowMs,
        intent = runConfig.intent,
        powerProfile = runConfig.powerProfile,
        candidateCount = ranked.size,
        selectedEventIds = selectedCandidates.map { it.eventId },
        candidates = manifestCandidates,
      ),
      selectedEvents = selectedCandidates.map { it.event },
      rankedCandidates = ranked,
      resumeEventIds = resumeIds,
    )
  }

  private fun selectCandidates(
    context: Context,
    ranked: List<EventSelector.Candidate>,
    runConfig: VlogPilotRunConfig,
    maxEvents: Int,
  ): List<EventSelector.Candidate> {
    if (runConfig.onlySelectedEventIds.isNotEmpty()) {
      return ranked.filter { it.eventId in runConfig.onlySelectedEventIds }
    }

    val resume = ranked.filter { isResumable(context, it.eventId) }
    val resumeIds = resume.map { it.eventId }.toSet()
    val fresh = ranked
      .filter { it.eventId !in resumeIds }
      .filter { !isCompleted(context, it.eventId) }
      .filter { it.eventId !in runConfig.forceRegenerateEventIds }
    val pinnedFresh = fresh.filter { it.eventId in runConfig.pinnedEventIds }
    val highFresh = fresh.filter { it.eventId !in runConfig.pinnedEventIds }
    val forced = ranked
      .filter { it.eventId !in resumeIds }
      .filter { it.eventId in runConfig.forceRegenerateEventIds }
    val primary = (pinnedFresh + highFresh + forced).distinctBy { it.eventId }.take(maxEvents)
    return (resume + primary).distinctBy { it.eventId }
  }

  private fun manifestCandidates(
    ranked: List<EventSelector.Candidate>,
    selectedIds: Set<String>,
    runConfig: VlogPilotRunConfig,
    manifestLimit: Int,
  ): List<EventSelector.Candidate> {
    val importantIds = selectedIds +
      runConfig.pinnedEventIds +
      runConfig.excludedEventIds +
      runConfig.forceRegenerateEventIds +
      runConfig.onlySelectedEventIds
    return (ranked.take(manifestLimit) + ranked.filter { it.eventId in importantIds })
      .distinctBy { it.eventId }
  }

  private fun EventSelector.Candidate.toSnapshot(
    context: Context,
    selectedIds: Set<String>,
    excludedIds: Set<String>,
  ): EventCandidateSnapshot {
    val status = when {
      eventId in excludedIds -> EventSelectionStatus.EXCLUDED
      eventId in selectedIds -> EventSelectionStatus.SELECTED
      isResumable(context, eventId) -> EventSelectionStatus.RESUME
      isCompleted(context, eventId) -> EventSelectionStatus.COMPLETED
      assets.isNotEmpty() -> EventSelectionStatus.FRESH
      else -> EventSelectionStatus.NOT_SELECTED
    }
    val stateReasons = buildList {
      if (status == EventSelectionStatus.EXCLUDED) add("excluded")
      if (status == EventSelectionStatus.SELECTED) add("selected")
      if (status == EventSelectionStatus.RESUME) add("resume")
      if (status == EventSelectionStatus.COMPLETED) add("completed")
    }
    return EventCandidateSnapshot(
      event = event,
      assets = assets.take(24),
      status = status,
      valueScore = valueScore,
      travelScore = travelScore,
      mediaScore = mediaScore,
      storyScore = storyScore,
      qualityScore = qualityScore,
      recencyScore = recencyScore,
      realVideoCount = realVideoCount,
      longVideoCount = longVideoCount,
      realVideoSeconds = realVideoSeconds,
      gpsAssetCount = gpsAssetCount,
      spanHours = spanHours,
      reasons = (stateReasons + reasons).distinct(),
      rankingMode = if (scout != null) "vlm_scout" else "metadata_only",
      scoutEventType = scout?.eventType.orEmpty(),
      scoutSummary = scout?.summary.orEmpty(),
      scoutStoryValue = scout?.storyValue ?: 0f,
      scoutVisualValue = scout?.visualValue ?: 0f,
      scoutSubjectValue = scout?.subjectValue ?: 0f,
      scoutRecommended = scout?.recommended == true,
      scoutBestAssetIds = scout?.bestAssetIds.orEmpty(),
      scoutRejectReasons = scout?.rejectReasons.orEmpty(),
      scoutPageCount = scout?.pageCount ?: 0,
      scoutSampled = scout?.sampled == true,
    )
  }

  private fun isResumable(context: Context, eventId: String): Boolean {
    val (timeline, mp4) = cachedFiles(context, eventId)
    return timeline.isFile && !mp4.isFile
  }

  private fun isCompleted(context: Context, eventId: String): Boolean {
    val (timeline, mp4) = cachedFiles(context, eventId)
    return timeline.isFile && mp4.isFile
  }

  private fun cachedFiles(context: Context, eventId: String): Pair<File, File> {
    val timeline = File(File(File(context.filesDir, "decisions"), eventId), "timeline_final.json")
    val mp4 = File(File(context.filesDir, "candidates"), "$eventId.mp4")
    return timeline to mp4
  }
}
