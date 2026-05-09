/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * V2.1 added speedFactor / kenBurnsZoom / cutReason to ShotSpec (and the
 * matching speedHint / kenBurnsIntensity / cutReason to ShotRequest). Cache
 * compatibility (landmine #7) requires that decoding an old JSON without
 * these fields still works — these tests pin the defaults and confirm
 * round-tripping.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.schemas

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ShotFieldDefaultsTest {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  @Test fun `ShotSpec deserializes legacy JSON without new fields`() {
    val legacy = """
      {"order":1,"assetId":"a1","mediaType":"image","durationSec":3.0,
       "kenBurns":"in","colorGrade":"warm","caption":"hello",
       "transitionIn":"fade","videoTrim":null,"rationale":""}
    """.trimIndent()
    val spec = json.decodeFromString(ShotSpec.serializer(), legacy)
    assertEquals(1.0f, spec.speedFactor, 0.0001f)
    assertEquals(1.08f, spec.kenBurnsZoom, 0.0001f)
    assertEquals("", spec.cutReason)
  }

  @Test fun `ShotRequest deserializes legacy JSON without new fields`() {
    val legacy = """
      {"position":1,"role":"opening","moodTarget":"x","visualRequirements":"y",
       "durationSec":2.5,"captionText":"","kenBurnsHint":"in",
       "transitionInHint":"fade"}
    """.trimIndent()
    val req = json.decodeFromString(ShotRequest.serializer(), legacy)
    assertEquals(1.0f, req.speedHint, 0.0001f)
    assertEquals(1.08f, req.kenBurnsIntensity, 0.0001f)
    assertEquals("", req.cutReason)
  }

  @Test fun `ShotSpec round-trips with new fields`() {
    val original = ShotSpec(
      order = 4,
      assetId = "id",
      mediaType = MediaType.VIDEO,
      durationSec = 3.5f,
      kenBurns = "pan_left",
      caption = "x",
      transitionIn = TransitionKind.ZOOMIN,
      speedFactor = 0.75f,
      kenBurnsZoom = 1.16f,
      cutReason = "高潮要慢下来",
    )
    val encoded = json.encodeToString(ShotSpec.serializer(), original)
    val decoded = json.decodeFromString(ShotSpec.serializer(), encoded)
    assertEquals(original, decoded)
  }
}
