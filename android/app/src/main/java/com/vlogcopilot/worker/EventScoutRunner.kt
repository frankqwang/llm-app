/*
 * Copyright 2026 The pc-pilot v3 authors
 */
package com.vlogcopilot.worker

import android.content.Context
import com.vlogcopilot.agents.AgentRuntime
import com.vlogcopilot.agents.EventScoutAgent
import com.vlogcopilot.pipeline.EventScoutSheetBuilder
import com.vlogcopilot.pipeline.EventSelector
import com.vlogcopilot.runtime.PowerProfile
import com.vlogcopilot.runtime.VlogCopilotRunConfig
import com.vlogcopilot.schemas.EventScout

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
    runConfig: VlogCopilotRunConfig,
    limit: Int = scoutEventLimit(runConfig.powerProfile),
    onProgress: suspend (Progress) -> Unit = {},
  ): Map<String, EventScout> {
    val selected = selectScoutCandidates(candidates, runConfig, limit)
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

  internal fun selectScoutCandidates(
    candidates: List<EventSelector.Candidate>,
    runConfig: VlogCopilotRunConfig,
    limit: Int = scoutEventLimit(runConfig.powerProfile),
  ): List<EventSelector.Candidate> {
    val selectable = candidates.filter { it.eventId !in runConfig.excludedEventIds }
    if (selectable.isEmpty() || limit <= 0) return emptyList()

    val selected = LinkedHashMap<String, EventSelector.Candidate>()
    fun add(candidate: EventSelector.Candidate) {
      selected.putIfAbsent(candidate.eventId, candidate)
    }

    val byId = selectable.associateBy { it.eventId }
    val mandatoryIds = runConfig.onlySelectedEventIds +
      runConfig.pinnedEventIds +
      runConfig.forceRegenerateEventIds
    mandatoryIds.forEach { id -> byId[id]?.let(::add) }

    val target = maxOf(limit, selected.size)
    val timeSpread = selectSpread(selectable.sortedBy { it.event.startEpochMs }, target)
    val pools = listOf(
      selectable,
      selectable.sortedWith(
        compareByDescending<EventSelector.Candidate> { it.longVideoCount }
          .thenByDescending { it.realVideoSeconds }
          .thenByDescending { it.mediaScore },
      ),
      selectable.sortedWith(
        compareByDescending<EventSelector.Candidate> { it.gpsAssetCount }
          .thenByDescending { it.travelScore }
          .thenByDescending { it.spanHours },
      ),
      selectable.sortedWith(
        compareByDescending<EventSelector.Candidate> { it.storyScore }
          .thenByDescending { it.assets.size }
          .thenByDescending { it.spanHours },
      ),
      selectable.sortedByDescending { it.event.endEpochMs },
      timeSpread,
    )

    var index = 0
    while (selected.size < target) {
      var added = false
      for (pool in pools) {
        if (index < pool.size) {
          val before = selected.size
          add(pool[index])
          added = added || selected.size > before
          if (selected.size >= target) break
        }
      }
      if (!added && pools.all { index >= it.size }) break
      index += 1
    }
    return selected.values.toList()
  }

  private fun scoutEventLimit(profile: PowerProfile): Int =
    when (profile) {
      PowerProfile.LOW_POWER -> 8
      PowerProfile.BALANCED -> 10
      PowerProfile.HIGH_QUALITY -> 12
    }

  private fun <T> selectSpread(values: List<T>, limit: Int): List<T> {
    if (values.size <= limit) return values
    if (limit <= 0) return emptyList()
    if (limit == 1) return listOf(values[values.size / 2])
    val indices = LinkedHashSet<Int>()
    for (i in 0 until limit) {
      val idx = (i * values.lastIndex.toFloat() / (limit - 1)).toInt().coerceIn(0, values.lastIndex)
      indices += idx
    }
    var cursor = 0
    while (indices.size < limit && cursor < values.size) {
      indices += cursor
      cursor += 1
    }
    return indices.sorted().map { values[it] }
  }
}
