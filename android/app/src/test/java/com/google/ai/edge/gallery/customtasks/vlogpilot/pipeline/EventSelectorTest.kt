/*
 * Copyright 2026 The pc-pilot v3 authors
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline

import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.GenerationIntent
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.EventScout
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSelectorTest {

  @Test fun zooIntentBoostsAnimalLongVideoEvent() {
    val now = 1_700_000_000_000L
    val zoo = asset(
      id = "zoo-video",
      displayName = "zoo panda wildlife long clip",
      durationMs = 90_000L,
      takenEpochMs = now - 3_600_000L,
    )
    val ordinary = asset(
      id = "ordinary-video",
      displayName = "indoor hallway random clip",
      durationMs = 90_000L,
      takenEpochMs = now - 3_600_000L,
    )
    val ranked = EventSelector.rank(
      events = listOf(event("ordinary", ordinary), event("zoo", zoo)),
      assetMap = listOf(zoo, ordinary).associateBy { it.id },
      perceptionFor = { null },
      intent = GenerationIntent.ZOO,
      nowMs = now,
    )

    assertEquals("zoo", ranked.first().eventId)
    assertTrue(ranked.first().reasons.contains("zoo"))
    assertTrue(ranked.first().valueScore > ranked.last().valueScore)
  }

  @Test fun travelIntentAddsGpsAndTravelReasons() {
    val now = 1_700_000_000_000L
    val travel = asset(
      id = "travel-video",
      displayName = "outdoor travel landmark city walk",
      durationMs = 45_000L,
      takenEpochMs = now - 2_000_000L,
      latitude = 31.2304,
      longitude = 121.4737,
    )
    val candidate = EventSelector.rank(
      events = listOf(event("travel", travel)),
      assetMap = mapOf(travel.id to travel),
      perceptionFor = { null },
      intent = GenerationIntent.TRAVEL,
      nowMs = now,
    ).first()

    assertTrue(candidate.reasons.contains("gps"))
    assertTrue(candidate.reasons.contains("intent-travel") || candidate.reasons.contains("travel"))
  }

  @Test fun scoutSignalCanDriveRankingBeforePerAssetTagsExist() {
    val now = 1_700_000_000_000L
    val weak = asset("weak", "random clip", 20_000L, now - 1_000_000L)
    val zoo = asset("zoo", "clip", 20_000L, now - 1_000_000L)
    val ranked = EventSelector.rank(
      events = listOf(event("weak-event", weak), event("zoo-event", zoo)),
      assetMap = listOf(weak, zoo).associateBy { it.id },
      perceptionFor = { null },
      scoutFor = { id ->
        if (id == "zoo-event") {
          EventScout(
            eventId = id,
            scoutVersion = 1,
            assetSignature = "test",
            generatedAtMs = now,
            eventType = "zoo",
            summary = "animals in a zoo",
            storyValue = 0.8f,
            visualValue = 0.8f,
            subjectValue = 0.9f,
            zooScore = 0.95f,
            recommended = true,
          )
        } else {
          null
        }
      },
      intent = GenerationIntent.ZOO,
      nowMs = now,
    )

    assertEquals("zoo-event", ranked.first().eventId)
    assertTrue(ranked.first().reasons.contains("vlm-scout"))
  }

  private fun event(id: String, asset: Asset): Event =
    Event(
      eventId = id,
      assetIds = listOf(asset.id),
      startEpochMs = asset.takenEpochMs,
      endEpochMs = asset.takenEpochMs + asset.durationMs,
    )

  private fun asset(
    id: String,
    displayName: String,
    durationMs: Long,
    takenEpochMs: Long,
    latitude: Double? = null,
    longitude: Double? = null,
  ): Asset =
    Asset(
      id = id,
      contentUri = "content://test/$id",
      displayName = displayName,
      mediaType = MediaType.VIDEO,
      takenEpochMs = takenEpochMs,
      durationMs = durationMs,
      widthPx = 1920,
      heightPx = 1080,
      latitude = latitude,
      longitude = longitude,
    )
}
