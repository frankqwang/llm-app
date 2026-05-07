/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * One-button VlogPilot screen. Tap Generate → permissions → pipeline. The
 * orchestrator does its own ingest+segment internally; no separate preview
 * pass on the UI side. No model-picker UI either: we grab whatever LLM the
 * user has imported, prefer Gemma 4. Everything else is progress + results.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@Composable
fun VlogPilotScreen(
  bottomPadding: Dp,
  modelManagerViewModel: ModelManagerViewModel,
  modifier: Modifier = Modifier,
  viewModel: VlogPilotViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsState()
  val decisions by viewModel.decisions.collectAsState()
  val context = LocalContext.current

  val perms = remember {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
      arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
  }

  fun launchPipeline() {
    val downloaded = modelManagerViewModel.getAllDownloadedModels()
    val model = downloaded.firstOrNull { it.name.contains("gemma-4", ignoreCase = true) }
      ?: downloaded.firstOrNull()
    if (model == null) {
      viewModel.reportError("No LLM imported. Use Models → + → From local model file.")
      return
    }
    viewModel.runFullPipeline(model)
  }

  val permLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
      if (grants.values.all { it }) launchPipeline()
      else viewModel.reportError("Album permission denied.")
    }

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(16.dp)
      .padding(bottom = bottomPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("VlogPilot", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(
      "Turns the last 30 days of your album into AI-curated vlog candidates, fully on-device.",
      style = MaterialTheme.typography.bodyMedium,
    )

    val running = state is PipelineState.Running || state is PipelineState.Scanning
    Button(
      onClick = {
        if (running) {
          viewModel.cancelPipeline()
        } else {
          val ungranted = perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
          }
          if (ungranted.isEmpty()) launchPipeline() else permLauncher.launch(ungranted.toTypedArray())
        }
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(if (running) "Cancel" else "Generate")
    }

    StatusLine(state)
    if (state is PipelineState.Running || state is PipelineState.Scanning) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    // Decision-chain viewer: each event shows the AI's progressive output as
    // each agent finishes (browse → audience → director → editor → critic).
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      items(decisions) { d -> EventDecisionCard(d) }
    }
  }
}

@Composable
private fun EventDecisionCard(d: EventDecisions) {
  var expanded by remember { mutableStateOf(false) }
  val tlFinal = d.timelineFinal ?: d.timelineV1
  val stages = listOfNotNull(
    "Browser".takeIf { d.memory != null },
    "Audience".takeIf { d.audience != null },
    "Director".takeIf { d.director != null },
    "Editor".takeIf { tlFinal != null },
    "Critic".takeIf { d.critique != null },
    "Render".takeIf { d.mp4Path != null },
  )
  Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(d.eventId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      Text(
        stages.joinToString(" › ") + if (d.mp4Path != null) "  ✓" else "  …",
        style = MaterialTheme.typography.bodySmall,
        color = if (d.mp4Path != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (expanded) {
        HorizontalDivider()
        d.memory?.let { m ->
          SectionLabel("📖 Browser — 故事概括")
          Text(m.storylineSummary, style = MaterialTheme.typography.bodySmall)
          if (m.charactersObserved.isNotEmpty()) Text("人物: ${m.charactersObserved.joinToString(", ")}", style = MaterialTheme.typography.labelSmall)
          if (m.emotionalArc.isNotEmpty()) Text("情绪弧: ${m.emotionalArc}", style = MaterialTheme.typography.labelSmall)
        }
        d.audience?.let { a ->
          SectionLabel("👥 Audience — 情绪诉求")
          if (a.emotionalPayoff.isNotEmpty()) Text(a.emotionalPayoff, style = MaterialTheme.typography.bodySmall)
          if (a.pacingGuidance.isNotEmpty()) Text("节奏: ${a.pacingGuidance}", style = MaterialTheme.typography.labelSmall)
        }
        d.director?.let { dir ->
          SectionLabel("🎬 Director — 导演脚本")
          if (dir.title.isNotEmpty()) Text(dir.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
          Text("基调: ${dir.tone}", style = MaterialTheme.typography.labelSmall)
          if (dir.narrativeArc.isNotEmpty()) Text(dir.narrativeArc.joinToString(" → "), style = MaterialTheme.typography.labelSmall)
        }
        tlFinal?.let { t ->
          SectionLabel("✂️ Editor — Timeline (${t.shots.size} shots)")
          for (s in t.shots) ShotRow(s)
        }
        d.critique?.let { c ->
          if (c.issues.isNotEmpty() || c.revisedRequests.isNotEmpty()) {
            SectionLabel("🔍 Critic — 审片")
            for (issue in c.issues) Text("• $issue", style = MaterialTheme.typography.labelSmall)
            if (c.revisedRequests.isNotEmpty()) {
              Text("修订: ${c.revisedRequests.size} shot 重选", style = MaterialTheme.typography.labelSmall)
            }
          }
        }
        d.mp4Path?.let { path ->
          SectionLabel("🎞 Render — 成片预览")
          MiniVideoPlayer(mp4Path = path)
          Text(path, style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32))
        }
        d.perf?.let { p ->
          SectionLabel("⏱ 耗时")
          Text(
            "总 ${formatMs(p.totalMs)}  ·  感知 ${formatMs(p.perceptionMs)} (${p.perceptionAssetCount} 张, 缓存 ${p.perceptionCacheHits})  ·  " +
              "browse ${formatMs(p.browseMs)}  audience ${formatMs(p.audienceMs)}  director ${formatMs(p.directorMs)}  " +
              "editor ${formatMs(p.editorMs)}  critic ${formatMs(p.criticMs)}  render ${formatMs(p.renderMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else if (stages.isNotEmpty()) {
        Text("点击展开 AI 决策细节", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
      }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(top = 6.dp))
}

private fun formatMs(ms: Long): String = when {
  ms < 1000 -> "${ms}ms"
  ms < 60_000 -> "${ms / 1000}s"
  else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

@Composable
private fun MiniVideoPlayer(mp4Path: String) {
  // VideoView wrapped in AndroidView. Cheap, no extra dep. Uses Android's
  // built-in MediaPlayer under the hood, which can play whatever MediaCodec
  // can decode (H.264 / mpeg4 — both of our possible encoder outputs).
  AndroidView(
    factory = { ctx ->
      VideoView(ctx).apply {
        setVideoPath(mp4Path)
        setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
        seekTo(1)  // show first frame as preview
      }
    },
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(9f / 16f)
      .padding(top = 4.dp),
  )
}

@Composable
private fun ShotRow(s: ShotSpec) {
  Column(modifier = Modifier.fillMaxWidth().padding(start = 6.dp)) {
    Text("#${s.order} · ${s.mediaType.name.lowercase()} · ${"%.1f".format(s.durationSec)}s",
      style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    if (s.caption.isNotEmpty()) Text("「${s.caption}」", style = MaterialTheme.typography.bodySmall)
    if (s.rationale.isNotEmpty()) Text(s.rationale, style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun StatusLine(state: PipelineState) {
  val text = when (state) {
    is PipelineState.Idle -> ""
    is PipelineState.Scanning -> state.phase
    is PipelineState.Ready -> "${state.assets.size} assets, ${state.events.size} events"
    is PipelineState.Running -> state.message
    is PipelineState.Done -> "Done — ${state.outputs.size} candidate(s)"
    is PipelineState.Error -> "Error: ${state.message}"
  }
  if (text.isNotEmpty()) {
    Text(text, style = MaterialTheme.typography.bodySmall)
  }
}
