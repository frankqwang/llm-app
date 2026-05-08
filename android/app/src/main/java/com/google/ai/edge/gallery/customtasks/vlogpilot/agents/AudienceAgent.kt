/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * step3b_audience: writes the AudienceBrief that drives all downstream choices.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.AudienceBrief
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.EventMemory
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Pace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AudienceAgent(private val agent: AgentRuntime) {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  suspend fun write(memory: EventMemory): AudienceBrief {
    val userMsg = """
事件 ${memory.eventId} 的 EventMemory:
- storyline_summary: ${memory.storylineSummary}
- emotional_arc: ${memory.emotionalArc}
- characters: ${memory.charactersObserved.joinToString("、")}
- visual_style: ${memory.visualStyleSignals}

请输出 AudienceBrief JSON。
""".trimIndent()
    val raw = agent.ask(systemPrompt = PromptStrings.AUDIENCE_SYSTEM, userText = userMsg)
    val obj = try { JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement)?.jsonObject } catch (_: Throwable) { null }
      ?: return fallback(memory)
    val paceStr = obj["pace"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
    val pace = when (paceStr) {
      "snappy" -> Pace.SNAPPY
      "lingering" -> Pace.LINGERING
      "balanced" -> Pace.BALANCED
      else -> Pace.BALANCED
    }
    // Patch missing fields from fallback rather than letting empty strings propagate
    // into the Director prompt — Gemma 4 E2B occasionally returns partial JSON like
    // {"hook_strategy":"...","emotional_payoff":""} which would weaken downstream prompts.
    val fb = fallback(memory)
    return AudienceBrief(
      eventId = memory.eventId,
      emotionalPayoff = obj["emotional_payoff"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fb.emotionalPayoff,
      hookStrategy = obj["hook_strategy"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fb.hookStrategy,
      povVoice = obj["pov_voice"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fb.povVoice,
      pacingGuidance = obj["pacing_guidance"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fb.pacingGuidance,
      pace = pace,
      avoidList = obj["avoid_list"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.takeIf { it.isNotEmpty() } ?: fb.avoidList,
    )
  }

  private fun fallback(memory: EventMemory) = AudienceBrief(
    eventId = memory.eventId,
    emotionalPayoff = "记录这一刻",
    hookStrategy = "用最有信息量的画面开场",
    povVoice = "第一人称记录",
    pacingGuidance = "前快后慢，结尾留白",
    pace = Pace.BALANCED,
    avoidList = listOf("连拍重复"),
  )
}
