/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Routes an IterationFeedback to the cheapest pipeline stage that can satisfy
 * it, and provides the per-scope timeline transforms.
 *
 * Milestone A only implements RENDER_ONLY: the user sees a chip-only feedback
 * sheet, the client maps each chip to a deterministic patch, and the
 * orchestrator re-renders without booting Gemma. Milestone C adds parseFeedback
 * (natural-language → revisions/global) and SHOT_LEVEL / GLOBAL paths.
 *
 * Cost ladder:
 *   RENDER_ONLY  ~30s   no agent calls
 *   SHOT_LEVEL   ~30-60s  per-revision Editor pickShot
 *   GLOBAL       ~3-5min  Director + Editor full rebuild (+ optional Critic)
 *   MIXED        GLOBAL then SHOT_LEVEL
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.CaptionPolicy
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ColorGrade
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.FeedbackScope
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.IterationFeedback
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.QuickAction
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.RenderOnlyPatch
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline

object IterationPlanner {

  /** Outcome of applying a feedback to a base timeline + tone. */
  data class RenderInput(
    val timeline: Timeline,
    val effectiveTone: String,
    val changeSummary: String,
  )

  /**
   * Maps the user's QuickAction chip taps to a fully-populated IterationFeedback
   * with parsedScope + parsedRenderPatch / parsedGlobal pre-filled. Used in
   * Milestone A as the only path (no LLM); in Milestone C this becomes a
   * client-side prefill that the LLM-based parser can override.
   *
   * `rotateColorGrade` is the function that picks the next grade given the
   * current one; we let the orchestrator inject it so the planner stays pure.
   */
  fun fromQuickActions(
    iterationId: String,
    baseTimelineVersion: Int,
    actions: List<QuickAction>,
    targetedShotOrders: List<Int> = emptyList(),
    userText: String = "",
    currentColorGrade: ColorGrade = ColorGrade.NEUTRAL,
    rotateColorGrade: (ColorGrade) -> ColorGrade = ::nextColorGrade,
  ): IterationFeedback {
    val renderPatch = buildRenderPatch(actions, currentColorGrade, rotateColorGrade)
    // FASTER_OVERALL / SLOWER_OVERALL would map to GlobalRevision but Milestone A
    // does not implement GLOBAL — those chips are surfaced in Milestone C.
    val scope = when {
      renderPatch != null -> FeedbackScope.RENDER_ONLY
      else                -> FeedbackScope.RENDER_ONLY  // no-op fallback
    }
    return IterationFeedback(
      iterationId = iterationId,
      baseTimelineVersion = baseTimelineVersion,
      userText = userText,
      targetedShotOrders = targetedShotOrders,
      quickActions = actions,
      parsedScope = scope,
      parsedRenderPatch = renderPatch,
      createdAtMs = System.currentTimeMillis(),
    )
  }

  private fun buildRenderPatch(
    actions: List<QuickAction>,
    currentColorGrade: ColorGrade,
    rotate: (ColorGrade) -> ColorGrade,
  ): RenderOnlyPatch? {
    if (actions.isEmpty()) return null
    var captionPolicy: CaptionPolicy? = null
    var colorGrade: ColorGrade? = null
    for (a in actions) {
      when (a) {
        QuickAction.REMOVE_CAPTIONS    -> captionPolicy = CaptionPolicy.NONE
        QuickAction.CHANGE_COLOR_GRADE -> colorGrade = rotate(currentColorGrade)
        // GLOBAL-scope chips are not handled here.
        QuickAction.FASTER_OVERALL,
        QuickAction.SLOWER_OVERALL     -> Unit
      }
    }
    if (captionPolicy == null && colorGrade == null) return null
    return RenderOnlyPatch(newColorGrade = colorGrade, captionPolicy = captionPolicy)
  }

  /**
   * Apply a RENDER_ONLY patch to a base timeline. Returns the patched timeline
   * + the tone string the renderer should use (BGM selection driver). The
   * caller is responsible for calling renderer.render() with the result.
   *
   * Caption stripping is implemented as setting each ShotSpec.caption to "" —
   * CaptionFilter already returns "" for blank text, so this naturally skips
   * drawtext. ColorGrade overrides every shot's per-shot grade.
   */
  fun applyRenderOnly(
    base: Timeline,
    baseTone: String,
    patch: RenderOnlyPatch,
  ): RenderInput {
    val changes = mutableListOf<String>()
    val shots = base.shots.map { shot ->
      var s = shot
      if (patch.captionPolicy == CaptionPolicy.NONE) {
        s = s.copy(caption = "")
      }
      if (patch.newColorGrade != null) {
        s = s.copy(colorGrade = patch.newColorGrade)
      }
      s
    }
    if (patch.captionPolicy == CaptionPolicy.NONE) changes += "去掉字幕"
    if (patch.newColorGrade != null) changes += "调色 → ${patch.newColorGrade.name.lowercase()}"
    val tone = patch.newBgmTone?.takeIf { it.isNotBlank() } ?: baseTone
    if (patch.newBgmTone != null) changes += "换 BGM"
    return RenderInput(
      timeline = base.copy(shots = shots),
      effectiveTone = tone,
      changeSummary = if (changes.isEmpty()) "无改动" else changes.joinToString(" / "),
    )
  }

  /**
   * Default ColorGrade rotation order — skips NEUTRAL so the user always sees
   * a visible change when they tap "换调色". Cycles through cinematic options.
   */
  fun nextColorGrade(current: ColorGrade): ColorGrade {
    val cycle = listOf(
      ColorGrade.WARM,
      ColorGrade.CINEMATIC_TEAL_ORANGE,
      ColorGrade.COOL,
      ColorGrade.VIBRANT,
      ColorGrade.VINTAGE,
      ColorGrade.MUTED,
    )
    val idx = cycle.indexOf(current)
    return if (idx < 0) cycle[0] else cycle[(idx + 1) % cycle.size]
  }
}
