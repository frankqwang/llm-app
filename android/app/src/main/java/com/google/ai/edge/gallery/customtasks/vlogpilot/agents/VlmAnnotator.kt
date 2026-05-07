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
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.VlmTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class VlmAnnotator(private val agent: AgentRuntime) {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  suspend fun annotate(thumbnail: Bitmap, hintMediaType: String = "image"): VlmTags {
    val raw = agent.ask(
      systemPrompt = SYSTEM_PROMPT,
      userText = "媒体类型: $hintMediaType。请输出 VlmTags JSON。",
      images = listOf(thumbnail),
    )
    val obj = try {
      JsonExtractor.firstObject(raw)?.let(json::parseToJsonElement)?.jsonObject
    } catch (_: Throwable) {
      null
    } ?: return VlmTags()

    return VlmTags(
      scene = obj["scene"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      subjects = obj["subjects"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
      action = obj["action"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      mood = obj["mood"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      timeFeel = obj["time_feel"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      salient = obj["salient"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      narrativeRoleHint = obj["narrative_role_hint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
  }

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
  }
}
