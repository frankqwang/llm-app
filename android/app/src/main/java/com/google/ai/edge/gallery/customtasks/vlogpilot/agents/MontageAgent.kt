/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * step3_montage on-device. Build contact sheet → ask Gemma 4 with image input
 * → parse EventMemory JSON → resolve image_index back to assetId.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import android.content.Context
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.MontageBuilder
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.EventMemory
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.KeyMoment
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.SubGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MontageAgent(private val context: Context, private val agent: AgentRuntime) {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  suspend fun browse(eventId: String, assets: List<Asset>): EventMemory {
    val sheets = MontageBuilder.build(context, assets)
    if (sheets.isEmpty()) return empty(eventId)

    // One VLM call per sheet — image indices in the agent's reply are local to
    // that sheet (1..sheet.size), and we resolve them against sheet.assetIds.
    // Then merge per-sheet EventMemories so large events keep all their context.
    val partials = mutableListOf<EventMemory>()
    for ((i, sheet) in sheets.withIndex()) {
      try {
        val raw = agent.ask(
          systemPrompt = PromptStrings.MONTAGE_SYSTEM,
          userText = "事件 $eventId 第 ${i + 1}/${sheets.size} 页，本页 ${sheet.assetIds.size} 张。" +
            "image_index 为本页内 1..${sheet.assetIds.size} 编号。请输出 EventMemory JSON。",
          images = listOf(sheet.bitmap),
        )
        val obj = try { JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement)?.jsonObject } catch (_: Throwable) { null } ?: continue
        partials += parseEventMemory(eventId, obj, sheet.assetIds)
      } finally {
        runCatching { sheet.bitmap.recycle() }
      }
    }
    return if (partials.isEmpty()) empty(eventId) else merge(eventId, partials)
  }

  private fun merge(eventId: String, parts: List<EventMemory>): EventMemory {
    val summary = parts.map { it.storylineSummary }.filter { it.isNotEmpty() }.joinToString(" ")
    val arc = parts.firstOrNull { it.emotionalArc.isNotEmpty() }?.emotionalArc.orEmpty()
    val style = parts.firstOrNull { it.visualStyleSignals.isNotEmpty() }?.visualStyleSignals.orEmpty()
    val chars = parts.flatMap { it.charactersObserved }.distinct()
    val moments = parts.flatMap { it.keyMoments }.distinctBy { it.assetId }
    val groups = parts.flatMap { it.notableSubgroups }
    return EventMemory(eventId, summary, moments, arc, chars, style, groups)
  }

  private fun empty(eventId: String) = EventMemory(
    eventId = eventId,
    storylineSummary = "(VLM unavailable — placeholder)",
  )

  private fun parseEventMemory(eventId: String, obj: JsonObject, assetIds: List<String>): EventMemory {
    val summary = obj["storyline_summary"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val arc = obj["emotional_arc"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val style = obj["visual_style_signals"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val chars = obj["characters_observed"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    val moments = obj["key_moments"]?.jsonArray?.mapNotNull { el ->
      val m = el.jsonObject
      val idx = m["image_index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
      val why = m["why"]?.jsonPrimitive?.contentOrNull.orEmpty()
      val assetId = assetIds.getOrNull(idx - 1) ?: return@mapNotNull null
      KeyMoment(imageIndex = idx, assetId = assetId, why = why)
    } ?: emptyList()
    val groups = obj["notable_subgroups"]?.jsonArray?.mapNotNull { el ->
      val g = el.jsonObject
      val indices = g["indices"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull } ?: return@mapNotNull null
      val label = g["label"]?.jsonPrimitive?.contentOrNull.orEmpty()
      SubGroup(indices = indices, label = label)
    } ?: emptyList()
    return EventMemory(eventId, summary, moments, arc, chars, style, groups)
  }
}
