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
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.FeedbackScope
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.GlobalRevision
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.IterationFeedback
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Pace
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.RenderOnlyPatch
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.RevisedRequest
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.UserBrief
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.UserCurationRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

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
      JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement).objectOrNull()
    } catch (_: Throwable) {
      null
    } ?: return UserBrief(rawText = text)

    return parseUserBrief(obj, rawText = text)
  }

  private fun parseUserBrief(obj: JsonObject, rawText: String): UserBrief {
    val pace = parsePace(obj["pace"].primitiveOrNull()?.contentOrNull)
    val colorGrade = parseColorGrade(obj["color_grade"].primitiveOrNull()?.contentOrNull)
    val captionPolicy = parseCaptionPolicy(obj["caption_policy"].primitiveOrNull()?.contentOrNull)
    val durationSec = obj["duration_sec"].primitiveOrNull()?.floatOrNull
      ?.takeIf { it in 5f..120f }
    return UserBrief(
      parsedHook = obj["hook"].primitiveOrNull()?.contentOrNull.orEmpty(),
      parsedPayoff = obj["payoff"].primitiveOrNull()?.contentOrNull.orEmpty(),
      parsedTone = obj["tone"].primitiveOrNull()?.contentOrNull.orEmpty(),
      parsedPace = pace,
      parsedDurationSec = durationSec,
      mustHaveSubjects = obj["must_have_subjects"].arrayOrNull()
        ?.mapNotNull { it.primitiveOrNull()?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        ?.distinct()
        ?.take(4)
        .orEmpty(),
      parsedAvoid = obj["avoid"].arrayOrNull()
        ?.mapNotNull { it.primitiveOrNull()?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
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

  /** Optional CaptionPolicy parser — null when LLM emitted "null" / unknown.
   *  Used for GlobalRevision/RenderOnlyPatch fields that are *meant* to be
   *  null-signalled "leave it alone" rather than DEFAULT-snapped. */
  private fun parseCaptionPolicyOrNull(s: String?): CaptionPolicy? = when (s?.trim()?.lowercase()) {
    null, "", "null" -> null
    "default" -> CaptionPolicy.DEFAULT
    "none" -> CaptionPolicy.NONE
    "zh_only" -> CaptionPolicy.ZH_ONLY
    "bilingual" -> CaptionPolicy.BILINGUAL
    else -> null
  }

  // ============================================================================
  // parseFeedback (Milestone C — interpret the user's iteration feedback)
  // ============================================================================

  /**
   * Refines a raw IterationFeedback by asking Gemma to interpret userText +
   * targetedShotOrders + quickActions against the current timeline. The result
   * has parsedScope set to the cheapest scope that satisfies the feedback,
   * with parsedRevisions / parsedGlobal / parsedRenderPatch populated.
   *
   * Behavior contract:
   *   - When userText is blank AND targetedShotOrders is empty, the LLM call
   *     is skipped — the raw feedback (already enriched by client-side
   *     IterationPlanner.fromQuickActions) is returned as-is.
   *   - LLM failures fall back to the raw feedback (chip-derived path stays
   *     valid). The user always gets *some* iteration even if parsing breaks.
   *   - Any client-side parsedRenderPatch from chips is preserved by ORing
   *     into the LLM's output (chips and text can co-exist as MIXED).
   */
  suspend fun parseFeedback(
    raw: IterationFeedback,
    currentTimeline: Timeline,
  ): IterationFeedback {
    val needsLlmParse = raw.userText.isNotBlank() || raw.targetedShotOrders.isNotEmpty()
    if (!needsLlmParse) return raw

    val timelineSummary = currentTimeline.shots.joinToString("\n") { s ->
      val trim = s.videoTrim?.let { " trim=${"%.1f".format(it.startSec)}-${"%.1f".format(it.endSec)}s" }.orEmpty()
      "  #${s.order} [${s.mediaType.name.lowercase()}] dur=${"%.1f".format(s.durationSec)}s$trim caption=\"${s.caption}\""
    }
    val targetedDesc = if (raw.targetedShotOrders.isEmpty()) "无（用户没点选具体镜头）"
                       else raw.targetedShotOrders.joinToString(",")
    val chipsDesc = if (raw.quickActions.isEmpty()) "无"
                    else raw.quickActions.joinToString(",") { it.name.lowercase() }
    val userMsg = """
当前 timeline 镜头：
$timelineSummary

targeted_shot_orders: $targetedDesc
快捷 chip: $chipsDesc

用户原话：「${raw.userText}」

请输出 IterationFeedback JSON。
""".trimIndent()
    val rawResponse = agent.ask(
      systemPrompt = PromptStrings.INTENT_PARSER_FEEDBACK_SYSTEM,
      userText = userMsg,
      label = "intent_feedback",
    )
    val obj = try {
      JsonExtractor.firstObject(rawResponse)?.let(json::parseToJsonElement).objectOrNull()
    } catch (_: Throwable) {
      null
    } ?: return raw  // fall back to client-built feedback

    val llmScope = parseFeedbackScope(obj["scope"].primitiveOrNull()?.contentOrNull)
    val llmGlobal = obj["global"].objectOrNull()?.takeIf { it.isNotEmpty() }?.let(::parseGlobalRevision)
    val llmRenderPatch = obj["render_patch"].objectOrNull()?.takeIf { it.isNotEmpty() }?.let(::parseRenderOnlyPatch)
    val llmRevisions = obj["revisions"].arrayOrNull()
      ?.mapNotNull { el -> el.objectOrNull()?.let(::parseRevision) }
      ?.take(5)
      .orEmpty()

    // Combine LLM output with client-side chips. Chips that map to RenderOnlyPatch
    // (REMOVE_CAPTIONS / CHANGE_COLOR_GRADE) are preserved if LLM didn't already
    // override them — chips are "ground truth" for explicit user gestures.
    val mergedRenderPatch = mergeRenderPatches(client = raw.parsedRenderPatch, llm = llmRenderPatch)
    val mergedGlobal = llmGlobal ?: raw.parsedGlobal

    // Determine final scope by what's actually present after merge.
    val finalScope = computeScope(
      llmHint = llmScope,
      hasRevisions = llmRevisions.isNotEmpty(),
      hasGlobal = mergedGlobal != null,
      hasRenderPatch = mergedRenderPatch != null,
    )
    return raw.copy(
      parsedScope = finalScope,
      parsedRevisions = llmRevisions,
      parsedGlobal = mergedGlobal,
      parsedRenderPatch = mergedRenderPatch,
    )
  }

  private fun parseFeedbackScope(s: String?): FeedbackScope? = when (s?.trim()?.lowercase()) {
    "render_only" -> FeedbackScope.RENDER_ONLY
    "shot_level" -> FeedbackScope.SHOT_LEVEL
    "global" -> FeedbackScope.GLOBAL
    "mixed" -> FeedbackScope.MIXED
    else -> null
  }

  private fun computeScope(
    llmHint: FeedbackScope?,
    hasRevisions: Boolean,
    hasGlobal: Boolean,
    hasRenderPatch: Boolean,
  ): FeedbackScope {
    val nonRenderTouches = listOf(hasRevisions, hasGlobal).count { it }
    return when {
      nonRenderTouches == 0 && hasRenderPatch -> FeedbackScope.RENDER_ONLY
      hasRevisions && hasGlobal -> FeedbackScope.MIXED
      hasRevisions -> FeedbackScope.SHOT_LEVEL
      hasGlobal -> FeedbackScope.GLOBAL
      // No structural changes detected; trust LLM hint or fall back to render-only.
      else -> llmHint ?: FeedbackScope.RENDER_ONLY
    }
  }

  private fun parseGlobalRevision(obj: JsonObject): GlobalRevision? {
    val durationRaw = obj["new_target_duration_sec"].primitiveOrNull()?.floatOrNull
    val newDuration = durationRaw?.takeIf { it in 5f..120f }
    val newPace = parsePace(obj["new_pace"].primitiveOrNull()?.contentOrNull)
    val newTone = obj["new_tone"].primitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
    val newGrade = parseColorGrade(obj["new_color_grade"].primitiveOrNull()?.contentOrNull)
    val captionPolicy = parseCaptionPolicyOrNull(obj["caption_policy"].primitiveOrNull()?.contentOrNull)
    val anyChange = newDuration != null || newPace != null || newTone != null || newGrade != null || captionPolicy != null
    if (!anyChange) return null
    return GlobalRevision(
      newTargetDurationSec = newDuration,
      newPace = newPace,
      newTone = newTone,
      newColorGrade = newGrade,
      captionPolicy = captionPolicy,
    )
  }

  private fun parseRenderOnlyPatch(obj: JsonObject): RenderOnlyPatch? {
    val newGrade = parseColorGrade(obj["new_color_grade"].primitiveOrNull()?.contentOrNull)
    val captionPolicy = parseCaptionPolicyOrNull(obj["caption_policy"].primitiveOrNull()?.contentOrNull)
    val newBgmTone = obj["new_bgm_tone"].primitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
    if (newGrade == null && captionPolicy == null && newBgmTone == null) return null
    return RenderOnlyPatch(
      newColorGrade = newGrade,
      captionPolicy = captionPolicy,
      newBgmTone = newBgmTone,
    )
  }

  private fun parseRevision(obj: JsonObject): RevisedRequest? {
    val order = obj["shot_order"].primitiveOrNull()?.intOrNull ?: return null
    val patchesObj = obj["patches"].objectOrNull() ?: return null
    val patches = patchesObj.entries
      .mapNotNull { (k, v) ->
        val s = v.primitiveOrNull()?.contentOrNull?.trim() ?: return@mapNotNull null
        if (s.isEmpty()) null else k to s
      }
      .toMap()
    if (patches.isEmpty()) return null
    return RevisedRequest(shotOrder = order, newRequest = null, patches = patches)
  }

  private fun mergeRenderPatches(client: RenderOnlyPatch?, llm: RenderOnlyPatch?): RenderOnlyPatch? {
    if (client == null) return llm
    if (llm == null) return client
    return RenderOnlyPatch(
      newColorGrade = llm.newColorGrade ?: client.newColorGrade,
      captionPolicy = llm.captionPolicy ?: client.captionPolicy,
      newBgmTone = llm.newBgmTone ?: client.newBgmTone,
    )
  }

  private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

  private fun JsonElement?.arrayOrNull(): JsonArray? = this as? JsonArray

  private fun JsonElement?.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
}
