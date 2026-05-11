/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * step6_critic. Reviews a rough timeline with both text context and a visual
 * storyboard, then emits revised shot requests that the orchestrator can replay
 * through the editor. We cap loops in PipelineOrchestrator.
 */
package com.vlogcopilot.agents

import android.content.Context
import com.vlogcopilot.pipeline.TimelineStoryboardBuilder
import com.vlogcopilot.schemas.Asset
import com.vlogcopilot.schemas.Critique
import com.vlogcopilot.schemas.CriticVerdict
import com.vlogcopilot.schemas.DirectorBrief
import com.vlogcopilot.schemas.EventMemory
import com.vlogcopilot.schemas.RevisedRequest
import com.vlogcopilot.schemas.ShotRequest
import com.vlogcopilot.schemas.ShotRole
import com.vlogcopilot.schemas.Timeline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CriticAgent(
  private val context: Context,
  private val agent: AgentRuntime,
) {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  suspend fun review(
    iteration: Int,
    timeline: Timeline,
    director: DirectorBrief,
    memory: EventMemory,
    assets: Map<String, Asset>,
  ): Critique {
    if (timeline.shots.isEmpty()) {
      return Critique(
        iteration = iteration,
        issues = listOf("timeline is empty"),
        verdict = CriticVerdict.ABORT,
      )
    }

    val storyboard = TimelineStoryboardBuilder.build(context, timeline, assets)
    val cheapChecks = if (looksGoodEnough(timeline, director)) "pass" else "needs_attention"
    val timelineSummary = timeline.shots.joinToString("\n") { s ->
      val trim = s.videoTrim?.let { " trim=${"%.1f".format(it.startSec)}-${"%.1f".format(it.endSec)}s" }.orEmpty()
      "  #${s.order} [${s.mediaType.name.lowercase()}] dur=${"%.1f".format(s.durationSec)}s$trim caption=\"${s.caption}\" - ${s.rationale.take(60)}"
    }
    val userMsg = """
DirectorBrief.title=${director.title}; tone=${director.tone}; target_duration=${director.targetDurationSec}
narrative_arc: ${director.narrativeArc.joinToString(" -> ")}
cheap_checks: $cheapChecks

EventMemory.storyline: ${memory.storylineSummary.take(160)}

Timeline v$iteration shots:
$timelineSummary

The attached storyboard shows the selected shots in order; the badge number matches #order.
Review both the text timeline and the storyboard. Focus on visual repetition, weak opening hook,
weak climax, video trims that miss the action, pacing monotony, and whether the ending resolves.
Return Critique JSON only.
""".trimIndent()

    val raw = try {
      agent.ask(
        systemPrompt = PromptStrings.CRITIC_SYSTEM,
        userText = userMsg,
        images = listOfNotNull(storyboard),
        label = "critic_visual",
      )
    } finally {
      storyboard?.recycle()
    }

    val obj = try {
      JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement)?.jsonObject
    } catch (_: Throwable) {
      null
    } ?: return Critique(iteration = iteration, issues = emptyList())
    val issues = obj["issues"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    // Accept either patch-mode `patches: {field: value}` or legacy `new_request: {...full ShotRequest...}`.
    // Patch mode is much cheaper for the LLM and correspondingly more reliable on Gemma 4 E2B.
    val revs = obj["revised_requests"]?.jsonArray?.mapNotNull { el ->
      val r = el.jsonObject
      val ord = r["shot_order"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
      val patchesObj = r["patches"]?.jsonObject
      val patches = patchesObj?.entries?.associate { (k, v) -> k to (v.jsonPrimitive.contentOrNull ?: "") }
        ?.filterValues { it.isNotBlank() }
        ?: emptyMap()
      val newReq = r["new_request"]?.jsonObject?.let { parseShot(it) }
      if (patches.isEmpty() && newReq == null) return@mapNotNull null
      RevisedRequest(shotOrder = ord, newRequest = newReq, patches = patches)
    } ?: emptyList()
    val verdictStr = obj["verdict"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
    val verdict = when (verdictStr) {
      "accept" -> CriticVerdict.ACCEPT
      "revise" -> CriticVerdict.REVISE
      "abort" -> CriticVerdict.ABORT
      else -> if (revs.isEmpty()) CriticVerdict.ACCEPT else CriticVerdict.REVISE
    }
    return Critique(
      iteration = iteration,
      issues = issues,
      verdict = verdict,
      revisedRequests = revs.take(MAX_REVISIONS),
    )
  }

  companion object {
    private const val MAX_REVISIONS = 5
  }

  private fun looksGoodEnough(timeline: Timeline, director: DirectorBrief): Boolean {
    if (timeline.shots.size < minOf(4, director.shotBlueprint.size)) return false
    val requiredOrders = director.shotBlueprint
      .filter { it.role == ShotRole.OPENING || it.role == ShotRole.CLIMAX || it.role == ShotRole.CLOSING }
      .map { it.position }
      .toSet()
    val filledOrders = timeline.shots.map { it.order }.toSet()
    if (!filledOrders.containsAll(requiredOrders)) return false
    val captions = timeline.shots.count { it.caption.isNotBlank() }
    if (captions < 3) return false
    val total = timeline.shots.sumOf { it.durationSec.toDouble() }.toFloat()
    val ratio = total / director.targetDurationSec
    if (ratio < 0.65f || ratio > 1.25f) return false
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
      speedHint = (obj["speed_hint"]?.jsonPrimitive?.floatOrNull ?: 1.0f).coerceIn(0.5f, 1.75f),
      kenBurnsIntensity = (obj["ken_burns_intensity"]?.jsonPrimitive?.floatOrNull ?: 1.08f).coerceIn(1.0f, 1.20f),
      cutReason = obj["cut_reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
  }
}
