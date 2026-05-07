/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * step5_editor: for each ShotRequest, recall up to 8 candidates → ask Gemma 4
 * to pick one with rationale → assemble ShotSpec. Caller is responsible for
 * passing in pre-computed CLIP text embedding for the request's
 * visual_requirements (the editor lives downstream of the perception engine).
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import android.content.Context
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.MediaLoader
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.Recall
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ColorGrade
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
   * @param queryEmbedding CLIP text embedding of [request.visualRequirements]
   * @param previousShot brief description of the previously chosen shot, for continuity
   * @return chosen ShotSpec, or null if recall returned no candidates
   */
  suspend fun pickShot(
    order: Int,
    request: ShotRequest,
    eventAssets: List<Asset>,
    perceptions: Map<String, Perception>,
    queryEmbedding: FloatArray,
    excludedAssetIds: Set<String>,
    previousShot: String,
  ): ShotSpec? {
    val candidates = Recall.topK(request, eventAssets, perceptions, queryEmbedding, excludedAssetIds, k = 8)
    if (candidates.isEmpty()) return null
    if (candidates.size == 1) return composeSpec(order, request, candidates[0].asset, "only candidate")

    val thumbs = candidates.mapNotNull { MediaLoader.loadImage(context, it.asset, maxSide = 512) }
    val userMsg = """
当前 slot：role=${request.role}; mood=${request.moodTarget}; visual_req=${request.visualRequirements}
previous_shot_summary: $previousShot
请从 ${thumbs.size} 张候选中选 1 张（编号 1..${thumbs.size}）。
""".trimIndent()
    val raw = agent.ask(systemPrompt = PromptStrings.EDITOR_SYSTEM, userText = userMsg, images = thumbs)
    val obj = try { JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement)?.jsonObject } catch (_: Throwable) { null }
    val idx = obj?.get("chosen_index")?.jsonPrimitive?.intOrNull?.minus(1) ?: 0
    val rationale = obj?.get("rationale")?.jsonPrimitive?.contentOrNull.orEmpty()
    val chosen = candidates.getOrNull(idx) ?: candidates.first()
    return composeSpec(order, request, chosen.asset, rationale)
  }

  private fun composeSpec(order: Int, request: ShotRequest, asset: Asset, rationale: String): ShotSpec {
    val mediaType = asset.mediaType
    // For video / live photo: trim a window matching the requested duration when possible.
    val trim = if (mediaType != com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType.IMAGE
      && asset.durationMs > 0) {
      val durSec = asset.durationMs / 1000f
      val want = request.durationSec.coerceAtMost(durSec)
      val mid = durSec / 2f
      VideoTrim(startSec = (mid - want / 2f).coerceAtLeast(0f), endSec = (mid + want / 2f).coerceAtMost(durSec))
    } else null

    return ShotSpec(
      order = order,
      assetId = asset.id,
      mediaType = mediaType,
      durationSec = request.durationSec,
      kenBurns = request.kenBurnsHint,
      colorGrade = ColorGrade.NEUTRAL,
      caption = request.captionText,
      transitionIn = TRANSITION_MAP[request.transitionInHint] ?: TransitionKind.CUT,
      videoTrim = trim,
      rationale = rationale,
    )
  }

  companion object {
    private val TRANSITION_MAP = mapOf(
      "cut" to TransitionKind.CUT,
      "fade" to TransitionKind.FADE,
      "fadewhite" to TransitionKind.FADEWHITE,
      "zoomin" to TransitionKind.ZOOMIN,
      "smoothleft" to TransitionKind.SMOOTHLEFT,
      "smoothright" to TransitionKind.SMOOTHRIGHT,
    )
  }
}
