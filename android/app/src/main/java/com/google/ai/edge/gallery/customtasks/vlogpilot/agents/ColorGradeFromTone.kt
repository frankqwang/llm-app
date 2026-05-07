/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Map the director's free-text tone (e.g. "温馨日常", "城市夜跑") onto one of the
 * concrete ColorGrade enum values the renderer understands. Pure heuristic —
 * Gemma 4 was leaving colorGrade unset, so the editor picks the closest grade
 * by keyword match. NEUTRAL is the safe default when nothing matches.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ColorGrade

object ColorGradeFromTone {

  private val RULES: List<Pair<List<String>, ColorGrade>> = listOf(
    listOf("温馨", "温暖", "夕阳", "黄昏", "怀旧", "家", "亲情", "warm") to ColorGrade.WARM,
    listOf("清晨", "海边", "蓝调", "冷静", "雨", "夜", "城市", "cool") to ColorGrade.COOL,
    listOf("活力", "鲜艳", "节庆", "派对", "聚会", "童年", "vivid", "vibrant") to ColorGrade.VIBRANT,
    listOf("文艺", "宁静", "禅", "muted", "黑白") to ColorGrade.MUTED,
    listOf("电影", "cinematic", "氛围", "戏剧", "震撼") to ColorGrade.CINEMATIC_TEAL_ORANGE,
    listOf("复古", "胶片", "vintage", "怀旧胶片") to ColorGrade.VINTAGE,
  )

  fun pick(tone: String): ColorGrade {
    if (tone.isBlank()) return ColorGrade.NEUTRAL
    val lc = tone.lowercase()
    for ((keywords, grade) in RULES) {
      if (keywords.any { lc.contains(it) }) return grade
    }
    return ColorGrade.NEUTRAL
  }
}
