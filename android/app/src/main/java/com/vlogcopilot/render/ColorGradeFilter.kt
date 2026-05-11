/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * 7 color grades implemented as ffmpeg `eq` / `colorbalance` / `curves` filter
 * snippets. Returns "" for NEUTRAL so callers can just drop it into a filter
 * chain unconditionally.
 */
package com.vlogcopilot.render

import com.vlogcopilot.schemas.ColorGrade

object ColorGradeFilter {

  fun forGrade(grade: ColorGrade): String = when (grade) {
    ColorGrade.NEUTRAL -> ""
    ColorGrade.WARM -> "eq=saturation=1.06,colorbalance=rs=0.035:gs=0.01:bs=-0.025:rh=0.01"
    ColorGrade.COOL -> "eq=saturation=1.03,colorbalance=rs=-0.025:gs=0.005:bs=0.035"
    ColorGrade.VIBRANT -> "eq=saturation=1.14:contrast=1.035"
    ColorGrade.MUTED -> "eq=saturation=0.86:contrast=0.985"
    ColorGrade.CINEMATIC_TEAL_ORANGE ->
      "curves=preset=increase_contrast,colorbalance=rs=0.035:gs=-0.015:bs=-0.04:rh=-0.03:gh=0.01:bh=0.05,eq=saturation=1.06"
    ColorGrade.VINTAGE -> "eq=saturation=0.82:contrast=0.98,colorbalance=rs=0.04:gs=0.02:bs=-0.02"
  }

  /** A light finishing pass; deliberately restrained to avoid a template/filter look. */
  fun polishLayer(): String = "eq=contrast=1.015:saturation=1.015"
}
