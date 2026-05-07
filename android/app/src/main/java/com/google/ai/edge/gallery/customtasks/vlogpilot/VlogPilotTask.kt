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
        "Turn the last 30 days of your photo album into AI-curated vlog candidates. " +
          "Browser → Director → Editor → Critic agents run fully on-device with Gemma 3n.",
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
    val customTaskData = data as CustomTaskData
    VlogPilotScreen(
      bottomPadding = customTaskData.bottomPadding,
      modelManagerViewModel = customTaskData.modelManagerViewModel,
    )
  }

  companion object {
    const val TASK_ID = "vlog_pilot"
  }
}
