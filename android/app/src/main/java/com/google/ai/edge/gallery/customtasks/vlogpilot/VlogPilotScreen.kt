/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * M1 UI: scan album button → list of detected events. Permission flow uses the
 * standard Activity Result API. Subsequent milestones replace the simple list
 * with a per-event card showing the rendered candidate mp4.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VlogPilotScreen(
  bottomPadding: Dp,
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: VlogPilotViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsState()
  val mmUiState by modelManagerViewModel.uiState.collectAsState()
  val context = LocalContext.current

  // Pick the best already-downloaded multimodal LLM. Empty until user downloads one
  // via any LLM task (Ask Image / Chat) — model files are shared across tasks.
  val downloadedMultimodal: Model? = remember(mmUiState.modelDownloadStatus, mmUiState.tasks) {
    val downloaded = modelManagerViewModel.getAllDownloadedModels()
      .filter { it.llmSupportImage }
    // Prefer Gemma 4 E2B (matches our prompt set), then any other image-capable LLM.
    downloaded.firstOrNull { it.name.contains("gemma-4", ignoreCase = true) }
      ?: downloaded.firstOrNull()
  }

  val perms = remember {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
      arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
  }
  val permLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
      if (grants.values.all { it }) viewModel.scanAlbum()
    }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp)
      .padding(bottom = bottomPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("VlogPilot", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(
      "Scans your album from the last 30 days, segments it into events, and (in later milestones) " +
        "renders an AI-curated vlog candidate per event — fully on-device.",
      style = MaterialTheme.typography.bodyMedium,
    )

    Button(
      onClick = {
        val ungranted = perms.filter {
          ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isEmpty()) viewModel.scanAlbum() else permLauncher.launch(ungranted.toTypedArray())
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Scan album (last 30 days)")
    }

    Text(
      text = if (downloadedMultimodal != null) {
        "VLM ready: ${downloadedMultimodal.displayName.ifEmpty { downloadedMultimodal.name }}"
      } else {
        "VLM not downloaded — open the LLM Ask Image task and download Gemma 4 E2B (or any image-capable LLM) first."
      },
      style = MaterialTheme.typography.bodySmall,
    )

    if (state is PipelineState.Ready && downloadedMultimodal != null) {
      OutlinedButton(
        onClick = { viewModel.runFullPipeline(downloadedMultimodal) },
        modifier = Modifier.fillMaxWidth(),
      ) { Text("Generate vlog candidates (full pipeline)") }
    }

    Spacer(Modifier.height(4.dp))
    StatusLine(state)

    when (val s = state) {
      is PipelineState.Ready -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(s.events) { evt -> EventCard(evt) }
      }
      is PipelineState.Running -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      is PipelineState.Done -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(s.outputs) { r -> ResultCard(r) }
      }
      else -> Unit
    }
  }
}

@Composable
private fun ResultCard(r: EventResult) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(r.eventId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      Text(r.outputPath, style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun StatusLine(state: PipelineState) {
  val text = when (state) {
    is PipelineState.Idle -> "Tap the button to begin."
    is PipelineState.Scanning -> "Working: ${state.phase}"
    is PipelineState.Ready -> "Found ${state.assets.size} assets in ${state.events.size} events."
    is PipelineState.Running -> state.message
    is PipelineState.Done -> "Done — ${state.outputs.size} candidate(s) ready."
    is PipelineState.Error -> "Error: ${state.message}"
  }
  Text(text, style = MaterialTheme.typography.bodySmall)
}

private val EVT_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@Composable
private fun EventCard(event: Event) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(event.eventId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      Text(
        "${EVT_FMT.format(Date(event.startEpochMs))}  →  ${EVT_FMT.format(Date(event.endEpochMs))}",
        style = MaterialTheme.typography.bodySmall,
      )
      Text("${event.assetIds.size} assets", style = MaterialTheme.typography.bodySmall)
    }
  }
}
