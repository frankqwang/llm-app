/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * step4_director: produces the DirectorBrief (title, tone, narrative arc, shot
 * blueprint). Heavily prompt-constrained — the actual quality bar comes from
 * the system prompt in PromptStrings.DIRECTOR_SYSTEM.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.AudienceBrief
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.DirectorBrief
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.EventMemory
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRequest
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DirectorAgent(private val agent: AgentRuntime) {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  suspend fun draft(memory: EventMemory, audience: AudienceBrief, assetCount: Int): DirectorBrief {
    val userMsg = """
EventMemory:
${memory.storylineSummary}
情绪曲线: ${memory.emotionalArc}
人物: ${memory.charactersObserved.joinToString("、")}

AudienceBrief:
- 情绪点: ${audience.emotionalPayoff}
- hook: ${audience.hookStrategy}
- pov: ${audience.povVoice}
- 节奏: ${audience.pacingGuidance}
- 避免: ${audience.avoidList.joinToString("、")}

总素材: $assetCount 张/段；目标 vlog 时长 18-22 秒。
请输出 DirectorBrief JSON。
""".trimIndent()
    val raw = agent.ask(systemPrompt = PromptStrings.DIRECTOR_SYSTEM, userText = userMsg)
    val obj = try { JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement)?.jsonObject } catch (_: Throwable) { null }
      ?: return fallback(memory)
    return parseBrief(memory.eventId, obj) ?: fallback(memory)
  }

  private fun parseBrief(eventId: String, obj: JsonObject): DirectorBrief? {
    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null
    val tagline = obj["tagline"]?.jsonPrimitive?.contentOrNull
    val targetDur = obj["target_duration_sec"]?.jsonPrimitive?.floatOrNull ?: 20f
    val tone = obj["tone"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val arc = obj["narrative_arc"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    val blueprint = obj["shot_blueprint"]?.jsonArray?.mapNotNull { el -> parseShot(el.jsonObject) } ?: emptyList()
    if (blueprint.isEmpty()) return null
    return DirectorBrief(
      eventId = eventId,
      title = title,
      tagline = tagline,
      targetDurationSec = targetDur,
      tone = tone,
      narrativeArc = arc,
      shotBlueprint = blueprint,
    )
  }

  private fun parseShot(obj: JsonObject): ShotRequest? {
    val pos = obj["position"]?.jsonPrimitive?.intOrNull ?: return null
    val roleStr = obj["role"]?.jsonPrimitive?.contentOrNull ?: "establishing"
    val role = ROLE_MAP[roleStr] ?: ShotRole.ESTABLISHING
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

  private fun fallback(memory: EventMemory) = DirectorBrief(
    eventId = memory.eventId,
    title = "片段",
    tagline = null,
    targetDurationSec = 20f,
    tone = "记录",
    narrativeArc = listOf("开场", "高潮", "收尾"),
    shotBlueprint = listOf(
      ShotRequest(1, ShotRole.OPENING, "好奇", "全景，主角入镜", 2.5f, null, "记录这一天", "in", "fade"),
      ShotRequest(2, ShotRole.PORTRAIT, "亲密", "中景，表情自然", 3f, null, "", "", "cut"),
      ShotRequest(3, ShotRole.ACTION, "动感", "动作中段", 2.5f, null, "", "out", "smoothleft"),
      ShotRequest(4, ShotRole.CLIMAX, "高光", "情绪峰值", 3f, null, "最难忘", "in", "zoomin"),
      ShotRequest(5, ShotRole.CLOSING, "余韵", "远景收尾", 2.5f, null, "下次见", "out", "fadewhite"),
    ),
  )

  companion object {
    private val ROLE_MAP = mapOf(
      "opening" to ShotRole.OPENING,
      "establishing" to ShotRole.ESTABLISHING,
      "portrait" to ShotRole.PORTRAIT,
      "action" to ShotRole.ACTION,
      "climax" to ShotRole.CLIMAX,
      "transition" to ShotRole.TRANSITION,
      "closing" to ShotRole.CLOSING,
    )
  }
}
