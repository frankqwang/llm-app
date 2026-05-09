/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Process viewer for the on-device VlogPilot pipeline. The screen is built as
 * a compact editing console: input assets, agent outputs, timeline decisions,
 * critique notes, and the rendered candidate stay in one inspectable flow.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.PromptStrings
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.EventScoutAgent
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.VlmAnnotator
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.MediaLoader
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.GenerationIntent
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.PowerProfile
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.VlogPilotRunConfig
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.VlmTags
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventCandidateSnapshot
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionManifest
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionStatus
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.StagePerf
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PromptCatalog() {
  PanelCard {
    PromptCatalogInline()
  }
}

@Composable
internal fun PromptCatalogInline() {
  Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    SectionHeader(
      icon = Icons.Outlined.Edit,
      title = "系统 Prompt",
      subtitle = "系统 prompt + 实际 user prompt 模板",
    )
    promptSpecs().forEach { spec ->
      PromptCard(spec)
    }
  }
}

@Composable
internal fun PromptCard(spec: PromptSpec) {
  var expanded by remember { mutableStateOf(false) }
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable { expanded = !expanded },
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(spec.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(spec.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
          imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
          contentDescription = null,
        )
      }
      PromptTextBlock("System", spec.systemPrompt, expanded)
      spec.userTemplate?.let { PromptTextBlock("User 模板", it, expanded) }
    }
  }
}

@Composable
internal fun PromptTextBlock(label: String, text: String, expanded: Boolean) {
  val shown = if (expanded || text.length <= 520) text else text.take(520) + "\n..."
  Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
  ) {
    Text(
      shown,
      modifier = Modifier.padding(10.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

internal data class PromptSpec(
  val title: String,
  val subtitle: String,
  val systemPrompt: String,
  val userTemplate: String? = null,
)

internal fun promptSpecs(): List<PromptSpec> = listOf(
  PromptSpec(
    title = "VLM 单素材标注",
    subtitle = "图片缩略图 -> VlmTags（含构图/光线/动态）",
    systemPrompt = PromptStrings.VLM_IMAGE_SYSTEM,
    userTemplate = "媒体类型: <image/video/live_photo>。这是用于 vlog 剪辑的素材标注，请输出 VlmTags JSON。",
  ),
  PromptSpec(
    title = "VLM 视频多帧标注",
    subtitle = "自适应多帧视频网格 -> VlmTags + VideoInsight（含镜头运动/节奏/声音暗示）",
    systemPrompt = PromptStrings.VLM_VIDEO_SYSTEM,
    userTemplate = "媒体类型: <video/live_photo>。帧编号和时间戳: 1=0.4s, 2=1.2s, ...。这是用于 vlog 剪辑的素材标注，请输出 Video VlmTags JSON。",
  ),
  PromptSpec(
    title = "Event Scout",
    subtitle = "3x3 候选事件 contact sheet -> EventScout",
    systemPrompt = EventScoutAgent.SCOUT_SYSTEM_PROMPT,
    userTemplate = """
Event id: <eventId>
Page: <page>/<pages>
Cells are numbered 1..9. Each cell is one image or one sampled video frame.
Return EventPageScout JSON only.
""".trimIndent(),
  ),
  PromptSpec(
    title = "Browser / Contact Sheet",
    subtitle = "事件缩略图网格 -> EventMemory",
    systemPrompt = PromptStrings.MONTAGE_SYSTEM,
    userTemplate = "事件 <eventId> 第 <page>/<pages> 页，本页 <N> 张。image_index 为本页内 1..<N> 编号。请输出 EventMemory JSON。",
  ),
  PromptSpec(
    title = "Audience",
    subtitle = "事件记忆 -> 观众情绪目标",
    systemPrompt = PromptStrings.AUDIENCE_SYSTEM,
    userTemplate = """
事件 <eventId> 的 EventMemory:
- storyline_summary: <storyline_summary>
- emotional_arc: <emotional_arc>
- characters: <characters>
- visual_style: <visual_style_signals>

请输出 AudienceBrief JSON。
""".trimIndent(),
  ),
  PromptSpec(
    title = "Director",
    subtitle = "EventMemory + AudienceBrief -> 分镜剧本",
    systemPrompt = PromptStrings.DIRECTOR_SYSTEM,
    userTemplate = """
EventMemory:
<storyline_summary>
情绪曲线: <emotional_arc>
人物: <characters>

AudienceBrief:
- 情绪点: <emotional_payoff>
- hook: <hook_strategy>
- pov: <pov_voice>
- 节奏: <pacing_guidance>
- 避免: <avoid_list>

总素材: <assetCount> 张/段；目标 vlog 时长 18-22 秒。
请输出 DirectorBrief JSON。
""".trimIndent(),
  ),
  PromptSpec(
    title = "Editor",
    subtitle = "候选缩略图 + 标签 -> 选一个镜头",
    systemPrompt = PromptStrings.EDITOR_SYSTEM,
    userTemplate = """
当前 slot：role=<role>; mood=<mood_target>; visual_req=<visual_requirements>
previous_shot_summary: <previous_shot_summary>

候选标签（VLM 已经看过每张图，结构化摘要）：
  1. scene=<scene> / action=<action> / mood=<mood> / salient=<salient> / subjects=<subjects>
  ...

请综合标签 + 缩略图视觉，从 <N> 张候选中选 1 张（编号 1..<N>）。
""".trimIndent(),
  ),
  PromptSpec(
    title = "Critic",
    subtitle = "粗剪 timeline + storyboard -> 视觉审片与修订请求",
    systemPrompt = PromptStrings.CRITIC_SYSTEM,
    userTemplate = """
DirectorBrief.title=<title>; tone=<tone>; target_duration=<target_duration>
narrative_arc: <arc>
cheap_checks: <pass/needs_attention>

EventMemory.storyline: <storyline>

Timeline v<iteration> shots:
  #1 [image/video] dur=<sec>s trim=<start-end>s caption="<caption>" - <rationale>
  ...

附一张 storyboard：每格对应一个 shot，左上角编号和 #order 一致。
请同时检查文字时间线和 storyboard，输出 Critique JSON。
""".trimIndent(),
  ),
)

