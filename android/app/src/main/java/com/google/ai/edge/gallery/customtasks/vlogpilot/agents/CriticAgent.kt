/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * step6_critic. Reads a v1 timeline + DirectorBrief + EventMemory; emits a
 * Critique whose `revisedRequests` the orchestrator can replay through the
 * editor to swap individual shots. We cap to 2 critic loops so a divergent
 * model doesn't infinitely re-cut.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Critique
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.DirectorBrief
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.EventMemory
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.RevisedRequest
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRequest
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRole
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CriticAgent(private val agent: AgentRuntime) {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  suspend fun review(
    iteration: Int,
    timeline: Timeline,
    director: DirectorBrief,
    memory: EventMemory,
  ): Critique {
    if (looksGoodEnough(timeline, director)) {
      return Critique(iteration, listOf("v1 already meets the bar — skipping critic"), emptyList())
    }
    val timelineSummary = timeline.shots.joinToString("\n") { s ->
      "  #${s.order} [${s.mediaType.name.lowercase()}] dur=${"%.1f".format(s.durationSec)}s caption=\"${s.caption}\" — ${s.rationale.take(40)}"
    }
    val userMsg = """
DirectorBrief.title=${director.title}; tone=${director.tone}; target_duration=${director.targetDurationSec}
narrative_arc: ${director.narrativeArc.joinToString(" → ")}

EventMemory.storyline: ${memory.storylineSummary.take(120)}

Timeline v$iteration shots:
$timelineSummary

请审片，输出 Critique JSON。
""".trimIndent()
    val raw = agent.ask(systemPrompt = PromptStrings.CRITIC_SYSTEM, userText = userMsg)
    // Gemma 4 E2B will occasionally emit malformed JSON for the critic schema
    // (missing close-quote on a key, etc.). Treat any parse failure as "no
    // critique available" so the event still ships v1 of the timeline.
    val obj = try {
      JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement)?.jsonObject
    } catch (_: Throwable) {
      null
    } ?: return Critique(iteration, emptyList(), emptyList())
    val issues = obj["issues"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    val revs = obj["revised_requests"]?.jsonArray?.mapNotNull { el ->
      val r = el.jsonObject
      val ord = r["shot_order"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
      val newReq = parseShot(r["new_request"]?.jsonObject ?: return@mapNotNull null) ?: return@mapNotNull null
      RevisedRequest(shotOrder = ord, newRequest = newReq)
    } ?: emptyList()
    return Critique(iteration, issues, revs.take(3))
  }

  private fun looksGoodEnough(timeline: Timeline, director: DirectorBrief): Boolean {
    val captions = timeline.shots.count { it.caption.isNotBlank() }
    if (captions < 3) return false
    val total = timeline.shots.sumOf { it.durationSec.toDouble() }.toFloat()
    val ratio = total / director.targetDurationSec
    if (ratio < 0.65f || ratio > 1.25f) return false
    // No two adjacent shots from the same asset
    for (i in 1 until timeline.shots.size) {
      if (timeline.shots[i].assetId == timeline.shots[i - 1].assetId) return false
    }
    return true
  }

  private fun parseShot(obj: JsonObject): ShotRequest? {
    val pos = obj["position"]?.jsonPrimitive?.intOrNull ?: return null
    val roleStr = obj["role"]?.jsonPrimitive?.contentOrNull ?: "establishing"
    val role = runCatching { ShotRole.valueOf(roleStr.uppercase()) }.getOrDefault(ShotRole.ESTABLISHING)
    return ShotRequest(
      position = pos,
      role = role,
      moodTarget = obj["mood_target"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      visualRequirements = obj["visual_requirements"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      durationSec = (obj["duration_sec"]?.jsonPrimitive?.floatOrNull ?: 2.5f).coerceIn(0.4f, 8f),
      personConstraint = obj["person_constraint"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" },
      captionText = obj["caption_text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      kenBurnsHint = obj["ken_burns_hint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      transitionInHint = obj["transition_in_hint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
  }
}
