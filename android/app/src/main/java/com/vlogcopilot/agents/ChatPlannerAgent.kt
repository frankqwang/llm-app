/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Model-driven planner for the VlogCopilot chat surface. The UI should feel like
 * an editing agent, not a keyword command bar: the model reads the current app
 * state, answers the user in natural language, and returns one structured
 * action for the UI to execute.
 */
package com.vlogcopilot.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

class ChatPlannerAgent(private val agent: AgentRuntime) {

  enum class Action {
    ANSWER,
    SUGGEST_EDITS,
    SCAN_ALBUM,
    MAKE_CANDIDATE,
    ITERATE,
    OPEN_ALBUM,
    OPEN_MODELS,
    CLARIFY,
  }

  data class VideoSummary(
    val eventId: String,
    val title: String,
    val shotCount: Int,
    val assetCount: Int,
    val durationSec: Float,
    val versionCount: Int,
  )

  data class CandidateSummary(
    val eventId: String,
    val title: String,
    val assetCount: Int,
    val videoCount: Int,
    val score: Float,
    val status: String,
    val summary: String,
  )

  data class ChatContext(
    val modelReady: Boolean,
    val albumCount: Int,
    val pipelineRunning: Boolean,
    val activeEventId: String?,
    val videos: List<VideoSummary>,
    val candidates: List<CandidateSummary>,
  )

  data class Plan(
    val action: Action,
    val reply: String,
    val targetEventId: String? = null,
    val feedbackText: String = "",
    val confidence: Float = 0f,
  )

  suspend fun plan(userText: String, context: ChatContext): Plan {
    if (!context.modelReady) return noModelPlan()

    val raw = agent.ask(
      systemPrompt = SYSTEM_PROMPT,
      userText = buildUserPrompt(userText, context),
      label = "chat_planner",
    )
    return parsePlan(raw) ?: fallbackPlan(userText, context)
  }

  suspend fun planAudio(audioWav: ByteArray, context: ChatContext): Plan {
    if (!context.modelReady) return noModelPlan()

    val raw = agent.ask(
      systemPrompt = SYSTEM_PROMPT,
      userText = buildUserPrompt(
        userText = "用户发来了一段语音剪辑指令。请先直接理解这段音频，再结合当前状态决定下一步。",
        context = context,
      ),
      audioClips = listOf(audioWav),
      label = "chat_planner_audio",
    )
    return parsePlan(raw) ?: Plan(
      action = Action.CLARIFY,
      reply = "这段语音没有稳定解析出明确的剪辑指令。你可以再说一遍，或者直接点候选故事让我制作。",
      confidence = 0f,
    )
  }

  private fun noModelPlan() = Plan(
    action = Action.OPEN_MODELS,
    reply = "我需要先有一个本地多模态模型，才能接管剪辑判断。先导入 Gemma 模型，回来我就能看素材、给建议并执行修改。",
  )

  private fun buildUserPrompt(userText: String, context: ChatContext): String = buildString {
    appendLine("User message:")
    appendLine(userText)
    appendLine()
    appendLine("Current app state:")
    appendLine("- model_ready=${context.modelReady}")
    appendLine("- album_count=${context.albumCount}")
    appendLine("- pipeline_running=${context.pipelineRunning}")
    appendLine("- active_event_id=${context.activeEventId ?: "none"}")
    appendLine()
    appendLine("Finished videos, newest first, max 6:")
    if (context.videos.isEmpty()) {
      appendLine("- none")
    } else {
      context.videos.take(6).forEach { v ->
        appendLine(
          "- event_id=${v.eventId}; title=${v.title}; shots=${v.shotCount}; " +
            "assets=${v.assetCount}; duration=${"%.1f".format(v.durationSec)}s; version=${v.versionCount}",
        )
      }
    }
    appendLine()
    appendLine("Candidate stories, max 8:")
    if (context.candidates.isEmpty()) {
      appendLine("- none")
    } else {
      context.candidates.take(8).forEach { c ->
        appendLine(
          "- event_id=${c.eventId}; title=${c.title}; assets=${c.assetCount}; " +
            "videos=${c.videoCount}; score=${"%.2f".format(c.score)}; status=${c.status}; " +
            "summary=${c.summary.take(90)}",
        )
      }
    }
    appendLine()
    appendLine("Return JSON only. Do not include markdown.")
  }

  private fun parsePlan(raw: String): Plan? {
    val obj = try {
      JsonExtractor.firstObject(raw)
        ?.let { json.parseToJsonElement(it) as? JsonObject }
    } catch (_: Throwable) {
      null
    } ?: return null

    val action = parseAction(obj["action"].stringOrNull()) ?: return null
    val reply = obj["reply"].stringOrNull()?.trim().orEmpty()
    if (reply.isBlank()) return null
    return Plan(
      action = action,
      reply = reply,
      targetEventId = obj["target_event_id"].stringOrNull()?.takeIf { it.isNotBlank() && it != "null" },
      feedbackText = obj["feedback_text"].stringOrNull().orEmpty(),
      confidence = obj["confidence"].floatOrNullSafe() ?: 0f,
    )
  }

  private fun parseAction(raw: String?): Action? = when (raw?.trim()?.lowercase()) {
    "answer" -> Action.ANSWER
    "suggest_edits" -> Action.SUGGEST_EDITS
    "scan_album" -> Action.SCAN_ALBUM
    "make_candidate" -> Action.MAKE_CANDIDATE
    "iterate" -> Action.ITERATE
    "open_album" -> Action.OPEN_ALBUM
    "open_models" -> Action.OPEN_MODELS
    "clarify" -> Action.CLARIFY
    else -> null
  }

  private fun fallbackPlan(userText: String, context: ChatContext): Plan {
    val text = userText.lowercase()
    val activeVideo = context.activeEventId?.let { id -> context.videos.firstOrNull { it.eventId == id } }
      ?: context.videos.firstOrNull()

    context.candidates.firstOrNull { c ->
      userText.contains(c.title) || c.title.contains(userText)
    }?.let { candidate ->
      return Plan(
        action = Action.MAKE_CANDIDATE,
        targetEventId = candidate.eventId,
        reply = "我理解你想做「${candidate.title}」。我会先读这组素材，再写分镜、选镜头并导出一条短片。",
      )
    }

    if (listOf("改什么", "可以改", "能改", "怎么改", "优化什么", "建议").any { it in text }) {
      val title = activeVideo?.title ?: "当前成片"
      return Plan(
        action = Action.SUGGEST_EDITS,
        targetEventId = activeVideo?.eventId,
        reply = "这条「$title」可以继续从节奏、镜头替换、开头钩子、字幕文案、调色氛围和时长上改。你可以直接说“再快一点”“换掉第 3 个镜头”“调得更温暖”，我会按当前版本继续迭代。",
      )
    }

    if (listOf("扫", "相册", "推荐", "做一条", "vlog", "剪一条").any { it in text }) {
      return Plan(
        action = Action.SCAN_ALBUM,
        reply = "我先扫描相册，按时间、人物、画面质量和故事完整度挑几组候选。扫描期间你可以继续说主题偏好，比如“更温馨”“只要旅行”“不要截图”。",
      )
    }

    if (activeVideo != null && listOf("快", "慢", "字幕", "调色", "镜头", "换", "短", "长").any { it in text }) {
      return Plan(
        action = Action.ITERATE,
        targetEventId = activeVideo.eventId,
        feedbackText = userText,
        reply = "我会按这句要求修改「${activeVideo.title}」，优先只动必要环节，保留已经好的镜头和结构。",
      )
    }

    return Plan(
      action = Action.ANSWER,
      reply = if (activeVideo != null) {
        "我现在看的是「${activeVideo.title}」。你可以让我分析哪里能改，也可以直接给修改方向，比如节奏、字幕、调色、镜头替换或重新选故事。"
      } else {
        "我可以先看相册给你推荐故事，也可以按你指定的主题做一条 vlog。比如说“帮我做一条最近的家庭日常”或“扫描相册推荐几个故事”。"
      },
    )
  }

  private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

  private fun JsonElement?.floatOrNullSafe(): Float? =
    (this as? JsonPrimitive)?.floatOrNull

  companion object {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val SYSTEM_PROMPT = """
You are VlogCopilot's intelligent editing agent. You play the role that Codex
plays for code, but your objects are album assets, candidate stories, finished
videos, and user editing requests.

Rules:
1. Reply in concise Chinese, like a professional video-editing agent.
2. Decide one action for the app to execute:
   answer, suggest_edits, scan_album, make_candidate, iterate, open_album,
   open_models, or clarify.
3. If the user asks what can be changed, explain concrete edit options for the
   current video: rhythm, shot replacement, opening hook, captions, color mood,
   duration, or story focus.
4. If the user gives an edit request for an existing video, choose iterate and
   put the edit instruction in feedback_text.
5. If the user wants a new vlog, story recommendations, or album scanning,
   choose scan_album.
6. If the user names a candidate title or event_id, choose make_candidate.
7. Do not say generic fallback phrases like "I did not understand." Use the
   current app state to propose a next step, or ask one narrow clarifying
   question.
8. If pipeline_running is true, do not imply that a new scan or new render will
   interrupt the current job. Treat extra user input as follow-up intent for
   the current or next version unless the user explicitly asks to cancel.

Output exactly one JSON object:
{
  "action": "answer | suggest_edits | scan_album | make_candidate | iterate | open_album | open_models | clarify",
  "reply": "Chinese text shown to the user",
  "target_event_id": "event id or null",
  "feedback_text": "instruction for iterate, or empty",
  "confidence": 0.0
}
""".trimIndent()
  }
}
