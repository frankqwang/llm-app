/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * step4_director: produces the DirectorBrief (title, tone, narrative arc, shot
 * blueprint). Heavily prompt-constrained — the actual quality bar comes from
 * the system prompt in PromptStrings.DIRECTOR_SYSTEM.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import com.google.ai.edge.gallery.customtasks.vlogpilot.RhythmPlanner
import com.google.ai.edge.gallery.customtasks.vlogpilot.VlogTemplate
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.AudienceBrief
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ColorGrade
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.DirectorBrief
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.EventMemory
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Pace
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

  internal suspend fun draft(
    memory: EventMemory,
    audience: AudienceBrief,
    assetCount: Int,
    availableSignals: String = "",
    template: VlogTemplate? = null,
  ): DirectorBrief {
    val paceLabel = when (audience.pace) {
      Pace.SNAPPY -> "snappy（15-18 秒，快剪）"
      Pace.BALANCED -> "balanced（18-22 秒）"
      Pace.LINGERING -> "lingering（22-28 秒，慢且留白）"
    }
    val userMsg = buildString {
      appendLine("EventMemory:")
      appendLine(memory.storylineSummary.take(280))
      appendLine("标题硬性要求：必须是本事件专属标题，禁止输出“片段 / 短片 / 视频 / vlog / 生活片段”这类泛标题；标题要包含具体主体、场景或情绪。")
      appendLine("情绪曲线: ${memory.emotionalArc}")
      appendLine("人物: ${memory.charactersObserved.joinToString("、")}")
      memory.notableSubgroups.takeIf { it.isNotEmpty() }?.let {
        appendLine("人物/场景子集: ${it.joinToString("; ") { sg -> "${sg.label}=${sg.indices}" }}")
      }
      appendLine()
      appendLine("AudienceBrief:")
      appendLine("- 情绪点: ${audience.emotionalPayoff}")
      appendLine("- hook: ${audience.hookStrategy}")
      appendLine("- pov: ${audience.povVoice}")
      appendLine("- 节奏文本: ${audience.pacingGuidance}")
      appendLine("- pace: $paceLabel")
      appendLine("- 避免: ${audience.avoidList.joinToString("、")}")
      appendLine()
      if (availableSignals.isNotBlank()) {
        appendLine("available_signals（本事件 perception+VLM 实际可用素材；visual_requirements 必须在这里能找到对应）:")
        appendLine(availableSignals)
        appendLine()
      }
      if (template != null) {
        appendLine("剪辑模板：${template.label}。Director 只能在模板 slot 内填充具体视觉要求和文案，不能自由增删 slot。")
        appendLine("模板槽位：")
        template.slots.forEach { slot ->
          appendLine(
            "- #${slot.position} role=${slot.role.name.lowercase()} dur=${slot.durationSec}s mood=${slot.moodTarget} visual=${slot.visualRequirements}",
          )
        }
        appendLine("输出 JSON 的 shot_blueprint 必须覆盖这些 position；duration 可以微调，但最终会被 RhythmPlanner 约束。")
        appendLine()
      }
      appendLine("总素材: $assetCount 张/段。请输出 DirectorBrief JSON。")
    }
    val raw = agent.ask(systemPrompt = PromptStrings.DIRECTOR_SYSTEM, userText = userMsg, label = "director")
    val obj = try { JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement)?.jsonObject } catch (_: Throwable) { null }
      ?: return finalizeBrief(fallback(memory), template, audience.pace)
    return finalizeBrief(parseBrief(memory, obj, audience.pace) ?: fallback(memory), template, audience.pace)
  }

  private fun finalizeBrief(brief: DirectorBrief, template: VlogTemplate?, pace: Pace): DirectorBrief =
    template?.let { RhythmPlanner.enforce(brief, it, pace) } ?: brief

  private fun parseBrief(memory: EventMemory, obj: JsonObject, pace: Pace): DirectorBrief? {
    val title = sanitizeTitle(obj["title"]?.jsonPrimitive?.contentOrNull)
      ?: titleFromMemory(memory)
    val tagline = obj["tagline"]?.jsonPrimitive?.contentOrNull
    val rawDur = obj["target_duration_sec"]?.jsonPrimitive?.floatOrNull ?: paceDefault(pace)
    val targetDur = clampToPaceBand(rawDur, pace)
    val tone = obj["tone"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val gradeStr = obj["color_grade"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
    val colorGrade = COLOR_GRADE_MAP[gradeStr] ?: ColorGrade.NEUTRAL
    val arc = obj["narrative_arc"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    val blueprint = obj["shot_blueprint"]?.jsonArray?.mapNotNull { el -> parseShot(el.jsonObject) } ?: emptyList()
    if (blueprint.isEmpty()) return null
    return DirectorBrief(
      eventId = memory.eventId,
      title = title,
      tagline = tagline,
      targetDurationSec = targetDur,
      tone = tone,
      colorGrade = colorGrade,
      narrativeArc = arc,
      shotBlueprint = blueprint,
    )
  }

  private fun sanitizeTitle(raw: String?): String? {
    val title = raw
      ?.trim()
      ?.trim('「', '」', '“', '”', '"', '\'', '《', '》')
      ?.replace(Regex("\\s+"), " ")
      .orEmpty()
    return title.takeIf { it.isNotBlank() && !isGenericTitle(it) }
  }

  private fun isGenericTitle(title: String): Boolean {
    val normalized = title.lowercase()
    return normalized in setOf(
      "片段",
      "生活片段",
      "日常片段",
      "日常记录",
      "视频片段",
      "短片",
      "视频",
      "小视频",
      "vlog",
      "一段 vlog",
      "一段回忆视频",
      "回忆视频",
    )
  }

  private fun titleFromMemory(memory: EventMemory): String {
    val text = listOf(memory.storylineSummary, memory.emotionalArc, memory.visualStyleSignals)
      .joinToString(" ")
      .lowercase()
    return when {
      listOf("food", "meal", "restaurant", "dinner", "lunch", "breakfast", "餐", "美食", "吃").any { it in text } ->
        "餐桌上的小日子"
      listOf("zoo", "animal", "pet", "dog", "cat", "动物", "猫", "狗").any { it in text } ->
        "动物闯进镜头"
      listOf("travel", "trip", "street", "hotel", "beach", "outdoor", "旅行", "出游", "街").any { it in text } ->
        "路上的日常"
      listOf("family", "friend", "child", "kid", "people", "person", "家人", "朋友", "孩子", "人物").any { it in text } ->
        "身边人的一刻"
      memory.storylineSummary.isNotBlank() ->
        memory.storylineSummary.take(14).trimEnd('，', '。', ',', '.', ' ')
      else -> "这一刻的记录"
    }
  }

  private fun paceDefault(pace: Pace) = when (pace) {
    Pace.SNAPPY -> 17f
    Pace.BALANCED -> 20f
    Pace.LINGERING -> 25f
  }

  private fun clampToPaceBand(rawSec: Float, pace: Pace): Float = when (pace) {
    Pace.SNAPPY -> rawSec.coerceIn(15f, 18f)
    Pace.BALANCED -> rawSec.coerceIn(18f, 22f)
    Pace.LINGERING -> rawSec.coerceIn(22f, 28f)
  }

  private fun parseShot(obj: JsonObject): ShotRequest? {
    val pos = obj["position"]?.jsonPrimitive?.intOrNull ?: return null
    val roleStr = obj["role"]?.jsonPrimitive?.contentOrNull ?: "establishing"
    val role = ROLE_MAP[roleStr.trim().lowercase()] ?: ShotRole.ESTABLISHING
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
    title = titleFromMemory(memory),
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
    private val COLOR_GRADE_MAP = mapOf(
      "neutral" to ColorGrade.NEUTRAL,
      "warm" to ColorGrade.WARM,
      "cool" to ColorGrade.COOL,
      "vibrant" to ColorGrade.VIBRANT,
      "muted" to ColorGrade.MUTED,
      "cinematic_teal_orange" to ColorGrade.CINEMATIC_TEAL_ORANGE,
      "cinematic" to ColorGrade.CINEMATIC_TEAL_ORANGE,
      "vintage" to ColorGrade.VINTAGE,
    )
  }
}
