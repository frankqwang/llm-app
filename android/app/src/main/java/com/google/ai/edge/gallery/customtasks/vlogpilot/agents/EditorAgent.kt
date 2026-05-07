/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * step5_editor: for each ShotRequest, recall up to 8 candidates → ask Gemma 4
 * to pick one with rationale → assemble ShotSpec. Recall uses VlmTags from
 * the per-asset annotation pass; the curate prompt also surfaces those tags
 * to Gemma alongside the candidate thumbnails so the model has structured
 * context (scene/subjects/action/mood/salient) per candidate.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import android.content.Context
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.MediaLoader
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.Recall
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ColorGrade
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRequest
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.TransitionKind
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.VideoTrim
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EditorAgent(
  private val context: Context,
  private val agent: AgentRuntime,
) {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /**
   * @param previousShot brief description of the previously chosen shot, for continuity
   * @return chosen ShotSpec, or null if recall returned no candidates
   */
  suspend fun pickShot(
    order: Int,
    request: ShotRequest,
    nBlueprint: Int,
    eventAssets: List<Asset>,
    perceptions: Map<String, Perception>,
    excludedAssetIds: Set<String>,
    previousShot: String,
    directorTone: String = "",
  ): ShotSpec? {
    val candidates = Recall.topK(
      request = request,
      nBlueprint = nBlueprint,
      eventAssets = eventAssets,
      perceptions = perceptions,
      excludedAssetIds = excludedAssetIds,
      k = 8,
    )
    val grade = ColorGradeFromTone.pick(directorTone)
    if (candidates.isEmpty()) return null
    if (candidates.size == 1) return composeSpec(order, request, candidates[0], "only candidate", grade)

    val visualCandidates = candidates.mapNotNull { candidate ->
      loadCandidateBitmap(candidate)?.let { candidate to it }
    }
    if (visualCandidates.isEmpty()) {
      return candidates.firstOrNull()?.let { composeSpec(order, request, it, "thumbnail load failed; fallback to top recall", grade) }
    }
    val thumbs = visualCandidates.map { it.second }
    val tagSummary = visualCandidates.withIndex().joinToString("\n") { (i, pair) ->
      val tags = pair.first.perception.vlmTags
      val video = pair.first.perception.videoInsight
      val parts = listOfNotNull(
        tags.scene.takeIf { it.isNotBlank() }?.let { "scene=$it" },
        tags.action.takeIf { it.isNotBlank() }?.let { "action=$it" },
        tags.mood.takeIf { it.isNotBlank() }?.let { "mood=$it" },
        tags.salient.takeIf { it.isNotBlank() }?.let { "salient=$it" },
        video.summary.takeIf { it.isNotBlank() }?.let { "video=$it" },
        video.bestMomentSec.takeIf { it > 0f }?.let { "best=${"%.1f".format(it)}s" },
        pair.first.videoTrim?.let { "trim=${"%.1f".format(it.startSec)}-${"%.1f".format(it.endSec)}s ${pair.first.windowReason}" },
        tags.subjects.takeIf { it.isNotEmpty() }?.let { "subjects=${it.joinToString("、")}" },
      )
      "  ${i + 1}. ${parts.joinToString(" / ").ifBlank { "(no tags)" }}"
    }
    val userMsg = """
当前 slot：role=${request.role}; mood=${request.moodTarget}; visual_req=${request.visualRequirements}
previous_shot_summary: $previousShot

候选标签（VLM 已经看过每张图，结构化摘要）：
$tagSummary

请综合标签 + 缩略图视觉，从 ${thumbs.size} 张候选中选 1 张（编号 1..${thumbs.size}）。
""".trimIndent()
    val raw = try {
      agent.ask(systemPrompt = PromptStrings.EDITOR_SYSTEM, userText = userMsg, images = thumbs)
    } finally {
      // Free thumbnail bitmaps once they've been encoded into the inference request.
      thumbs.forEach { runCatching { it.recycle() } }
    }
    val obj = try { JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement)?.jsonObject } catch (_: Throwable) { null }
    val idx = obj?.get("chosen_index")?.jsonPrimitive?.intOrNull?.minus(1) ?: 0
    val rationale = obj?.get("rationale")?.jsonPrimitive?.contentOrNull.orEmpty()
    val chosen = visualCandidates.getOrNull(idx)?.first ?: visualCandidates.first().first
    return composeSpec(order, request, chosen, rationale, grade)
  }

  private fun loadCandidateBitmap(candidate: Recall.Candidate) =
    if (candidate.videoTrim != null && candidate.asset.mediaType != MediaType.IMAGE) {
      val previewSec = (candidate.videoTrim.startSec + candidate.videoTrim.endSec) / 2f
      MediaLoader.loadVideoFrame(context, candidate.asset, previewSec, maxSide = 512)
        ?: MediaLoader.loadImage(context, candidate.asset, maxSide = 512)
    } else {
      MediaLoader.loadImage(context, candidate.asset, maxSide = 512)
    }

  private fun composeSpec(
    order: Int,
    request: ShotRequest,
    candidate: Recall.Candidate,
    rationale: String,
    grade: ColorGrade,
  ): ShotSpec {
    val asset = candidate.asset
    val mediaType = asset.mediaType
    // For video / live photo: use the cached multi-frame VLM best moment as a semantic anchor,
    // then score local windows with sharpness / role / scene-cut stability. This keeps the
    // expensive VLM pass at once per asset rather than once per shot.
    val trim = if (mediaType != MediaType.IMAGE && asset.durationMs > 0) {
      val durSec = asset.durationMs / 1000f
      val want = request.durationSec.coerceAtMost(durSec)
      candidate.videoTrim ?: run {
        val start = VideoWindowPicker.pick(context, asset, want, candidate.perception, request.role)
          .coerceIn(0f, (durSec - want).coerceAtLeast(0f))
        VideoTrim(startSec = start, endSec = (start + want).coerceAtMost(durSec))
      }
    } else null

    return ShotSpec(
      order = order,
      assetId = asset.id,
      mediaType = mediaType,
      durationSec = request.durationSec,
      kenBurns = normalizeKenBurns(request.kenBurnsHint),
      colorGrade = grade,
      caption = request.captionText,
      transitionIn = transitionFor(order, request.transitionInHint),
      videoTrim = trim,
      rationale = listOf(rationale, candidate.windowReason.takeIf { it.isNotBlank() }?.let { "window: $it" })
        .filterNotNull()
        .joinToString(" / "),
    )
  }

  companion object {
    private val TRANSITION_MAP = mapOf(
      "cut" to TransitionKind.CUT,
      "fade" to TransitionKind.FADE,
      "crossfade" to TransitionKind.CROSSFADE,
      "fadeblack" to TransitionKind.FADEBLACK,
      "fadewhite" to TransitionKind.FADEWHITE,
      "fade_white" to TransitionKind.FADEWHITE,
      "slideleft" to TransitionKind.SLIDELEFT,
      "slide_left" to TransitionKind.SLIDELEFT,
      "slideright" to TransitionKind.SLIDERIGHT,
      "slide_right" to TransitionKind.SLIDERIGHT,
      "circleopen" to TransitionKind.CIRCLEOPEN,
      "circle_open" to TransitionKind.CIRCLEOPEN,
      "circleclose" to TransitionKind.CIRCLECLOSE,
      "circle_close" to TransitionKind.CIRCLECLOSE,
      "zoomin" to TransitionKind.ZOOMIN,
      "zoom_in" to TransitionKind.ZOOMIN,
      "smoothleft" to TransitionKind.SMOOTHLEFT,
      "smooth_left" to TransitionKind.SMOOTHLEFT,
      "smoothright" to TransitionKind.SMOOTHRIGHT,
      "smooth_right" to TransitionKind.SMOOTHRIGHT,
    )

    private fun transitionFor(order: Int, raw: String): TransitionKind {
      val key = raw.trim().lowercase()
      if (key.isBlank()) return if (order == 1) TransitionKind.FADE else TransitionKind.CUT
      val requested = TRANSITION_MAP[key] ?: return if (order == 1) TransitionKind.FADE else TransitionKind.CUT
      return when (requested) {
        TransitionKind.SLIDELEFT,
        TransitionKind.SLIDERIGHT,
        TransitionKind.CIRCLEOPEN,
        TransitionKind.CIRCLECLOSE,
        TransitionKind.ZOOMIN,
        TransitionKind.SMOOTHLEFT,
        TransitionKind.SMOOTHRIGHT -> TransitionKind.CROSSFADE
        else -> requested
      }
    }

    private fun normalizeKenBurns(raw: String): String =
      when (raw.trim().lowercase()) {
        "zoom_in", "zoomin", "in" -> "in"
        "zoom_out", "zoomout", "out" -> "out"
        "pan_left", "panleft" -> "pan_left"
        "pan_right", "panright" -> "pan_right"
        else -> raw
      }
  }
}
