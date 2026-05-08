/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * VLM-first event scout. It reads paged 3x3 contact sheets and produces a
 * compact event-level semantic memory for ranking before expensive generation.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import android.content.Context
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.EventScoutSheetBuilder
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.PowerProfile
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.EventScout
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ScoutMoment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EventScoutAgent(
  private val context: Context,
  private val agent: AgentRuntime,
) {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  data class PageProgress(
    val pageIndex: Int,
    val pageCount: Int,
    val eventId: String,
  )

  suspend fun scout(
    eventId: String,
    assets: List<Asset>,
    powerProfile: PowerProfile,
    onPage: suspend (PageProgress) -> Unit = {},
  ): EventScout {
    val signature = EventScoutSheetBuilder.signature(assets, powerProfile)
    val sheets = EventScoutSheetBuilder.build(context, assets, powerProfile)
    if (sheets.isEmpty()) {
      return EventScout(
        eventId = eventId,
        scoutVersion = EventScoutSheetBuilder.SCOUT_VERSION,
        assetSignature = signature,
        generatedAtMs = System.currentTimeMillis(),
        eventType = "unknown",
        rejectReasons = listOf("empty"),
      )
    }

    val pages = mutableListOf<PageScout>()
    for (sheet in sheets) {
      try {
        onPage(PageProgress(sheet.pageIndex, sheet.pageCount, eventId))
        val cellList = sheet.units.withIndex().joinToString("\n") { (idx, unit) ->
          val frame = unit.frameSec?.let { " frame=${"%.1f".format(it)}s" }.orEmpty()
          "${idx + 1}. ${unit.asset.mediaType.name.lowercase()}$frame name=${unit.asset.displayName}"
        }
        val raw = agent.ask(
          systemPrompt = SCOUT_SYSTEM_PROMPT,
          userText = """
Event id: $eventId
Page: ${sheet.pageIndex}/${sheet.pageCount}
Cells are numbered 1..${sheet.units.size}. Each cell is one image or one sampled video frame.
Total assets in event: ${sheet.totalAssetCount}
Visual units before sampling: ${sheet.totalVisualUnitCount}
This event was sampled: ${sheet.sampled}

Cell metadata:
$cellList

Return EventPageScout JSON only.
""".trimIndent(),
          images = listOf(sheet.bitmap),
          label = "event_scout",
        )
        parsePage(raw, sheet)?.let { pages += it }
      } finally {
        runCatching { sheet.bitmap.recycle() }
      }
    }
    return merge(eventId, signature, assets.size, sheets.first(), pages)
  }

  private fun parsePage(raw: String, sheet: EventScoutSheetBuilder.Sheet): PageScout? {
    val obj = try {
      JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement)?.jsonObject
    } catch (_: Throwable) {
      null
    } ?: return null

    val moments = obj["key_cells"]?.jsonArray?.mapNotNull { el ->
      val m = el.jsonObject
      val idx = m["cell_index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
      val asset = sheet.units.getOrNull(idx - 1)?.asset ?: return@mapNotNull null
      ScoutMoment(
        assetId = asset.id,
        pageIndex = sheet.pageIndex,
        cellIndex = idx,
        why = m["why"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      )
    }.orEmpty()

    return PageScout(
      eventType = obj["event_type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      summary = obj["summary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      storyValue = score(obj, "story_value"),
      visualValue = score(obj, "visual_value"),
      subjectValue = score(obj, "subject_value"),
      travelScore = score(obj, "travel_score"),
      zooScore = score(obj, "zoo_score"),
      peopleScore = score(obj, "people_score"),
      foodScore = score(obj, "food_score"),
      recommended = obj["recommended"]?.jsonPrimitive?.booleanOrNull ?: false,
      keyMoments = moments,
      rejectReasons = obj["reject_reasons"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
    )
  }

  private fun score(obj: JsonObject, key: String): Float =
    (obj[key]?.jsonPrimitive?.floatOrNull ?: 0f).coerceIn(0f, 1f)

  private fun merge(
    eventId: String,
    signature: String,
    totalAssetCount: Int,
    firstSheet: EventScoutSheetBuilder.Sheet,
    pages: List<PageScout>,
  ): EventScout {
    if (pages.isEmpty()) {
      return EventScout(
        eventId = eventId,
        scoutVersion = EventScoutSheetBuilder.SCOUT_VERSION,
        assetSignature = signature,
        generatedAtMs = System.currentTimeMillis(),
        eventType = "unknown",
        totalAssetCount = totalAssetCount,
        pageCount = firstSheet.pageCount,
        visualUnitCount = firstSheet.totalVisualUnitCount,
        sampled = firstSheet.sampled,
        rejectReasons = listOf("vlm-empty"),
      )
    }
    val typeScores = mapOf(
      "travel" to pages.maxOf { it.travelScore },
      "zoo" to pages.maxOf { it.zooScore },
      "people" to pages.maxOf { it.peopleScore },
      "food" to pages.maxOf { it.foodScore },
    )
    val bestType = typeScores.maxByOrNull { it.value }?.takeIf { it.value >= 0.35f }?.key
    val explicitType = pages.map { it.eventType.lowercase() }
      .firstOrNull { it in setOf("travel", "zoo", "people", "food", "daily", "junk") }
    return EventScout(
      eventId = eventId,
      scoutVersion = EventScoutSheetBuilder.SCOUT_VERSION,
      assetSignature = signature,
      generatedAtMs = System.currentTimeMillis(),
      eventType = bestType ?: explicitType ?: "unknown",
      summary = pages.map { it.summary }.filter { it.isNotBlank() }.joinToString(" ").take(500),
      storyValue = pages.map { it.storyValue }.averageScore(),
      visualValue = pages.map { it.visualValue }.averageScore(),
      subjectValue = pages.map { it.subjectValue }.averageScore(),
      travelScore = pages.maxOf { it.travelScore },
      zooScore = pages.maxOf { it.zooScore },
      peopleScore = pages.maxOf { it.peopleScore },
      foodScore = pages.maxOf { it.foodScore },
      recommended = pages.any { it.recommended } && pages.none { it.eventType.equals("junk", ignoreCase = true) },
      bestMoments = pages.flatMap { it.keyMoments }.distinctBy { it.assetId }.take(12),
      rejectReasons = pages.flatMap { it.rejectReasons }.filter { it.isNotBlank() }.distinct().take(8),
      pageSummaries = pages.map { it.summary }.filter { it.isNotBlank() },
      pageCount = firstSheet.pageCount,
      visualUnitCount = firstSheet.totalVisualUnitCount,
      totalAssetCount = totalAssetCount,
      sampled = firstSheet.sampled,
    )
  }

  private fun List<Float>.averageScore(): Float =
    if (isEmpty()) 0f else average().toFloat().coerceIn(0f, 1f)

  private data class PageScout(
    val eventType: String,
    val summary: String,
    val storyValue: Float,
    val visualValue: Float,
    val subjectValue: Float,
    val travelScore: Float,
    val zooScore: Float,
    val peopleScore: Float,
    val foodScore: Float,
    val recommended: Boolean,
    val keyMoments: List<ScoutMoment>,
    val rejectReasons: List<String>,
  )

  companion object {
    val SCOUT_SYSTEM_PROMPT = """
You are a senior vlog editor doing a fast event-level scout before generation.
You will see one 3x3 contact sheet page from a larger event. Each cell has a
number in the corner. A video may appear as several sampled frames across pages.

Judge the page as visual material for a short personal vlog. Prefer concrete
visual evidence over metadata. Look for travel, zoo/animals, people interaction,
food, strong moments, story progression, and obvious junk/repetition.

Return strict JSON only:
{
  "event_type": "travel|zoo|people|food|daily|junk|unknown",
  "summary": "<=80 words describing what is visibly happening on this page",
  "story_value": 0.0,
  "visual_value": 0.0,
  "subject_value": 0.0,
  "travel_score": 0.0,
  "zoo_score": 0.0,
  "people_score": 0.0,
  "food_score": 0.0,
  "recommended": true,
  "key_cells": [{"cell_index": 1, "why": "<=18 words why this cell matters"}],
  "reject_reasons": ["duplicate", "low-signal", "blurred", "screenshots"]
}

Rules:
1. Scores are 0..1. Use high scores only when the visual evidence is clear.
2. key_cells must reference existing cell numbers only.
3. If this page is mostly repetitive or low-value, set recommended=false and explain why.
4. Do not include markdown or prose outside JSON.
""".trimIndent()
  }
}
