/*
 * Copyright 2026 The pc-pilot v3 authors
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

import android.content.Context
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.AgentRuntime
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.EventScoutAgent
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.EventScoutSheetBuilder
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.EventSelector
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.PowerProfile
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.VlogPilotRunConfig
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.EventScout

object EventScoutRunner {

  data class Progress(
    val eventIndex: Int,
    val eventCount: Int,
    val eventId: String,
    val pageIndex: Int,
    val pageCount: Int,
    val cacheHit: Boolean,
  )

  suspend fun scout(
    context: Context,
    agentRuntime: AgentRuntime,
    candidates: List<EventSelector.Candidate>,
    runConfig: VlogPilotRunConfig,
    limit: Int = scoutEventLimit(runConfig.powerProfile),
    onProgress: suspend (Progress) -> Unit = {},
  ): Map<String, EventScout> {
    val selected = candidates
      .filter { it.eventId !in runConfig.excludedEventIds }
      .take(limit)
    if (selected.isEmpty()) return emptyMap()
    val agent = EventScoutAgent(context, agentRuntime)
    val out = LinkedHashMap<String, EventScout>()
    for ((idx, candidate) in selected.withIndex()) {
      val signature = EventScoutSheetBuilder.signature(candidate.assets, runConfig.powerProfile)
      val cached = DecisionStore.loadEventScout(context, candidate.eventId, signature)
      if (cached != null) {
        out[candidate.eventId] = cached
        onProgress(Progress(idx + 1, selected.size, candidate.eventId, cached.pageCount, cached.pageCount, cacheHit = true))
        continue
      }
      val scout = agent.scout(candidate.eventId, candidate.assets, runConfig.powerProfile) { page ->
        onProgress(Progress(idx + 1, selected.size, candidate.eventId, page.pageIndex, page.pageCount, cacheHit = false))
      }
      DecisionStore.writeEventScout(context, scout)
      out[candidate.eventId] = scout
    }
    return out
  }

  private fun scoutEventLimit(profile: PowerProfile): Int =
    when (profile) {
      PowerProfile.LOW_POWER -> 6
      PowerProfile.BALANCED -> 8
      PowerProfile.HIGH_QUALITY -> 10
    }
}
