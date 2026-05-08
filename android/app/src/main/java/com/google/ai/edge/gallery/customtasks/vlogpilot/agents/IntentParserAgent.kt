/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Parses the user's free-text intent into a structured UserBrief. Single
 * Gemma call per submission — cheap (~3-5s) and JSON-cached on disk.
 *
 * Robustness contract:
 *   - Empty/whitespace user text → empty UserBrief, no LLM call (saves a few seconds).
 *   - Malformed JSON / LLM hiccup → fallback UserBrief with rawText preserved
 *     so downstream agents still get the user's words.
 *   - Unknown enum values → null/default. Don't coerce wrong values into valid ones.
 *
 * The parsed fields are guidance — downstream Audience/Director still get the
 * raw text appended to their prompts as authoritative-but-fuzzy context.
 *
 * (Milestone B implements parseInitial. Milestone C will add parseFeedback,
 * which has a different prompt and target type IterationFeedback.)
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.CaptionPolicy
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ColorGrade
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Pace
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.UserBrief
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.UserCurationRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class IntentParserAgent(private val agent: AgentRuntime) {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /**
   * Parses the user's initial intent text from CuratorScreen submission.
   * Returns a UserBrief that:
   *   - has rawText set to request.intentText (always)
   *   - has parsed* fields populated where the LLM identified clear values
   *   - has empty/null defaults where the user didn't say anything
   */
  suspend fun parseInitial(request: UserCurationRequest): UserBrief {
    val text = request.intentText.trim()
    if (text.isEmpty()) return UserBrief(rawText = "")

    val userMsg = """
用户挑选了 ${request.selectedAssetIds.size} 个素材。
用户原文："$text"
请输出 UserBrief JSON。
""".trimIndent()
    val raw = agent.ask(
      systemPrompt = PromptStrings.INTENT_PARSER_INITIAL_SYSTEM,
      userText = userMsg,
      label = "intent_initial",
    )
    val obj = try {
      JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement)?.jsonObject
    } catch (_: Throwable) {
      null
    } ?: return UserBrief(rawText = text)

    return parseUserBrief(obj, rawText = text)
  }

  private fun parseUserBrief(obj: JsonObject, rawText: String): UserBrief {
    val pace = parsePace(obj["pace"]?.jsonPrimitive?.contentOrNull)
    val colorGrade = parseColorGrade(obj["color_grade"]?.jsonPrimitive?.contentOrNull)
    val captionPolicy = parseCaptionPolicy(obj["caption_policy"]?.jsonPrimitive?.contentOrNull)
    val durationSec = obj["duration_sec"]?.jsonPrimitive?.floatOrNull
      ?.takeIf { it in 5f..120f }
    return UserBrief(
      parsedHook = obj["hook"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      parsedPayoff = obj["payoff"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      parsedTone = obj["tone"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      parsedPace = pace,
      parsedDurationSec = durationSec,
      mustHaveSubjects = obj["must_have_subjects"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        ?.distinct()
        ?.take(4)
        .orEmpty(),
      parsedAvoid = obj["avoid"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        ?.take(4)
        .orEmpty(),
      captionPolicy = captionPolicy,
      parsedColorGrade = colorGrade,
      rawText = rawText,
    )
  }

  private fun parsePace(s: String?): Pace? = when (s?.trim()?.lowercase()) {
    "snappy" -> Pace.SNAPPY
    "balanced" -> Pace.BALANCED
    "lingering" -> Pace.LINGERING
    else -> null
  }

  private fun parseColorGrade(s: String?): ColorGrade? = when (s?.trim()?.lowercase()) {
    "neutral" -> ColorGrade.NEUTRAL
    "warm" -> ColorGrade.WARM
    "cool" -> ColorGrade.COOL
    "vibrant" -> ColorGrade.VIBRANT
    "muted" -> ColorGrade.MUTED
    "cinematic_teal_orange", "cinematic" -> ColorGrade.CINEMATIC_TEAL_ORANGE
    "vintage" -> ColorGrade.VINTAGE
    else -> null
  }

  private fun parseCaptionPolicy(s: String?): CaptionPolicy = when (s?.trim()?.lowercase()) {
    "none" -> CaptionPolicy.NONE
    "zh_only" -> CaptionPolicy.ZH_ONLY
    "bilingual" -> CaptionPolicy.BILINGUAL
    else -> CaptionPolicy.DEFAULT
  }
}
