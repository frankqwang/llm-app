/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Replaces the never-shipped CLIP / YOLO / face-embedder small-model stack
 * with a single Gemma 4 VLM call per asset. Produces semantic tags that the
 * Editor recall + agents can reason about much more richly than CLIP cosine
 * over a 512-d embedding ever could.
 *
 * Cost: ~3-5s/asset on Dimensity 9400 GPU + MTP. Per-asset Perception is
 * JSON-cached, so subsequent runs over the same album are essentially free.
 *
 * Robustness: any malformed JSON or LLM hiccup falls back to an empty VlmTags
 * — the orchestrator continues without the asset's semantic tags rather than
 * dropping the asset entirely. Recall.kt scoring degrades gracefully when
 * tags are absent.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import android.graphics.Bitmap
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.VideoInsight
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.VlmTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class VlmAnnotator(private val agent: AgentRuntime) {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  data class Annotation(
    val tags: VlmTags = VlmTags(),
    val videoInsight: VideoInsight = VideoInsight(),
  ) {
    fun hasSignal(): Boolean =
      tags.scene.isNotBlank() || tags.action.isNotBlank() || tags.salient.isNotBlank() || videoInsight.summary.isNotBlank()
  }

  suspend fun annotate(thumbnail: Bitmap, hintMediaType: String = "image"): Annotation {
    val raw = agent.ask(
      systemPrompt = PromptStrings.VLM_IMAGE_SYSTEM,
      userText = "媒体类型: $hintMediaType。请输出 VlmTags JSON。",
      images = listOf(thumbnail),
      label = "vlm_image",
    )
    val obj = try {
      JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement)?.jsonObject
    } catch (_: Throwable) {
      null
    } ?: return Annotation()

    return Annotation(tags = parseTags(obj))
  }

  suspend fun annotateVideo(frameSheet: Bitmap, frameTimestampsSec: List<Float>, hintMediaType: String = "video"): Annotation {
    val timeline = frameTimestampsSec.withIndex().joinToString(", ") { (idx, sec) -> "${idx + 1}=${"%.1f".format(sec)}s" }
    val raw = agent.ask(
      systemPrompt = PromptStrings.VLM_VIDEO_SYSTEM,
      userText = "媒体类型: $hintMediaType。帧编号和时间戳: $timeline。请输出 Video VlmTags JSON。",
      images = listOf(frameSheet),
      label = "vlm_video",
    )
    val obj = try {
      JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement)?.jsonObject
    } catch (_: Throwable) {
      null
    } ?: return Annotation(videoInsight = VideoInsight(frameTimestampsSec = frameTimestampsSec))

    val bestIdx = obj["best_moment_index"]?.jsonPrimitive?.intOrNull
      ?.coerceIn(1, frameTimestampsSec.size)
      ?: 0
    val bestSec = obj["best_moment_sec"]?.jsonPrimitive?.floatOrNull
      ?: frameTimestampsSec.getOrNull(bestIdx - 1)
      ?: 0f
    val badIndices = obj["bad_moment_indices"]?.jsonArray
      ?.mapNotNull { it.jsonPrimitive.intOrNull }
      ?.filter { it in 1..frameTimestampsSec.size }
      ?.distinct()
      ?.take(3)
      ?: emptyList()
    // best_moment_window: [start, end] (1-based, inclusive). Editor.expandWindows uses
    // this to seed candidate trim windows that span the model's identified peak action,
    // not just a single point.
    val windowPair = obj["best_moment_window"]?.jsonArray
      ?.mapNotNull { it.jsonPrimitive.intOrNull }
      ?.filter { it in 1..frameTimestampsSec.size }
      ?.take(2)
    val (winStart, winEnd) = when {
      windowPair == null || windowPair.size < 2 -> bestIdx to bestIdx
      else -> {
        val a = minOf(windowPair[0], windowPair[1])
        val b = maxOf(windowPair[0], windowPair[1])
        a to b
      }
    }

    return Annotation(
      tags = parseTags(obj),
      videoInsight = VideoInsight(
        frameTimestampsSec = frameTimestampsSec,
        summary = obj["video_summary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        actionArc = obj["action_arc"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        bestMomentIndex = bestIdx,
        bestMomentSec = bestSec,
        bestMomentWindowStart = winStart,
        bestMomentWindowEnd = winEnd,
        badMomentIndices = badIndices,
      ),
    )
  }

  private fun parseTags(obj: JsonObject): VlmTags =
    VlmTags(
      scene = obj["scene"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      subjects = obj["subjects"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
      action = obj["action"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      mood = obj["mood"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      timeFeel = obj["time_feel"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      salient = obj["salient"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      narrativeRoleHint = obj["narrative_role_hint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
}
