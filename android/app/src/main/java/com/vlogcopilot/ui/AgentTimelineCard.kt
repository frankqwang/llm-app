/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Claude-style timeline card — vertical list of agent steps with a tinted
 * dot, label, relative timestamp, and detail. Used in two places:
 *
 *   1. Live progress card during creation (bounded height + auto-stick to
 *      bottom so the latest step is always visible).
 *   2. 已完成作品详情页的"制作过程" — replays the full per-event history
 *      from `_state_history.jsonl`.
 *
 * The data source is [AgentTimelineEntry]; this file is purely presentation.
 */
package com.vlogcopilot.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vlogcopilot.pipeline.AgentTimeline
import com.vlogcopilot.pipeline.AgentTimelineEntry
import com.vlogcopilot.ui.theme.VlogCopilotTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vertical list of agent steps. Caller must provide entries in
 * chronological order (oldest first). When [pulse] is true, the latest dot
 * pulses to signal "still running"; pass false on completed events.
 *
 * @param maxVisibleHeight cap so the card doesn't dominate when used inline.
 *     Pass null to let it size naturally (e.g. on a dedicated detail page).
 */
@Composable
fun AgentTimelineCard(
  entries: List<AgentTimelineEntry>,
  modifier: Modifier = Modifier,
  pulse: Boolean = false,
  maxVisibleHeight: androidx.compose.ui.unit.Dp? = 280.dp,
  emptyText: String = "等待 AI 开始工作…",
) {
  val tokens = VlogCopilotTokens
  if (entries.isEmpty()) {
    Box(
      modifier = modifier
        .fillMaxWidth()
        .padding(vertical = tokens.spacing.lg),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        emptyText,
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.tertiaryLabel,
      )
    }
    return
  }

  // When a maxVisibleHeight is supplied we use LazyColumn for proper scroll +
  // virtualization. When null (e.g. nested inside an outer scroll container
  // like the detail page's LazyColumn) we fall through to a plain Column so
  // we don't trigger the "infinity max height" crash that nested Lazy* hits
  // when the parent doesn't know our height in advance.
  if (maxVisibleHeight != null) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size, pulse) {
      if (pulse && entries.isNotEmpty()) {
        listState.animateScrollToItem(entries.size - 1)
      }
    }
    LazyColumn(
      state = listState,
      modifier = modifier
        .fillMaxWidth()
        .heightIn(max = maxVisibleHeight),
      verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
      items(entries, key = { "${it.timestampMs}-${it.stage}-${it.detail.hashCode()}" }) { entry ->
        val isLatest = entry === entries.last()
        AgentTimelineRow(entry = entry, isLatest = isLatest, pulse = pulse && isLatest)
      }
    }
  } else {
    Column(modifier = modifier.fillMaxWidth()) {
      val last = entries.last()
      entries.forEach { entry ->
        AgentTimelineRow(entry = entry, isLatest = entry === last, pulse = false)
      }
    }
  }
}

@Composable
private fun AgentTimelineRow(
  entry: AgentTimelineEntry,
  isLatest: Boolean,
  pulse: Boolean,
) {
  val tokens = VlogCopilotTokens
  val tint = entry.category.resolveTint()
  val label = AgentTimeline.friendlyLabel(entry.stage, entry.detail)
  val detail = AgentTimeline.friendlyDetail(entry.stage, entry.detail)
  val timeStr = remember(entry.timestampMs) {
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timestampMs))
  }
  val pulseScale by animateFloatAsState(
    targetValue = if (pulse) 1.15f else 1f,
    animationSpec = spring(stiffness = 80f, dampingRatio = 0.4f),
    label = "agent-pulse",
  )

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 5.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.Top,
  ) {
    // Dot + connector line column. The "rail" running between dots is what
    // makes the timeline feel temporal rather than a generic list.
    Column(
      modifier = Modifier.width(18.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
        modifier = Modifier
          .padding(top = 4.dp)
          .size((9 * pulseScale).dp)
          .background(tint, CircleShape),
      )
      if (!isLatest) {
        Box(
          modifier = Modifier
            .padding(top = 2.dp)
            .width(1.dp)
            .height(28.dp)
            .background(tokens.colors.opaqueSeparator),
        )
      }
    }
    Column(modifier = Modifier.weight(1f)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          label,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Text(
          timeStr,
          style = MaterialTheme.typography.labelSmall,
          color = tokens.colors.tertiaryLabel,
        )
      }
      if (detail.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Surface(
          color = tint.copy(alpha = if (tokens.colors.isDark) 0.18f else 0.10f),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text(
            detail,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.secondaryLabel,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

/** Maps the stage category to the iOS-system-color from VlogCopilotTokens.
 *  This must be invoked from @Composable scope because token colors are
 *  resolved against the current LocalVlogCopilotExtraColors. */
@Composable
private fun AgentTimelineEntry.Category.resolveTint(): Color {
  val c = VlogCopilotTokens.colors
  return when (this) {
    AgentTimelineEntry.Category.SETUP -> c.secondaryLabel
    AgentTimelineEntry.Category.PLANNING -> c.systemPurple
    AgentTimelineEntry.Category.PERCEPTION -> c.accent
    AgentTimelineEntry.Category.PIPELINE -> c.secondaryLabel
    AgentTimelineEntry.Category.BROWSE -> c.accent
    AgentTimelineEntry.Category.AUDIENCE -> c.systemPink
    AgentTimelineEntry.Category.DIRECTOR -> c.systemOrange
    AgentTimelineEntry.Category.EDITOR -> c.systemPurple
    AgentTimelineEntry.Category.CRITIC -> c.systemOrange
    AgentTimelineEntry.Category.RENDER -> c.accent
    AgentTimelineEntry.Category.ITERATION -> c.systemPink
    AgentTimelineEntry.Category.DONE -> c.systemGreen
    AgentTimelineEntry.Category.ERROR -> c.systemRed
  }
}

