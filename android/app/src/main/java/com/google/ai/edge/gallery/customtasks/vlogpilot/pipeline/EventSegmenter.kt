/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * step2 segment, on-device port. Walks assets in time order and starts a new
 * event when (gap > GAP_HOURS) OR (event span > MAX_EVENT_SPAN_HOURS). After
 * splitting, runt events with fewer than MIN_ASSETS_PER_EVENT are merged into
 * the temporally adjacent event with smaller gap.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event

object EventSegmenter {

  // Same defaults as pc-pilot/step2_segment.py after the iteration in v2.
  private const val GAP_HOURS = 8.0
  private const val MAX_EVENT_SPAN_HOURS = 72.0
  private const val MIN_ASSETS_PER_EVENT = 3

  fun segment(assets: List<Asset>): List<Event> {
    if (assets.isEmpty()) return emptyList()
    val sorted = assets.sortedBy { it.takenEpochMs }
    val gapMs = (GAP_HOURS * 3_600_000L).toLong()
    val maxSpanMs = (MAX_EVENT_SPAN_HOURS * 3_600_000L).toLong()

    // Pass 1: gap + span based splitting
    val groups = mutableListOf<MutableList<Asset>>()
    var current = mutableListOf<Asset>()
    var spanStart = sorted[0].takenEpochMs
    for (a in sorted) {
      val prev = current.lastOrNull()
      val isNew = prev == null ||
        (a.takenEpochMs - prev.takenEpochMs) > gapMs ||
        (a.takenEpochMs - spanStart) > maxSpanMs
      if (isNew && current.isNotEmpty()) {
        groups += current
        current = mutableListOf()
        spanStart = a.takenEpochMs
      }
      if (current.isEmpty()) spanStart = a.takenEpochMs
      current += a
    }
    if (current.isNotEmpty()) groups += current

    // Pass 2: merge runt events into the closer neighbor
    var changed = true
    while (changed) {
      changed = false
      for (i in groups.indices) {
        if (groups[i].size >= MIN_ASSETS_PER_EVENT) continue
        val left = if (i > 0) groups[i - 1] else null
        val right = if (i < groups.size - 1) groups[i + 1] else null
        val canMergeLeft = left != null && spanMs(left + groups[i]) <= maxSpanMs
        val canMergeRight = right != null && spanMs(groups[i] + right) <= maxSpanMs
        when {
          left == null && right == null -> Unit // single tiny event, keep
          !canMergeLeft && !canMergeRight -> continue
          !canMergeLeft -> { right!!.addAll(0, groups[i]); groups[i].clear() }
          !canMergeRight -> { left!!.addAll(groups[i]); groups[i].clear() }
          else -> {
            val gapLeft = groups[i].first().takenEpochMs - left!!.last().takenEpochMs
            val gapRight = right!!.first().takenEpochMs - groups[i].last().takenEpochMs
            if (gapLeft <= gapRight) left.addAll(groups[i]) else right.addAll(0, groups[i])
            groups[i].clear()
          }
        }
        changed = true
        break
      }
      groups.removeAll { it.isEmpty() }
    }

    return groups.mapIndexed { idx, g ->
      Event(
        eventId = "evt_%03d".format(idx + 1),
        assetIds = g.map { it.id },
        startEpochMs = g.first().takenEpochMs,
        endEpochMs = g.last().takenEpochMs,
        placeHint = "", // filled later from GPS in step3 if any asset has lat/lon
      )
    }
  }

  private fun spanMs(group: List<Asset>): Long =
    (group.maxOfOrNull { it.takenEpochMs } ?: 0L) - (group.minOfOrNull { it.takenEpochMs } ?: 0L)
}
