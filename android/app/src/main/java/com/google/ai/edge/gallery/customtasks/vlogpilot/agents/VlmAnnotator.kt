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
      systemPrompt = SYSTEM_PROMPT,
      userText = "媒体类型: $hintMediaType。请输出 VlmTags JSON。",
      images = listOf(thumbnail),
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
      systemPrompt = VIDEO_SYSTEM_PROMPT,
      userText = "媒体类型: $hintMediaType。帧编号和时间戳: $timeline。请输出 Video VlmTags JSON。",
      images = listOf(frameSheet),
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
      ?: emptyList()

    return Annotation(
      tags = parseTags(obj),
      videoInsight = VideoInsight(
        frameTimestampsSec = frameTimestampsSec,
        summary = obj["video_summary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        actionArc = obj["action_arc"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        bestMomentIndex = bestIdx,
        bestMomentSec = bestSec,
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

  companion object {
    val SYSTEM_PROMPT = """
你是一名 vlog 剪辑师的视觉助手，正在为相册里每一张素材打标签。我会给你 1 张缩略图，请仔细看，然后输出严格 JSON：
{
  "scene": "<=20字，这张图所在的场景（户外烧烤、清晨咖啡馆、卧室自拍、雨夜街道...）",
  "subjects": [<=4 个，图里出现的主要主体（人/物），用最简短的视觉特征；图里没人没物就给 []>],
  "action": "<=15字，正在发生的动作（举杯、凝视、行走、静止...）；如果是静态画面就描述状态",
  "mood": "<=15字，整体情绪（热闹放松、宁静沉思、紧张兴奋...）",
  "time_feel": "<=10字，时间感（清晨/傍晚/深夜/正午）；不确定就空字符串",
  "salient": "<=40字，这张图作为 vlog 镜头最值得选的细节（光线、表情、构图、动作的瞬间感）",
  "narrative_role_hint": "<opening / establishing / portrait / action / climax / transition / closing 之一；不明确就空字符串>"
}

硬性规则：
1. 只描述图里实际出现的内容，不要脑补图外的情节
2. 不要照抄 schema 里的 placeholder——所有字段都要扣实际看到的画面
3. 不要 markdown，不要解释，直接输出 JSON
4. subjects 要简短（每项 <=10字）
""".trimIndent()

    val VIDEO_SYSTEM_PROMPT = """
你是一名 vlog 剪辑师的视觉助手，正在为一个视频素材做快速浏览。我会给你一张 1..N 编号的视频帧网格，每个编号旁边有时间戳。请把这些帧当成同一段视频的时间采样，而不是多张独立照片。

输出严格 JSON：
{
  "scene": "<=20字，这段视频所在的场景>",
  "subjects": [<=4 个，视频里的主要主体，用最短视觉特征],
  "action": "<=15字，这段视频的主要动作>",
  "mood": "<=15字，整体情绪>",
  "time_feel": "<=10字，时间感；不确定就空字符串",
  "salient": "<=40字，作为 vlog 镜头最值得选的瞬间/细节>",
  "narrative_role_hint": "<opening / establishing / portrait / action / climax / transition / closing 之一；不明确就空字符串>",
  "video_summary": "<=60字，概括这段视频从前到后发生了什么>",
  "action_arc": "<=50字，描述动作或情绪随时间的变化；如果变化不明显就写静态/平稳>",
  "best_moment_index": <1..N 的整数，最适合剪进 vlog 的那一帧编号>,
  "bad_moment_indices": [<1..N 中明显糊、遮挡、无意义或不适合入片的编号>]
}

硬性规则：
1. 只描述帧里实际出现的内容，不要脑补图外情节
2. best_moment_index 必须是实际存在的编号
3. bad_moment_indices 只列明显不该选的帧，不确定就 []
4. 不要 markdown，不要解释，直接输出 JSON
""".trimIndent()
  }
}
