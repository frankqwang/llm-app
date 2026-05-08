/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Tests Milestones A + C iteration logic. Validates that:
 *   - QuickAction chip taps map to expected RenderOnlyPatch / GlobalRevision
 *   - applyRenderOnly mutates timeline (caption strip, color grade swap)
 *   - applyGlobalToDirector rescales blueprint durations + clamps to pace band
 *   - applyGlobalToTimeline strips captions + overrides per-shot color grade
 *   - describeChanges produces human-readable summaries
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.CaptionPolicy
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ColorGrade
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.DirectorBrief
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.FeedbackScope
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.GlobalRevision
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.IterationFeedback
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Pace
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.QuickAction
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.RenderOnlyPatch
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.RevisedRequest
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRequest
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRole
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.TransitionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IterationPlannerTest {

  private fun shot(order: Int, caption: String = "标题$order", grade: ColorGrade = ColorGrade.NEUTRAL) =
    ShotSpec(
      order = order,
      assetId = "asset-$order",
      mediaType = MediaType.IMAGE,
      durationSec = 2.5f,
      caption = caption,
      colorGrade = grade,
      transitionIn = TransitionKind.CUT,
    )

  private val baseTimeline = Timeline(
    eventId = "evt1",
    directorBriefRef = "",
    shots = listOf(shot(1), shot(2), shot(3)),
  )

  @Test fun removeCaptionsChip_producesNoneCaptionPolicy() {
    val fb = IterationPlanner.fromQuickActions(
      iterationId = "iter_002",
      baseTimelineVersion = 1,
      actions = listOf(QuickAction.REMOVE_CAPTIONS),
    )
    assertEquals(FeedbackScope.RENDER_ONLY, fb.parsedScope)
    assertNotNull(fb.parsedRenderPatch)
    assertEquals(CaptionPolicy.NONE, fb.parsedRenderPatch?.captionPolicy)
    assertNull(fb.parsedRenderPatch?.newColorGrade)
  }

  @Test fun changeColorGradeChip_picksNextInRotation() {
    val fb = IterationPlanner.fromQuickActions(
      iterationId = "iter_002",
      baseTimelineVersion = 1,
      actions = listOf(QuickAction.CHANGE_COLOR_GRADE),
      currentColorGrade = ColorGrade.NEUTRAL,
    )
    assertEquals(FeedbackScope.RENDER_ONLY, fb.parsedScope)
    // NEUTRAL is not in the cycle; rotateColorGrade returns the first element (WARM).
    assertEquals(ColorGrade.WARM, fb.parsedRenderPatch?.newColorGrade)
  }

  @Test fun colorGradeRotation_cyclesThroughCinematicOptions() {
    assertEquals(ColorGrade.CINEMATIC_TEAL_ORANGE, IterationPlanner.nextColorGrade(ColorGrade.WARM))
    assertEquals(ColorGrade.COOL, IterationPlanner.nextColorGrade(ColorGrade.CINEMATIC_TEAL_ORANGE))
    assertEquals(ColorGrade.WARM, IterationPlanner.nextColorGrade(ColorGrade.MUTED))   // wraps
  }

  @Test fun fasterChipProducesSnappyGlobalRevision() {
    val fb = IterationPlanner.fromQuickActions(
      iterationId = "iter_002",
      baseTimelineVersion = 1,
      actions = listOf(QuickAction.FASTER_OVERALL),
      currentPace = Pace.BALANCED,
    )
    assertEquals(FeedbackScope.GLOBAL, fb.parsedScope)
    assertNotNull(fb.parsedGlobal)
    assertEquals(Pace.SNAPPY, fb.parsedGlobal?.newPace)
    assertNotNull(fb.parsedGlobal?.newTargetDurationSec)
  }

  @Test fun slowerChipProducesLingeringGlobalRevision() {
    val fb = IterationPlanner.fromQuickActions(
      iterationId = "iter_002",
      baseTimelineVersion = 1,
      actions = listOf(QuickAction.SLOWER_OVERALL),
      currentPace = Pace.BALANCED,
    )
    assertEquals(FeedbackScope.GLOBAL, fb.parsedScope)
    assertEquals(Pace.LINGERING, fb.parsedGlobal?.newPace)
  }

  @Test fun fasterFromLingeringStepsToBalanced() {
    val fb = IterationPlanner.fromQuickActions(
      iterationId = "iter_002",
      baseTimelineVersion = 1,
      actions = listOf(QuickAction.FASTER_OVERALL),
      currentPace = Pace.LINGERING,
    )
    assertEquals(Pace.BALANCED, fb.parsedGlobal?.newPace)
  }

  @Test fun bothFasterAndSlowerCancelOut() {
    val fb = IterationPlanner.fromQuickActions(
      iterationId = "iter_002",
      baseTimelineVersion = 1,
      actions = listOf(QuickAction.FASTER_OVERALL, QuickAction.SLOWER_OVERALL),
    )
    // Contradictory pace chips ignored — falls back to RENDER_ONLY no-op.
    assertNull(fb.parsedGlobal)
    assertEquals(FeedbackScope.RENDER_ONLY, fb.parsedScope)
  }

  @Test fun mixedPaceAndCaptionChipsProduceMixed() {
    val fb = IterationPlanner.fromQuickActions(
      iterationId = "iter_002",
      baseTimelineVersion = 1,
      actions = listOf(QuickAction.FASTER_OVERALL, QuickAction.REMOVE_CAPTIONS),
      currentPace = Pace.BALANCED,
    )
    assertEquals(FeedbackScope.MIXED, fb.parsedScope)
    assertNotNull(fb.parsedGlobal)
    assertNotNull(fb.parsedRenderPatch)
    assertEquals(CaptionPolicy.NONE, fb.parsedRenderPatch?.captionPolicy)
  }

  @Test fun applyRenderOnly_stripsCaptionsWhenPolicyIsNone() {
    val patch = RenderOnlyPatch(captionPolicy = CaptionPolicy.NONE)
    val out = IterationPlanner.applyRenderOnly(baseTimeline, baseTone = "neutral", patch = patch)
    assertTrue(out.timeline.shots.all { it.caption.isEmpty() })
    assertEquals("neutral", out.effectiveTone)  // unchanged
    assertTrue(out.changeSummary.contains("去掉字幕"))
  }

  @Test fun applyRenderOnly_overridesEveryShotsColorGrade() {
    val patch = RenderOnlyPatch(newColorGrade = ColorGrade.CINEMATIC_TEAL_ORANGE)
    val out = IterationPlanner.applyRenderOnly(baseTimeline, baseTone = "neutral", patch = patch)
    assertTrue(out.timeline.shots.all { it.colorGrade == ColorGrade.CINEMATIC_TEAL_ORANGE })
    // Captions are preserved when policy is null.
    assertFalse(out.timeline.shots.any { it.caption.isEmpty() })
  }

  @Test fun applyRenderOnly_combinedPatchAffectsBothFields() {
    val patch = RenderOnlyPatch(
      captionPolicy = CaptionPolicy.NONE,
      newColorGrade = ColorGrade.WARM,
    )
    val out = IterationPlanner.applyRenderOnly(baseTimeline, "neutral", patch)
    assertTrue(out.timeline.shots.all { it.caption.isEmpty() && it.colorGrade == ColorGrade.WARM })
    assertTrue(out.changeSummary.contains("去掉字幕"))
    assertTrue(out.changeSummary.contains("调色"))
  }

  @Test fun applyRenderOnly_newBgmToneOverridesEffectiveTone() {
    val patch = RenderOnlyPatch(newBgmTone = "upbeat")
    val out = IterationPlanner.applyRenderOnly(baseTimeline, "neutral", patch)
    assertEquals("upbeat", out.effectiveTone)
    assertTrue(out.changeSummary.contains("BGM"))
  }

  // ---- applyGlobalToDirector ----

  private fun shotRequest(position: Int, durationSec: Float): ShotRequest =
    ShotRequest(
      position = position,
      role = ShotRole.ESTABLISHING,
      moodTarget = "中性",
      visualRequirements = "占位",
      durationSec = durationSec,
    )

  private fun director(durationSec: Float, blueprint: List<ShotRequest>): DirectorBrief =
    DirectorBrief(
      eventId = "evt1",
      title = "测试",
      tagline = null,
      targetDurationSec = durationSec,
      tone = "neutral",
      narrativeArc = listOf("开场", "高潮", "收尾"),
      shotBlueprint = blueprint,
    )

  @Test fun applyGlobalToDirector_rescalesShotDurationsProportionally() {
    val base = director(20f, listOf(shotRequest(1, 4f), shotRequest(2, 6f), shotRequest(3, 4f), shotRequest(4, 6f)))
    val out = IterationPlanner.applyGlobalToDirector(
      base,
      GlobalRevision(newTargetDurationSec = 30f, newPace = Pace.LINGERING),
    )
    // 30 falls in LINGERING band [22, 28], gets clamped to 28.
    assertEquals(28f, out.targetDurationSec, 0.01f)
    val rescale = 28f / 20f
    val expected = listOf(4f * rescale, 6f * rescale, 4f * rescale, 6f * rescale)
    out.shotBlueprint.zip(expected).forEach { (shot, want) ->
      assertEquals(want.coerceIn(0.4f, 8f), shot.durationSec, 0.01f)
    }
  }

  @Test fun applyGlobalToDirector_overridesToneAndColorGrade() {
    val base = director(20f, listOf(shotRequest(1, 4f), shotRequest(2, 4f)))
    val out = IterationPlanner.applyGlobalToDirector(
      base,
      GlobalRevision(newTone = "欢快", newColorGrade = ColorGrade.WARM),
    )
    assertEquals("欢快", out.tone)
    assertEquals(ColorGrade.WARM, out.colorGrade)
  }

  @Test fun applyGlobalToDirector_withoutChangesReturnsEquivalentBrief() {
    val base = director(20f, listOf(shotRequest(1, 4f)))
    val out = IterationPlanner.applyGlobalToDirector(base, GlobalRevision())
    assertEquals(base.targetDurationSec, out.targetDurationSec, 0.01f)
    assertEquals(base.tone, out.tone)
    assertEquals(base.colorGrade, out.colorGrade)
    assertEquals(base.shotBlueprint.first().durationSec, out.shotBlueprint.first().durationSec, 0.01f)
  }

  // ---- applyGlobalToTimeline ----

  @Test fun applyGlobalToTimeline_stripsCaptionsWhenPolicyIsNone() {
    val out = IterationPlanner.applyGlobalToTimeline(
      baseTimeline,
      GlobalRevision(captionPolicy = CaptionPolicy.NONE),
    )
    assertTrue(out.shots.all { it.caption.isEmpty() })
  }

  @Test fun applyGlobalToTimeline_overridesAllShotColorGrades() {
    val out = IterationPlanner.applyGlobalToTimeline(
      baseTimeline,
      GlobalRevision(newColorGrade = ColorGrade.VINTAGE),
    )
    assertTrue(out.shots.all { it.colorGrade == ColorGrade.VINTAGE })
  }

  @Test fun applyGlobalToTimeline_passesThroughWhenNoRenderOverrides() {
    val out = IterationPlanner.applyGlobalToTimeline(
      baseTimeline,
      GlobalRevision(newPace = Pace.SNAPPY),  // doesn't affect timeline directly
    )
    assertEquals(baseTimeline, out)
  }

  // ---- describeChanges ----

  @Test fun describeChanges_renderOnlyPatch() {
    val fb = IterationFeedback(
      iterationId = "iter_002",
      baseTimelineVersion = 1,
      parsedScope = FeedbackScope.RENDER_ONLY,
      parsedRenderPatch = RenderOnlyPatch(captionPolicy = CaptionPolicy.NONE, newColorGrade = ColorGrade.WARM),
    )
    val summary = IterationPlanner.describeChanges(fb)
    assertTrue(summary.contains("去字幕"))
    assertTrue(summary.contains("调色"))
  }

  @Test fun describeChanges_globalRevision() {
    val fb = IterationFeedback(
      iterationId = "iter_002",
      baseTimelineVersion = 1,
      parsedScope = FeedbackScope.GLOBAL,
      parsedGlobal = GlobalRevision(newTargetDurationSec = 18f, newPace = Pace.SNAPPY),
    )
    val summary = IterationPlanner.describeChanges(fb)
    assertTrue(summary.contains("时长"))
    assertTrue(summary.contains("节奏"))
  }

  @Test fun describeChanges_shotLevelLisesShotOrders() {
    val fb = IterationFeedback(
      iterationId = "iter_002",
      baseTimelineVersion = 1,
      parsedScope = FeedbackScope.SHOT_LEVEL,
      parsedRevisions = listOf(
        RevisedRequest(shotOrder = 3, patches = mapOf("duration_sec" to "2.0")),
        RevisedRequest(shotOrder = 5, patches = mapOf("caption_text" to "再见")),
      ),
    )
    val summary = IterationPlanner.describeChanges(fb)
    assertTrue(summary.contains("#3"))
    assertTrue(summary.contains("#5"))
  }
}
