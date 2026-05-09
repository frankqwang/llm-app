/*
 * Copyright 2026 The pc-pilot v3 authors
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlogPilotUiTextTest {

  @Test
  fun refreshCandidateStagesDisplayAsRescanNotMaking() {
    listOf("candidate_refresh", "event_scout", "event_select").forEach { stage ->
      val friendly = friendlyProgress(
        ProgressSnapshot(
          headline = "pipeline headline",
          detail = "pipeline detail",
          stage = stage,
          current = 1,
          total = 3,
        ),
        making = true,
      )

      assertTrue("stage=$stage title=${friendly.title}", friendly.title.contains("重扫"))
      assertFalse("stage=$stage title=${friendly.title}", friendly.title.contains("制作"))
      assertFalse("stage=$stage detail=${friendly.detail}", friendly.detail.contains("制作中"))
    }
  }

  @Test
  fun operationClassifierSeparatesRefreshFromMaking() {
    val refresh = classifyVlogPilotOperation(
      state = PipelineState.Running("candidate refresh"),
      progress = ProgressSnapshot(stage = "event_scout"),
      activeIteration = null,
      albumLoading = false,
    )
    val making = classifyVlogPilotOperation(
      state = PipelineState.Running("queued"),
      progress = ProgressSnapshot(stage = "queued"),
      activeIteration = null,
      albumLoading = false,
    )

    assertEquals(VlogPilotOperation.RefreshingStories, refresh)
    assertEquals(VlogPilotOperation.MakingVideos, making)
  }
}
