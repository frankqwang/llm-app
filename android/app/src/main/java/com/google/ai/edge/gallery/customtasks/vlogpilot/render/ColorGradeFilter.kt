/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * 7 color grades implemented as ffmpeg `eq` / `colorbalance` / `curves` filter
 * snippets. Returns "" for NEUTRAL so callers can just drop it into a filter
 * chain unconditionally.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.render

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ColorGrade

object ColorGradeFilter {

  fun forGrade(grade: ColorGrade): String = when (grade) {
    ColorGrade.NEUTRAL -> ""
    ColorGrade.WARM -> "eq=saturation=1.10,colorbalance=rs=0.06:gs=0.02:bs=-0.05:rh=0.02"
    ColorGrade.COOL -> "eq=saturation=1.06,colorbalance=rs=-0.05:gs=0.01:bs=0.07"
    ColorGrade.VIBRANT -> "eq=saturation=1.30:contrast=1.08"
    ColorGrade.MUTED -> "eq=saturation=0.78:contrast=0.96"
    ColorGrade.CINEMATIC_TEAL_ORANGE ->
      "curves=preset=increase_contrast,colorbalance=rs=0.07:gs=-0.03:bs=-0.08:rh=-0.06:gh=0.02:bh=0.10,eq=saturation=1.15"
    ColorGrade.VINTAGE -> "curves=preset=darker,eq=saturation=0.70,colorbalance=rs=0.08:gs=0.04:bs=-0.04"
  }

  /** A faint film-grain noise + center vignette polish layer. Optional. */
  fun polishLayer(): String = "noise=alls=4:allf=t,vignette=PI/5"
}
