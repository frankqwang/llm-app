/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Tests for EditorAgent companion-object helpers. Currently focused on
 * enforceTransitionDiversity — the post-process that prevents a Director
 * from emitting 3+ identical transitions in a row, which makes the cut
 * feel monotone even when individual shots are well chosen.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.TransitionKind
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorAgentTest {

  private fun spec(order: Int, transition: TransitionKind, durationSec: Float = 3.0f): ShotSpec =
    ShotSpec(
      order = order,
      assetId = "a$order",
      mediaType = MediaType.IMAGE,
      durationSec = durationSec,
      transitionIn = transition,
    )

  @Test fun `noChangeWhenAllDifferent`() {
    val input = listOf(
      spec(1, TransitionKind.FADE),
      spec(2, TransitionKind.CROSSFADE),
      spec(3, TransitionKind.SLIDELEFT),
      spec(4, TransitionKind.ZOOMIN),
    )
    val out = EditorAgent.enforceTransitionDiversity(input)
    assertEquals(input.map { it.transitionIn }, out.map { it.transitionIn })
  }

  @Test fun `noChangeWhenJustTwoConsecutive`() {
    val input = listOf(
      spec(1, TransitionKind.CROSSFADE),
      spec(2, TransitionKind.CROSSFADE),
      spec(3, TransitionKind.FADE),
    )
    val out = EditorAgent.enforceTransitionDiversity(input)
    assertEquals(input.map { it.transitionIn }, out.map { it.transitionIn })
  }

  @Test fun `breaksThreeConsecutiveSameWithFadeForLongShots`() {
    val input = listOf(
      spec(1, TransitionKind.CROSSFADE, 3.0f),
      spec(2, TransitionKind.CROSSFADE, 3.0f),
      spec(3, TransitionKind.CROSSFADE, 3.0f),
      spec(4, TransitionKind.FADE, 3.0f),
    )
    val out = EditorAgent.enforceTransitionDiversity(input)
    assertEquals(TransitionKind.CROSSFADE, out[0].transitionIn)
    assertEquals(TransitionKind.CROSSFADE, out[1].transitionIn)
    assertEquals(TransitionKind.FADE, out[2].transitionIn)
    assertEquals(TransitionKind.FADE, out[3].transitionIn)
  }

  @Test fun `breaksThreeConsecutiveSameWithCutForShortShots`() {
    val input = listOf(
      spec(1, TransitionKind.SLIDELEFT, 1.5f),
      spec(2, TransitionKind.SLIDELEFT, 1.5f),
      spec(3, TransitionKind.SLIDELEFT, 1.5f),
    )
    val out = EditorAgent.enforceTransitionDiversity(input)
    assertEquals(TransitionKind.SLIDELEFT, out[0].transitionIn)
    assertEquals(TransitionKind.SLIDELEFT, out[1].transitionIn)
    assertEquals(TransitionKind.CUT, out[2].transitionIn)
  }

  @Test fun `breaksFiveConsecutiveAtPositions3and5`() {
    val input = listOf(
      spec(1, TransitionKind.CROSSFADE, 3f),
      spec(2, TransitionKind.CROSSFADE, 3f),
      spec(3, TransitionKind.CROSSFADE, 3f),
      spec(4, TransitionKind.CROSSFADE, 3f),
      spec(5, TransitionKind.CROSSFADE, 3f),
    )
    val out = EditorAgent.enforceTransitionDiversity(input)
    // After replacing #3 with FADE, the chain becomes CF-CF-FADE-CF-CF.
    // No new run of 3 forms, so #4 and #5 stay CROSSFADE.
    assertEquals(TransitionKind.CROSSFADE, out[0].transitionIn)
    assertEquals(TransitionKind.CROSSFADE, out[1].transitionIn)
    assertEquals(TransitionKind.FADE, out[2].transitionIn)
    assertEquals(TransitionKind.CROSSFADE, out[3].transitionIn)
    assertEquals(TransitionKind.CROSSFADE, out[4].transitionIn)
  }

  @Test fun `passthroughForListsShorterThan3`() {
    val one = listOf(spec(1, TransitionKind.CROSSFADE))
    val two = listOf(spec(1, TransitionKind.CROSSFADE), spec(2, TransitionKind.CROSSFADE))
    assertEquals(one.map { it.transitionIn }, EditorAgent.enforceTransitionDiversity(one).map { it.transitionIn })
    assertEquals(two.map { it.transitionIn }, EditorAgent.enforceTransitionDiversity(two).map { it.transitionIn })
  }
}
