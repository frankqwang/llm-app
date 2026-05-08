/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Tests Milestone A's RENDER_ONLY iteration path. Validates that:
 *   - QuickAction chip taps map to the expected RenderOnlyPatch
 *   - applyRenderOnly correctly mutates the timeline (caption strip, color grade swap)
 *   - GLOBAL-scope chips (FASTER/SLOWER) produce a no-op patch in Milestone A
 *   - changeSummary is human-readable and reflects what changed
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.CaptionPolicy
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ColorGrade
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.FeedbackScope
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.QuickAction
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.RenderOnlyPatch
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

  @Test fun globalChipsAreNoOpInMilestoneA() {
    val fb = IterationPlanner.fromQuickActions(
      iterationId = "iter_002",
      baseTimelineVersion = 1,
      actions = listOf(QuickAction.FASTER_OVERALL, QuickAction.SLOWER_OVERALL),
    )
    // No RENDER_ONLY patch produced — these are GLOBAL-scope chips not yet wired.
    assertNull(fb.parsedRenderPatch)
    assertNull(fb.parsedGlobal)
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
}
