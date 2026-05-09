/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * VlogPilot — on-device port of pc-pilot. The app turns a photo album segment
 * into AI-curated vlog candidates without sending any frame off-device.
 *
 * M1 scope: ingest the user's recent album, segment into events, render a list
 * UI. M2-M5 wire perception, agents, render.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.litertlm.Contents
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class VlogPilotTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = TASK_ID,
      label = "VlogPilot",
      category = Category.EXPERIMENTAL,
      icon = Icons.Outlined.Movie,
      description =
        "Turn the last 90 days of your photo album into AI-curated vlog candidates. " +
          "Browser → Director → Editor → Critic agents run fully on-device with Gemma 4 E2B-IT.",
      shortDescription = "AI vlog from your album",
      models = mutableListOf(),
      experimental = true,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    // M1: no model wiring yet — VLM/TFLite init lands in M2/M3.
    onDone("")
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    // Real entry is GalleryNavGraph's dedicated ROUTE_VLOGPILOT branch which hosts
    // VlogPilotRootScreen with the Apps button + top bar. This MainScreen exists
    // only because the CustomTask interface requires it; route it through the
    // same RootScreen (sans gallery-link, since the host can't be reached from
    // here without a NavController) so any unexpected fallback path stays
    // consistent with the c-end UX.
    val customTaskData = data as CustomTaskData
    VlogPilotRootScreen(
      modelManagerViewModel = customTaskData.modelManagerViewModel,
      onOpenGallery = {},
    )
  }

  companion object {
    const val TASK_ID = "vlog_pilot"
  }
}
