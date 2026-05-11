/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Claude-agent-style "vlog creation perspective" — instead of just showing
 * stage names + timestamps, this panel renders WHAT EACH AGENT ACTUALLY
 * PRODUCED. Each stage is a tappable card:
 *   ✓ (done) / · (pending) / ⏳ (running, pulsing) status dot
 *   agent name + 1-line summary distilled from its output
 *   tap-to-expand body showing the structured content (storyline / pace /
 *     shot blueprint / picks / verdict / mp4 path).
 *
 * The data source is [EventDecisions], whose fields fill in progressively
 * as the orchestrator writes each stage's JSON. Same component is used for
 * live progress (in-flight event) and replay (completed event in detail page).
 */
package com.vlogcopilot.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vlogcopilot.schemas.AudienceBrief
import com.vlogcopilot.schemas.Critique
import com.vlogcopilot.schemas.CriticVerdict
import com.vlogcopilot.schemas.DirectorBrief
import com.vlogcopilot.schemas.EventMemory
import com.vlogcopilot.schemas.Pace
import com.vlogcopilot.schemas.ShotRequest
import com.vlogcopilot.schemas.Timeline
import com.vlogcopilot.ui.theme.VlogCopilotTokens
import com.vlogcopilot.worker.EventDecisions
import com.vlogcopilot.worker.StagePerf
import java.util.Locale

/** Stage progression — drives which card pulses next when [live] = true. */
private enum class Stage { BROWSE, AUDIENCE, DIRECTOR, EDITOR, CRITIC, RENDER }

private fun EventDecisions.stageStatus(s: Stage): StageStatus = when (s) {
  Stage.BROWSE -> if (memory != null) StageStatus.DONE else StageStatus.PENDING
  Stage.AUDIENCE -> if (audience != null) StageStatus.DONE else StageStatus.PENDING
  Stage.DIRECTOR -> if (director != null) StageStatus.DONE else StageStatus.PENDING
  Stage.EDITOR -> if (timelineV1 != null || timelineFinal != null) StageStatus.DONE else StageStatus.PENDING
  Stage.CRITIC -> if (critique != null) StageStatus.DONE else StageStatus.PENDING
  Stage.RENDER -> if (mp4Path != null) StageStatus.DONE else StageStatus.PENDING
}

private enum class StageStatus { DONE, RUNNING, PENDING }

/** First pending stage in dependency order — the agent currently working
 *  in live mode gets this one's dot to pulse. */
private fun activeStage(d: EventDecisions): Stage? {
  Stage.values().forEach { if (d.stageStatus(it) == StageStatus.PENDING) return it }
  return null
}

/**
 * @param live when true, the first not-yet-done stage shows a pulsing dot
 *     and "正在工作" label, matching how Claude indicates an in-flight tool
 *     call. When false (replay / completed event), all stages render in
 *     their final state.
 */
@Composable
fun AgentWorkPanel(
  d: EventDecisions,
  modifier: Modifier = Modifier,
  live: Boolean = false,
) {
  val running = if (live) activeStage(d) else null
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    AgentCard(
      icon = Icons.Outlined.Search,
      label = "Browse · 阅读事件",
      summary = browseSummary(d.memory, d.inputAssets.size),
      status = d.stageStatus(Stage.BROWSE),
      isRunning = running == Stage.BROWSE,
    ) { BrowseBody(d.memory) }
    AgentCard(
      icon = Icons.Outlined.Person,
      label = "Audience · 观众诉求",
      summary = audienceSummary(d.audience),
      status = d.stageStatus(Stage.AUDIENCE),
      isRunning = running == Stage.AUDIENCE,
    ) { AudienceBody(d.audience) }
    AgentCard(
      icon = Icons.Outlined.Movie,
      label = "Director · 写分镜",
      summary = directorSummary(d.director),
      status = d.stageStatus(Stage.DIRECTOR),
      isRunning = running == Stage.DIRECTOR,
    ) { DirectorBody(d.director) }
    AgentCard(
      icon = Icons.Outlined.Edit,
      label = "Editor · 选镜头",
      summary = editorSummary(d.timelineFinal ?: d.timelineV1),
      status = d.stageStatus(Stage.EDITOR),
      isRunning = running == Stage.EDITOR,
    ) { EditorBody(d.timelineFinal ?: d.timelineV1) }
    AgentCard(
      icon = Icons.Outlined.Visibility,
      label = "Critic · 审片",
      summary = criticSummary(d.critique),
      status = d.stageStatus(Stage.CRITIC),
      isRunning = running == Stage.CRITIC,
    ) { CriticBody(d.critique) }
    AgentCard(
      icon = Icons.Outlined.AutoAwesome,
      label = "Render · 渲染",
      summary = renderSummary(d.mp4Path, d.perf, d.timelineFinal ?: d.timelineV1),
      status = d.stageStatus(Stage.RENDER),
      isRunning = running == Stage.RENDER,
    ) { RenderBody(d.mp4Path, d.perf) }
  }
}

// ----- card chrome -----

@Composable
private fun AgentCard(
  icon: ImageVector,
  label: String,
  summary: String,
  status: StageStatus,
  isRunning: Boolean,
  bodyContent: @Composable ColumnScope.() -> Unit,
) {
  val tokens = VlogCopilotTokens
  val effectiveStatus = if (isRunning) StageStatus.RUNNING else status
  var expanded by remember(label) { mutableStateOf(false) }
  val hasBody = effectiveStatus == StageStatus.DONE
  val tint = when (effectiveStatus) {
    StageStatus.DONE -> tokens.colors.systemGreen
    StageStatus.RUNNING -> tokens.colors.accent
    StageStatus.PENDING -> tokens.colors.tertiaryLabel
  }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
  ) {
    Column {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable(enabled = hasBody) { expanded = !expanded }
          .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        StageStatusDot(status = effectiveStatus, tint = tint)
        Box(
          modifier = Modifier
            .size(22.dp)
            .background(tint.copy(alpha = if (tokens.colors.isDark) 0.22f else 0.12f), RoundedCornerShape(7.dp)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        }
        Text(
          if (effectiveStatus == StageStatus.DONE && summary.isNotBlank()) "$label · $summary" else label,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (hasBody) {
          Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "收起详情" else "展开详情",
            tint = tokens.colors.tertiaryLabel,
            modifier = Modifier.size(16.dp),
          )
        }
      }
      AnimatedExpand(expanded = expanded && hasBody) {
        Column(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
          HairlineDivider(startInset = 0.dp)
          bodyContent()
        }
      }
    }
  }
}

/** Pulsing dot for the running stage; static dot otherwise. The pulse is
 *  what tells the user the AI is currently doing this thing. */
@Composable
private fun StageStatusDot(status: StageStatus, tint: Color) {
  val infinite = rememberInfiniteTransition(label = "stage-dot")
  val scale by infinite.animateFloat(
    initialValue = 0.7f,
    targetValue = 1.4f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1100),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "stage-pulse",
  )
  val resolvedSize = if (status == StageStatus.RUNNING) (8 * scale).dp else 8.dp
  Box(
    modifier = Modifier.size(16.dp),
    contentAlignment = Alignment.Center,
  ) {
    when (status) {
      StageStatus.DONE -> Icon(
        Icons.Outlined.CheckCircle,
        contentDescription = "已完成",
        tint = tint,
        modifier = Modifier.size(16.dp),
      )
      StageStatus.RUNNING -> Box(
        modifier = Modifier
          .size(resolvedSize)
          .background(tint, CircleShape),
      )
      StageStatus.PENDING -> Box(
        modifier = Modifier
          .size(8.dp)
          .background(tint.copy(alpha = 0.4f), CircleShape),
      )
    }
  }
}

// ----- per-agent summaries (the "head" line) -----

private fun browseSummary(m: EventMemory?, assetCount: Int): String =
  when {
    m == null -> "$assetCount 张素材待阅读"
    m.storylineSummary.isNotBlank() -> m.storylineSummary.take(50)
    else -> "${m.charactersObserved.size} 个人物 · ${m.keyMoments.size} 个关键时刻"
  }

private fun audienceSummary(b: AudienceBrief?): String {
  if (b == null) return "等待 Browse 完成"
  val pace = paceLabel(b.pace)
  val payoff = b.emotionalPayoff.take(20)
  return if (payoff.isNotBlank()) "$pace · $payoff" else pace
}

private fun directorSummary(d: DirectorBrief?): String {
  if (d == null) return "等待观众分析完成"
  val title = d.title.takeIf { it.isNotBlank() } ?: "(未命名)"
  return "$title · ${d.shotBlueprint.size} 镜头 · ${"%.0f".format(Locale.US, d.targetDurationSec)}s"
}

private fun editorSummary(t: Timeline?): String {
  if (t == null) return "等待 Director 完成"
  val totalSec = t.shots.sumOf { it.durationSec.toDouble() }
  return "${t.shots.size} 个镜头 · 总 ${"%.1f".format(Locale.US, totalSec)}s"
}

private fun criticSummary(c: Critique?): String {
  if (c == null) return "等待 Editor 完成"
  return when (c.verdict) {
    CriticVerdict.ACCEPT -> "通过 · ${c.issues.size} 条改进意见"
    CriticVerdict.REVISE -> "需修订 · ${c.revisedRequests.size} 处"
    CriticVerdict.ABORT -> "放弃 · ${c.issues.size} 处难修"
  }
}

private fun renderSummary(mp4: String?, perf: StagePerf?, t: Timeline?): String {
  if (mp4 == null) return "等待 Critic 完成"
  val totalSec = t?.shots?.sumOf { it.durationSec.toDouble() } ?: 0.0
  val renderMs = perf?.renderMs ?: 0L
  val durStr = if (totalSec > 0) "${"%.1f".format(Locale.US, totalSec)}s" else "完成"
  val renderStr = if (renderMs > 0) " · 渲染用时 ${formatMs(renderMs)}" else ""
  return "$durStr$renderStr"
}

private fun paceLabel(p: Pace): String = when (p) {
  Pace.SNAPPY -> "快剪 (15-18s)"
  Pace.BALANCED -> "均衡 (18-22s)"
  Pace.LINGERING -> "留白 (22-28s)"
}

// ----- per-agent expanded bodies -----

@Composable
private fun BrowseBody(m: EventMemory?) {
  val tokens = VlogCopilotTokens
  if (m == null) return
  if (m.storylineSummary.isNotBlank()) {
    BodyText(m.storylineSummary)
  }
  if (m.charactersObserved.isNotEmpty()) {
    BodyKv("出场人物", m.charactersObserved.joinToString("、"))
  }
  if (m.emotionalArc.isNotBlank()) {
    BodyKv("情绪弧线", m.emotionalArc)
  }
  if (m.visualStyleSignals.isNotBlank()) {
    BodyKv("视觉信号", m.visualStyleSignals)
  }
  if (m.keyMoments.isNotEmpty()) {
    Text(
      "关键时刻",
      style = MaterialTheme.typography.labelMedium,
      color = tokens.colors.secondaryLabel,
      fontWeight = FontWeight.SemiBold,
    )
    m.keyMoments.take(5).forEach { km ->
      Text(
        "· #${km.imageIndex} ${km.why}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
private fun AudienceBody(b: AudienceBrief?) {
  if (b == null) return
  if (b.emotionalPayoff.isNotBlank()) BodyKv("情绪回报", b.emotionalPayoff)
  if (b.hookStrategy.isNotBlank()) BodyKv("开头钩子", b.hookStrategy)
  if (b.povVoice.isNotBlank()) BodyKv("视角", b.povVoice)
  if (b.pacingGuidance.isNotBlank()) BodyKv("节奏", b.pacingGuidance)
  if (b.avoidList.isNotEmpty()) BodyKv("避免", b.avoidList.joinToString("、"))
}

@Composable
private fun DirectorBody(d: DirectorBrief?) {
  val tokens = VlogCopilotTokens
  if (d == null) return
  if (d.title.isNotBlank()) {
    Text(
      d.title,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
  d.tagline?.takeIf { it.isNotBlank() }?.let {
    Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.secondaryLabel)
  }
  BodyKv("基调", d.tone)
  BodyKv("调色", d.colorGrade.name.lowercase().replace('_', ' '))
  if (d.narrativeArc.isNotEmpty()) BodyKv("叙事弧", d.narrativeArc.joinToString("  →  "))
  if (d.shotBlueprint.isNotEmpty()) {
    Spacer(Modifier.size(2.dp))
    Text(
      "${d.shotBlueprint.size} 个分镜",
      style = MaterialTheme.typography.labelMedium,
      color = tokens.colors.secondaryLabel,
      fontWeight = FontWeight.SemiBold,
    )
    d.shotBlueprint.forEach { sr -> ShotBlueprintLine(sr) }
  }
}

@Composable
private fun ShotBlueprintLine(sr: ShotRequest) {
  val tokens = VlogCopilotTokens
  val tint = roleTint(sr.role.name.lowercase())
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      "${sr.position}",
      modifier = Modifier
        .size(20.dp)
        .background(tint.copy(alpha = if (tokens.colors.isDark) 0.24f else 0.14f), CircleShape),
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      color = tint,
      textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          sr.role.name.lowercase(),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = tint,
        )
        Text(
          "${"%.1f".format(Locale.US, sr.durationSec)}s",
          style = MaterialTheme.typography.labelSmall,
          color = tokens.colors.tertiaryLabel,
        )
      }
      if (sr.visualRequirements.isNotBlank()) {
        Text(
          sr.visualRequirements,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (sr.captionText.isNotBlank()) {
        Text(
          "「${sr.captionText}」",
          style = MaterialTheme.typography.labelSmall,
          color = tokens.colors.secondaryLabel,
        )
      }
    }
  }
}

@Composable
private fun EditorBody(t: Timeline?) {
  val tokens = VlogCopilotTokens
  if (t == null || t.shots.isEmpty()) return
  Text(
    "${t.shots.size} 个镜头",
    style = MaterialTheme.typography.labelMedium,
    color = tokens.colors.secondaryLabel,
    fontWeight = FontWeight.SemiBold,
  )
  t.shots.sortedBy { it.order }.forEach { shot ->
    Row(
      modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Text(
        "#${shot.order}",
        modifier = Modifier.width(28.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = tokens.colors.accent,
      )
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
          "${"%.1f".format(Locale.US, shot.durationSec)}s · ${shot.caption.ifBlank { "(无字幕)" }}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        if (shot.rationale.isNotBlank()) {
          Text(
            shot.rationale,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.tertiaryLabel,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
private fun CriticBody(c: Critique?) {
  val tokens = VlogCopilotTokens
  if (c == null) return
  val verdictColor = when (c.verdict) {
    CriticVerdict.ACCEPT -> tokens.colors.systemGreen
    CriticVerdict.REVISE -> tokens.colors.systemOrange
    CriticVerdict.ABORT -> tokens.colors.systemRed
  }
  Text(
    when (c.verdict) {
      CriticVerdict.ACCEPT -> "✓ 接受这个版本"
      CriticVerdict.REVISE -> "需要修订"
      CriticVerdict.ABORT -> "放弃，无法修复"
    },
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.SemiBold,
    color = verdictColor,
  )
  if (c.issues.isNotEmpty()) {
    c.issues.forEach { issue ->
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Text("•", color = tokens.colors.systemOrange, fontWeight = FontWeight.Bold)
        Text(
          issue,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
  if (c.revisedRequests.isNotEmpty()) {
    BodyKv("修订镜头", c.revisedRequests.size.toString())
  }
}

@Composable
private fun RenderBody(mp4: String?, perf: StagePerf?) {
  val tokens = VlogCopilotTokens
  if (mp4 != null) {
    Text(
      "✓ 已输出",
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold,
      color = tokens.colors.systemGreen,
    )
    BodyKv("文件路径", mp4.substringAfterLast('/'))
  }
  perf?.let { p ->
    BodyKv("总耗时", formatMs(p.totalMs))
    if (p.perceptionMs > 0) BodyKv("感知", formatMs(p.perceptionMs))
    if (p.browseMs > 0) BodyKv("Browse", formatMs(p.browseMs))
    if (p.audienceMs > 0) BodyKv("Audience", formatMs(p.audienceMs))
    if (p.directorMs > 0) BodyKv("Director", formatMs(p.directorMs))
    if (p.editorMs > 0) BodyKv("Editor", formatMs(p.editorMs))
    if (p.criticMs > 0) BodyKv("Critic", formatMs(p.criticMs))
    if (p.renderMs > 0) BodyKv("Render", formatMs(p.renderMs))
  }
}

// ----- shared body primitives -----

@Composable
private fun BodyText(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurface,
  )
}

@Composable
private fun BodyKv(label: String, value: String) {
  if (value.isBlank()) return
  val tokens = VlogCopilotTokens
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
    Text(
      label,
      modifier = Modifier.width(72.dp),
      style = MaterialTheme.typography.bodyMedium,
      color = tokens.colors.secondaryLabel,
    )
    Text(
      value,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun roleTint(role: String): Color {
  val c = VlogCopilotTokens.colors
  return when (role) {
    "opening" -> c.systemGreen
    "climax" -> c.systemOrange
    "closing" -> c.systemPurple
    "action" -> c.accent
    "portrait" -> c.systemPink
    "establishing" -> c.systemPurple
    else -> c.secondaryLabel
  }
}

// Local copy of formatMs to avoid pulling the entire VlogCopilotUiText namespace
private fun formatMs(ms: Long): String = when {
  ms < 1000 -> "${ms}ms"
  ms < 60_000 -> "${ms / 1000}s"
  else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}
