/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * step5_editor: for each ShotRequest, recall up to 8 candidates → ask Gemma 4
 * to pick one with rationale → assemble ShotSpec. Recall uses VlmTags from
 * the per-asset annotation pass; the curate prompt also surfaces those tags
 * to Gemma alongside the candidate thumbnails so the model has structured
 * context (scene/subjects/action/mood/salient) per candidate.
 */
package com.vlogcopilot.agents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.vlogcopilot.perception.MediaLoader
import com.vlogcopilot.pipeline.Recall
import com.vlogcopilot.schemas.Asset
import com.vlogcopilot.schemas.ColorGrade
import com.vlogcopilot.schemas.MediaType
import com.vlogcopilot.schemas.Perception
import com.vlogcopilot.schemas.ShotRequest
import com.vlogcopilot.schemas.ShotSpec
import com.vlogcopilot.schemas.TransitionKind
import com.vlogcopilot.schemas.VideoTrim
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

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
    /** Director's tone string (used as a fallback for ColorGrade if directorColorGrade is NEUTRAL). */
    directorTone: String = "",
    /** Director's chosen ColorGrade enum. NEUTRAL means "use ColorGradeFromTone keyword inference on directorTone". */
    directorColorGrade: ColorGrade = ColorGrade.NEUTRAL,
    /** Subject keywords from UserBrief.mustHaveSubjects — boosts assets whose
     *  VLM tags mention these in Recall scoring. Empty for auto events. */
    mustHaveSubjects: List<String> = emptyList(),
  ): ShotSpec? {
    val candidates = Recall.topK(
      request = request,
      nBlueprint = nBlueprint,
      eventAssets = eventAssets,
      perceptions = perceptions,
      excludedAssetIds = excludedAssetIds,
      k = 8,
      mustHaveSubjects = mustHaveSubjects,
    )
    val grade = if (directorColorGrade != ColorGrade.NEUTRAL) directorColorGrade
                else ColorGradeFromTone.pick(directorTone)
    if (candidates.isEmpty()) return null
    if (candidates.size == 1) return composeSpec(order, request, candidates[0], "only candidate", grade)
    if (shouldTrustRecall(candidates)) {
      return composeSpec(order, request, candidates[0], "high-confidence recall pick", grade)
    }

    val visualCandidates = candidates.take(MAX_VISUAL_CANDIDATES).mapNotNull { candidate ->
      loadCandidateBitmap(candidate)?.let { candidate to it }
    }
    if (visualCandidates.isEmpty()) {
      return candidates.firstOrNull()?.let { composeSpec(order, request, it, "thumbnail load failed; fallback to top recall", grade) }
    }
    if (visualCandidates.size == 1) {
      val only = visualCandidates.first()
      only.second.recycle()
      return composeSpec(order, request, only.first, "only visual candidate after thumbnail load", grade)
    }
    val tagSummary = visualCandidates.withIndex().joinToString("\n") { (i, pair) ->
      val tags = pair.first.perception.vlmTags
      val video = pair.first.perception.videoInsight
      val desc = tags.visualDescription.takeIf { it.isNotBlank() }
        ?: video.visualDescription.takeIf { it.isNotBlank() }
        ?: tags.salient.takeIf { it.isNotBlank() }
        ?: video.summary.takeIf { it.isNotBlank() }
      val parts = listOfNotNull(
        desc?.let { "描述=$it" },
        tags.composition.takeIf { it.isNotBlank() }?.let { "构图=$it" },
        tags.lighting.takeIf { it.isNotBlank() }?.let { "光线=$it" },
        tags.motionHint.takeIf { it.isNotBlank() }?.let { "动态=$it" },
        video.cameraWork.takeIf { it.isNotBlank() }?.let { "镜头=$it" },
        video.pacing.takeIf { it.isNotBlank() }?.let { "节奏=$it" },
        video.bestMomentSec.takeIf { it > 0f }?.let { "best=${"%.1f".format(it)}s" },
        pair.first.videoTrim?.let { "trim=${"%.1f".format(it.startSec)}-${"%.1f".format(it.endSec)}s ${pair.first.windowReason}" },
      )
      "  ${i + 1}. ${parts.joinToString(" / ").ifBlank { "(no tags)" }}"
    }
    val contactSheet = buildCandidateContactSheet(visualCandidates)
    visualCandidates.forEach { (_, bitmap) -> bitmap.recycle() }
    val userMsg = """
当前 slot：role=${request.role}; mood=${request.moodTarget}; visual_req=${request.visualRequirements}
previous_shot_summary: $previousShot

候选标签（VLM 已经看过每张图，结构化摘要）：
$tagSummary

候选都在同一张编号接触表里，请综合标签和缩略图，从 ${visualCandidates.size} 个候选中选 1 个（编号 1..${visualCandidates.size}）。
请输出 chosen_index，对应接触表上的数字编号；图和标签冲突时以图为准。
""".trimIndent()
    val raw = try {
      agent.ask(systemPrompt = PromptStrings.EDITOR_SYSTEM, userText = userMsg, images = listOf(contactSheet), label = "editor")
    } finally {
      contactSheet.recycle()
    }
    val obj = try { JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement)?.jsonObject } catch (_: Throwable) { null }
    // Coerce chosen_index into [0, size-1] before using it. Gemma 4 E2B occasionally
    // emits 0 (which would underflow to -1) or numbers larger than the candidate set
    // when it hallucinates entries. Clamping keeps runner-up de-dup logic correct.
    val rawChosen = obj?.get("chosen_index")?.jsonPrimitive?.intOrNull?.minus(1) ?: 0
    val idx = rawChosen.coerceIn(0, visualCandidates.lastIndex.coerceAtLeast(0))
    val rationale = obj?.get("rationale")?.jsonPrimitive?.contentOrNull.orEmpty()
    val runnerUpIdx = obj?.get("runner_up_index")?.jsonPrimitive?.intOrNull?.let { it - 1 }?.takeIf { it in visualCandidates.indices && it != idx }
    val whyNot = obj?.get("why_not_others")?.jsonPrimitive?.contentOrNull.orEmpty()
    val chosen = visualCandidates.getOrNull(idx)?.first ?: visualCandidates.first().first
    val combinedRationale = listOfNotNull(
      rationale.ifBlank { null },
      runnerUpIdx?.let { "runner-up=#${it + 1}" },
      whyNot.takeIf { it.isNotBlank() }?.let { "排除其它: $it" },
    ).joinToString(" / ")
    return composeSpec(order, request, chosen, combinedRationale, grade)
  }

  private fun shouldTrustRecall(candidates: List<Recall.Candidate>): Boolean {
    val top = candidates.firstOrNull() ?: return false
    val runnerUp = candidates.drop(1).firstOrNull { it.asset.id != top.asset.id } ?: candidates.getOrNull(1)
    val gap = top.score - (runnerUp?.score ?: 0f)
    return top.score >= 0.74f && gap >= 0.18f
  }

  private fun buildCandidateContactSheet(visualCandidates: List<Pair<Recall.Candidate, Bitmap>>): Bitmap {
    val count = visualCandidates.size.coerceAtLeast(1)
    val cols = if (count <= 2) count else 2
    val rows = ((count + cols - 1) / cols).coerceAtLeast(1)
    val sheet = Bitmap.createBitmap(cols * CANDIDATE_CELL_W, rows * CANDIDATE_CELL_H, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet).apply { drawColor(Color.rgb(12, 12, 12)) }
    visualCandidates.forEachIndexed { index, (_, bitmap) ->
      val col = index % cols
      val row = index / cols
      val x = col * CANDIDATE_CELL_W
      val y = row * CANDIDATE_CELL_H
      drawCandidateCell(canvas, bitmap, x, y)
      drawCandidateBadge(canvas, x.toFloat(), y.toFloat(), index + 1)
    }
    return sheet
  }

  private fun drawCandidateCell(canvas: Canvas, bitmap: Bitmap, x: Int, y: Int) {
    val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
    val dstAspect = CANDIDATE_CELL_W.toFloat() / CANDIDATE_CELL_H.toFloat()
    val src = if (srcAspect > dstAspect) {
      val cropW = (bitmap.height * dstAspect).toInt().coerceAtLeast(1)
      val left = ((bitmap.width - cropW) / 2).coerceAtLeast(0)
      Rect(left, 0, (left + cropW).coerceAtMost(bitmap.width), bitmap.height)
    } else {
      val cropH = (bitmap.width / dstAspect).toInt().coerceAtLeast(1)
      val top = ((bitmap.height - cropH) / 2).coerceAtLeast(0)
      Rect(0, top, bitmap.width, (top + cropH).coerceAtMost(bitmap.height))
    }
    val dst = Rect(x, y, x + CANDIDATE_CELL_W, y + CANDIDATE_CELL_H)
    canvas.drawBitmap(bitmap, src, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
  }

  private fun drawCandidateBadge(canvas: Canvas, x: Float, y: Float, index: Int) {
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 122, 24) }
    val rect = RectF(x + 10f, y + 10f, x + 56f, y + 56f)
    canvas.drawRoundRect(rect, 20f, 20f, bg)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = 25f
      textAlign = Paint.Align.CENTER
    }
    val cy = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(index.toString(), rect.centerX(), cy, textPaint)
  }

  private fun loadCandidateBitmap(candidate: Recall.Candidate): Bitmap? =
    if (candidate.asset.mediaType != MediaType.IMAGE) {
      loadVideoStrip(candidate)
        ?: candidate.videoTrim?.let { trim ->
          val previewSec = (trim.startSec + trim.endSec) / 2f
          MediaLoader.loadVideoFrame(context, candidate.asset, previewSec, maxSide = 512)
        }
        ?: MediaLoader.loadImage(context, candidate.asset, maxSide = 512)
    } else {
      MediaLoader.loadImage(context, candidate.asset, maxSide = 512)
    }

  private fun loadVideoStrip(candidate: Recall.Candidate): Bitmap? {
    val asset = candidate.asset
    val durSec = asset.durationMs.takeIf { it > 0 }?.let { it / 1000f } ?: return null
    val trim = candidate.videoTrim
    val timestamps = if (trim != null) {
      val start = trim.startSec.coerceIn(0f, durSec)
      val end = trim.endSec.coerceIn(start, durSec)
      val span = (end - start).coerceAtLeast(0.3f)
      listOf(
        start + span * 0.18f,
        start + span * 0.50f,
        start + span * 0.82f,
      )
    } else {
      val best = candidate.perception.videoInsight.bestMomentSec
        .takeIf { it > 0f }
        ?: candidate.perception.videoInsight.bestMomentIndex
          .takeIf { it > 0 }
          ?.let { candidate.perception.videoInsight.frameTimestampsSec.getOrNull(it - 1) }
        ?: (durSec / 2f)
      listOf(best - 0.7f, best, best + 0.7f)
    }.map { it.coerceIn(0f, durSec) }

    val frames = MediaLoader.sampleVideoFramesAt(context, asset, timestamps, maxSide = VIDEO_STRIP_CELL_H)
    if (frames.isEmpty()) return null
    return drawVideoStrip(frames)
  }

  private fun drawVideoStrip(frames: List<Pair<Float, Bitmap>>): Bitmap {
    val sheet = Bitmap.createBitmap(VIDEO_STRIP_CELL_W * frames.size, VIDEO_STRIP_CELL_H, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet).apply { drawColor(Color.BLACK) }
    frames.forEachIndexed { index, (timestampSec, bitmap) ->
      drawStripFrame(canvas, bitmap, index * VIDEO_STRIP_CELL_W, 0)
      drawStripBadge(canvas, (index * VIDEO_STRIP_CELL_W).toFloat(), 0f, timestampSec)
      bitmap.recycle()
    }
    return sheet
  }

  private fun drawStripFrame(canvas: Canvas, bitmap: Bitmap, x: Int, y: Int) {
    val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
    val dstAspect = VIDEO_STRIP_CELL_W.toFloat() / VIDEO_STRIP_CELL_H.toFloat()
    val src = if (srcAspect > dstAspect) {
      val cropW = (bitmap.height * dstAspect).toInt().coerceAtLeast(1)
      val left = ((bitmap.width - cropW) / 2).coerceAtLeast(0)
      Rect(left, 0, (left + cropW).coerceAtMost(bitmap.width), bitmap.height)
    } else {
      val cropH = (bitmap.width / dstAspect).toInt().coerceAtLeast(1)
      val top = ((bitmap.height - cropH) / 2).coerceAtLeast(0)
      Rect(0, top, bitmap.width, (top + cropH).coerceAtMost(bitmap.height))
    }
    val dst = Rect(x, y, x + VIDEO_STRIP_CELL_W, y + VIDEO_STRIP_CELL_H)
    canvas.drawBitmap(bitmap, src, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
  }

  private fun drawStripBadge(canvas: Canvas, x: Float, y: Float, timestampSec: Float) {
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 20, 20, 20) }
    val rect = RectF(x + 8f, y + 8f, x + 92f, y + 40f)
    canvas.drawRoundRect(rect, 14f, 14f, bg)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = 18f
      textAlign = Paint.Align.CENTER
    }
    val text = "${"%.1f".format(Locale.US, timestampSec)}s"
    val cy = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(text, rect.centerX(), cy, textPaint)
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
      speedFactor = request.speedHint.coerceIn(0.5f, 1.75f),
      kenBurnsZoom = request.kenBurnsIntensity.coerceIn(1.0f, 1.20f),
      cutReason = request.cutReason,
    )
  }

  companion object {
    private const val MAX_VISUAL_CANDIDATES = 4
    private const val CANDIDATE_CELL_W = 300
    private const val CANDIDATE_CELL_H = 420
    private const val VIDEO_STRIP_CELL_W = 220
    private const val VIDEO_STRIP_CELL_H = 300

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
      return TRANSITION_MAP[key] ?: if (order == 1) TransitionKind.FADE else TransitionKind.CUT
    }

    /**
     * Post-process: if 3+ consecutive shots share the same transition, break the
     * chain to keep visual variety. Pick CUT for short shots (< 2s) where a
     * fade would feel like dragging, otherwise FADE. Order-1 is left alone
     * since it's the opener.
     */
    fun enforceTransitionDiversity(shots: List<ShotSpec>): List<ShotSpec> {
      if (shots.size < 3) return shots
      val out = shots.toMutableList()
      for (i in 2 until out.size) {
        val a = out[i].transitionIn
        if (a == out[i - 1].transitionIn && a == out[i - 2].transitionIn) {
          val replacement = if (out[i].durationSec < 2f) TransitionKind.CUT else TransitionKind.FADE
          if (replacement != a) {
            out[i] = out[i].copy(transitionIn = replacement)
          }
        }
      }
      return out
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
