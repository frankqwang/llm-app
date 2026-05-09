/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Lightweight built-in templates. They are not visual themes; they are editing
 * grammar: slot order, pacing, subject policy, captions, and music tone.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.CaptionPolicy
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.EventMemory
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Pace
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRole
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventCandidateSnapshot
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions

internal enum class TemplateSubjectPolicy {
  PROTAGONIST,
  GROUP,
  ENVIRONMENT,
  ACTION,
  ANY,
  AVOID_REPEAT,
}

internal data class TemplateSlot(
  val position: Int,
  val role: ShotRole,
  val durationSec: Float,
  val moodTarget: String,
  val visualRequirements: String,
  val subjectPolicy: TemplateSubjectPolicy = TemplateSubjectPolicy.ANY,
  val transitionInHint: String = "",
  val kenBurnsHint: String = "",
  val captionHint: String = "",
)

internal data class VlogTemplate(
  val id: String,
  val label: String,
  val storyKeywords: Set<String>,
  val defaultPace: Pace,
  val bgmTone: String,
  val captionPolicy: CaptionPolicy = CaptionPolicy.DEFAULT,
  val slots: List<TemplateSlot>,
) {
  val targetDurationSec: Float get() = slots.sumOf { it.durationSec.toDouble() }.toFloat()
}

internal object TemplateCatalog {
  const val DEFAULT_TEMPLATE_ID = "daily_moment"

  val all: List<VlogTemplate> = listOf(
    VlogTemplate(
      id = "daily_moment",
      label = "日常瞬间",
      storyKeywords = setOf("日常", "城市", "街", "生活", "瞬间", "city", "street", "daily"),
      defaultPace = Pace.BALANCED,
      bgmTone = "neutral",
      slots = listOf(
        TemplateSlot(1, ShotRole.OPENING, 2.4f, "自然开场", "环境或地点建立，交代这一天在哪里", TemplateSubjectPolicy.ENVIRONMENT, "fade", "in", "这一天"),
        TemplateSlot(2, ShotRole.PORTRAIT, 2.8f, "亲近", "人物或物件细节，表情自然", TemplateSubjectPolicy.PROTAGONIST),
        TemplateSlot(3, ShotRole.ACTION, 2.6f, "轻快", "正在发生的小动作，避免静态重复", TemplateSubjectPolicy.ACTION),
        TemplateSlot(4, ShotRole.CLIMAX, 3.2f, "温暖高光", "最有故事感或情绪的瞬间", TemplateSubjectPolicy.AVOID_REPEAT, "crossfade", "in", "刚好被看见"),
        TemplateSlot(5, ShotRole.CLOSING, 2.5f, "余韵", "干净收尾，最好是远景或安静画面", TemplateSubjectPolicy.ENVIRONMENT, "fadewhite", "out"),
      ),
    ),
    VlogTemplate(
      id = "travel_memory",
      label = "旅行回忆",
      storyKeywords = setOf("旅行", "出游", "路上", "酒店", "景点", "户外", "travel", "trip", "beach", "hotel"),
      defaultPace = Pace.BALANCED,
      bgmTone = "travel",
      slots = listOf(
        TemplateSlot(1, ShotRole.ESTABLISHING, 2.6f, "抵达感", "地点/路牌/风景建立，明确出行感", TemplateSubjectPolicy.ENVIRONMENT, "fade", "in"),
        TemplateSlot(2, ShotRole.ACTION, 2.8f, "在路上", "移动、走路、车窗、游玩动作", TemplateSubjectPolicy.ACTION, "cut"),
        TemplateSlot(3, ShotRole.PORTRAIT, 2.8f, "同行者", "同行人物自然反应或背影", TemplateSubjectPolicy.GROUP),
        TemplateSlot(4, ShotRole.CLIMAX, 3.4f, "目的地高光", "最像旅行记忆的风景或动作高潮", TemplateSubjectPolicy.AVOID_REPEAT, "crossfade", "in", "到这里了"),
        TemplateSlot(5, ShotRole.CLOSING, 2.8f, "告别", "离开、远景或安静结尾", TemplateSubjectPolicy.ENVIRONMENT, "fadewhite", "out"),
      ),
    ),
    VlogTemplate(
      id = "food_record",
      label = "美食记录",
      storyKeywords = setOf("美食", "吃", "餐", "饭", "菜", "店", "restaurant", "food", "meal", "dinner", "lunch"),
      defaultPace = Pace.SNAPPY,
      bgmTone = "warm",
      slots = listOf(
        TemplateSlot(1, ShotRole.OPENING, 2.0f, "开胃", "店面、桌面或第一道菜", TemplateSubjectPolicy.ENVIRONMENT, "fade", "in"),
        TemplateSlot(2, ShotRole.ESTABLISHING, 2.4f, "细节", "菜品特写、摆盘、热气或颜色", TemplateSubjectPolicy.AVOID_REPEAT),
        TemplateSlot(3, ShotRole.ACTION, 2.3f, "动手", "夹菜、倒饮料、切开、上菜动作", TemplateSubjectPolicy.ACTION),
        TemplateSlot(4, ShotRole.PORTRAIT, 2.6f, "一起吃", "人物反应或同桌氛围", TemplateSubjectPolicy.GROUP),
        TemplateSlot(5, ShotRole.CLIMAX, 2.7f, "最好吃的一口", "最诱人的食物或笑点", TemplateSubjectPolicy.AVOID_REPEAT, "crossfade", "in", "这一口"),
      ),
    ),
    VlogTemplate(
      id = "people_together",
      label = "家人朋友",
      storyKeywords = setOf("家人", "朋友", "孩子", "人", "合照", "family", "friend", "kid", "people", "portrait"),
      defaultPace = Pace.LINGERING,
      bgmTone = "warm",
      slots = listOf(
        TemplateSlot(1, ShotRole.OPENING, 2.5f, "相聚", "人物同框或场景建立", TemplateSubjectPolicy.GROUP, "fade", "in"),
        TemplateSlot(2, ShotRole.PORTRAIT, 3.0f, "主角", "清楚自然的人脸或表情", TemplateSubjectPolicy.PROTAGONIST),
        TemplateSlot(3, ShotRole.ACTION, 2.8f, "互动", "拥抱、聊天、玩耍、举杯等互动", TemplateSubjectPolicy.ACTION),
        TemplateSlot(4, ShotRole.CLIMAX, 3.4f, "情绪峰值", "笑容、亲密或最有记忆点的画面", TemplateSubjectPolicy.GROUP, "crossfade", "in", "在一起"),
        TemplateSlot(5, ShotRole.CLOSING, 3.0f, "留住", "合照、背影或安静收尾", TemplateSubjectPolicy.GROUP, "fadewhite", "out"),
      ),
    ),
    VlogTemplate(
      id = "pet_animal",
      label = "宠物/动物",
      storyKeywords = setOf("动物", "宠物", "猫", "狗", "熊猫", "zoo", "animal", "pet", "cat", "dog"),
      defaultPace = Pace.SNAPPY,
      bgmTone = "playful",
      slots = listOf(
        TemplateSlot(1, ShotRole.OPENING, 2.0f, "出场", "动物或宠物第一次清楚出现", TemplateSubjectPolicy.PROTAGONIST, "fade", "in"),
        TemplateSlot(2, ShotRole.ACTION, 2.3f, "可爱动作", "走动、吃东西、玩耍或互动", TemplateSubjectPolicy.ACTION),
        TemplateSlot(3, ShotRole.PORTRAIT, 2.4f, "特写", "动物表情或近景细节", TemplateSubjectPolicy.PROTAGONIST),
        TemplateSlot(4, ShotRole.CLIMAX, 2.8f, "最好笑/最萌", "最有趣或最可爱的动作高光", TemplateSubjectPolicy.AVOID_REPEAT, "crossfade", "in", "太可爱了"),
        TemplateSlot(5, ShotRole.CLOSING, 2.2f, "收尾", "离开、回头或安静画面", TemplateSubjectPolicy.PROTAGONIST, "fadewhite", "out"),
      ),
    ),
  )

  fun byId(id: String?): VlogTemplate =
    all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_TEMPLATE_ID }

  fun selectForCandidate(candidate: EventCandidateSnapshot): VlogTemplate =
    selectFromText(candidate.scoutEventType + " " + candidate.scoutSummary + " " + candidate.reasons.joinToString(" "))

  fun selectForMemory(memory: EventMemory, userText: String = ""): VlogTemplate =
    selectFromText(
      listOf(
        userText,
        memory.storylineSummary,
        memory.emotionalArc,
        memory.visualStyleSignals,
        memory.charactersObserved.joinToString(" "),
      ).joinToString(" "),
    )

  fun inferForDecision(d: EventDecisions): VlogTemplate =
    d.memory?.let { selectForMemory(it) }
      ?: selectFromText(d.director?.tone.orEmpty() + " " + d.director?.title.orEmpty())

  fun selectFromText(text: String): VlogTemplate {
    val normalized = text.lowercase()
    return all
      .map { template -> template to template.storyKeywords.count { it.lowercase() in normalized } }
      .maxByOrNull { it.second }
      ?.takeIf { it.second > 0 }
      ?.first
      ?: byId(DEFAULT_TEMPLATE_ID)
  }
}
