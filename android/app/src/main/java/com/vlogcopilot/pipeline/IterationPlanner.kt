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
package com.vlogcopilot.pipeline

import com.vlogcopilot.schemas.CaptionPolicy
import com.vlogcopilot.schemas.ColorGrade
import com.vlogcopilot.schemas.DirectorBrief
import com.vlogcopilot.schemas.FeedbackScope
import com.vlogcopilot.schemas.GlobalRevision
import com.vlogcopilot.schemas.IterationFeedback
import com.vlogcopilot.schemas.Pace
import com.vlogcopilot.schemas.QuickAction
import com.vlogcopilot.schemas.RenderOnlyPatch
import com.vlogcopilot.schemas.Timeline

object IterationPlanner {

  /** Outcome of applying a feedback to a base timeline + tone. */
  data class RenderInput(
    val timeline: Timeline,
    val effectiveTone: String,
    val changeSummary: String,
  )

  /**
   * Maps the user's QuickAction chip taps to a fully-populated IterationFeedback
   * with parsedScope + parsedRenderPatch + parsedGlobal pre-filled. Used as
   * the LLM-free path; the LLM-based parseFeedback merges its output on top.
   *
   * `rotateColorGrade` is the function that picks the next grade given the
   * current one; we let the orchestrator inject it so the planner stays pure.
   *
   * Pace chips (FASTER/SLOWER) produce a GlobalRevision based on the current
   * Pace — we step one notch up/down through SNAPPY → BALANCED → LINGERING
   * with the new target_duration_sec falling at the band's midpoint. This is
   * predictable enough that "更快" reliably means snappier, even without the LLM.
   */
  fun fromQuickActions(
    iterationId: String,
    baseTimelineVersion: Int,
    actions: List<QuickAction>,
    targetedShotOrders: List<Int> = emptyList(),
    userText: String = "",
    currentColorGrade: ColorGrade = ColorGrade.NEUTRAL,
    currentPace: Pace = Pace.BALANCED,
    rotateColorGrade: (ColorGrade) -> ColorGrade = ::nextColorGrade,
  ): IterationFeedback {
    val renderPatch = buildRenderPatch(actions, currentColorGrade, rotateColorGrade)
    val global = buildGlobalRevision(actions, currentPace)
    val scope = when {
      global != null && renderPatch != null -> FeedbackScope.MIXED
      global != null                        -> FeedbackScope.GLOBAL
      else                                  -> FeedbackScope.RENDER_ONLY
    }
    return IterationFeedback(
      iterationId = iterationId,
      baseTimelineVersion = baseTimelineVersion,
      userText = userText,
      targetedShotOrders = targetedShotOrders,
      quickActions = actions,
      parsedScope = scope,
      parsedRenderPatch = renderPatch,
      parsedGlobal = global,
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
        // GLOBAL-scope chips handled by buildGlobalRevision.
        QuickAction.FASTER_OVERALL,
        QuickAction.SLOWER_OVERALL     -> Unit
      }
    }
    if (captionPolicy == null && colorGrade == null) return null
    return RenderOnlyPatch(newColorGrade = colorGrade, captionPolicy = captionPolicy)
  }

  private fun buildGlobalRevision(
    actions: List<QuickAction>,
    currentPace: Pace,
  ): GlobalRevision? {
    val faster = QuickAction.FASTER_OVERALL in actions
    val slower = QuickAction.SLOWER_OVERALL in actions
    if (!faster && !slower) return null
    if (faster && slower) return null  // contradictory; ignore both
    val newPace = when {
      faster && currentPace == Pace.LINGERING -> Pace.BALANCED
      faster                                  -> Pace.SNAPPY
      slower && currentPace == Pace.SNAPPY    -> Pace.BALANCED
      else                                    -> Pace.LINGERING
    }
    val newDuration = when (newPace) {
      Pace.SNAPPY -> 17f
      Pace.BALANCED -> 20f
      Pace.LINGERING -> 25f
    }
    return GlobalRevision(
      newTargetDurationSec = newDuration,
      newPace = newPace,
    )
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
   * Patch a DirectorBrief to reflect the user's GlobalRevision. Mirrors the
   * UserBrief patching path in PipelineOrchestrator, but with the iteration-
   * focused GlobalRevision shape:
   *   - new_target_duration_sec → clamped to the (possibly new) pace band,
   *     and per-shot durations are proportionally rescaled
   *   - new_pace → drives the duration band even when LLM didn't pick a duration
   *   - new_tone / new_color_grade → straight overwrite when set
   *
   * The audiencePace parameter defaults to the value present in the brief's
   * blueprint behavior; pass the AudienceBrief.pace if you want stricter
   * banding. Caller is responsible for not double-applying audience pace
   * overrides — this helper assumes audiencePace already reflects user intent.
   */
  fun applyGlobalToDirector(
    base: DirectorBrief,
    global: GlobalRevision,
    audiencePace: Pace = global.newPace ?: Pace.BALANCED,
  ): DirectorBrief {
    val effectivePace = global.newPace ?: audiencePace
    val rawDuration = global.newTargetDurationSec ?: base.targetDurationSec
    val newDuration = clampToPaceBand(rawDuration, effectivePace)
    val rescale = if (base.targetDurationSec > 0f && newDuration != base.targetDurationSec) {
      newDuration / base.targetDurationSec
    } else 1f
    val rescaledBlueprint = if (rescale != 1f) {
      base.shotBlueprint.map { it.copy(durationSec = (it.durationSec * rescale).coerceIn(0.4f, 8f)) }
    } else {
      base.shotBlueprint
    }
    return base.copy(
      targetDurationSec = newDuration,
      tone = global.newTone?.takeIf { it.isNotBlank() } ?: base.tone,
      colorGrade = global.newColorGrade ?: base.colorGrade,
      shotBlueprint = rescaledBlueprint,
    )
  }

  /**
   * After a GLOBAL re-build produces a fresh Timeline (via Editor), apply the
   * caption-policy / per-shot color overrides that come along with the global
   * change. Symmetric counterpart to applyRenderOnly but for GLOBAL flow's
   * post-Editor leg.
   */
  fun applyGlobalToTimeline(base: Timeline, global: GlobalRevision): Timeline {
    val stripCaptions = global.captionPolicy == CaptionPolicy.NONE
    val overrideGrade = global.newColorGrade
    if (!stripCaptions && overrideGrade == null) return base
    return base.copy(
      shots = base.shots.map { shot ->
        var s = shot
        if (stripCaptions) s = s.copy(caption = "")
        if (overrideGrade != null) s = s.copy(colorGrade = overrideGrade)
        s
      },
    )
  }

  /** Clamps a duration into the band associated with a Pace. Mirrors
   *  DirectorAgent.clampToPaceBand. */
  private fun clampToPaceBand(rawSec: Float, pace: Pace): Float = when (pace) {
    Pace.SNAPPY -> rawSec.coerceIn(15f, 18f)
    Pace.BALANCED -> rawSec.coerceIn(18f, 22f)
    Pace.LINGERING -> rawSec.coerceIn(22f, 28f)
  }

  /**
   * Builds a one-line human-readable summary of what the iteration changed.
   * Used for IterationSummary.changeSummary so users can see a tooltip on the
   * 上一版 arrow without opening the JSON.
   */
  fun describeChanges(feedback: IterationFeedback): String {
    val parts = mutableListOf<String>()
    feedback.parsedRenderPatch?.let { p ->
      if (p.captionPolicy == CaptionPolicy.NONE) parts += "去字幕"
      if (p.newColorGrade != null) parts += "调色 → ${p.newColorGrade.name.lowercase()}"
      if (p.newBgmTone != null) parts += "换 BGM"
    }
    feedback.parsedGlobal?.let { g ->
      if (g.newTargetDurationSec != null) parts += "时长 → ${g.newTargetDurationSec.toInt()}s"
      if (g.newPace != null) parts += "节奏 → ${g.newPace.name.lowercase()}"
      if (g.newTone != null) parts += "调性 → ${g.newTone}"
      if (g.newColorGrade != null) parts += "调色 → ${g.newColorGrade.name.lowercase()}"
    }
    if (feedback.parsedRevisions.isNotEmpty()) {
      val orders = feedback.parsedRevisions.map { "#${it.shotOrder}" }
      parts += "改了 ${orders.joinToString(",")}"
    }
    return if (parts.isEmpty()) "无改动" else parts.joinToString(" / ")
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
