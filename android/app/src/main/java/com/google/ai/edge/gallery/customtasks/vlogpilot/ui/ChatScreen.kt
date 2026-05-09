/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Phase 1 placeholder for the Chat tab. Renders a guided welcome message
 * and a "重扫" button at the top so users can refresh the AI's
 * understanding of their album. The real conversation surface (Phase 3)
 * will replace this with a full message-list + tool-call cards + input
 * bar wired into the worker pipeline.
 *
 * For now this just establishes the visual home so the bottom-tab
 * navigation has somewhere to land.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionManifest

@Composable
internal fun ChatScreen(
  decisions: List<EventDecisions>,
  eventSelection: EventSelectionManifest?,
  onRescan: () -> Unit,
  onOpenCurator: () -> Unit,
  @Suppress("UNUSED_PARAMETER") onOpenCandidate: (String) -> Unit,
) {
  val tokens = VlogPilotTokens
  val candidateCount = eventSelection?.candidates?.size ?: 0
  val completedCount = decisions.count { it.mp4Path != null }

  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(tokens.spacing.md),
  ) {
    // Top action bar — primary "重扫" + secondary "我自己挑素材"
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
    ) {
      TintedActionButton(
        text = "重扫相册",
        icon = Icons.Outlined.Search,
        onClick = onRescan,
        modifier = Modifier.fillMaxWidth(0.5f),
      )
      TintedActionButton(
        text = "挑素材",
        icon = Icons.Outlined.PhotoLibrary,
        onClick = onOpenCurator,
        tint = tokens.colors.systemPurple,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    // Welcome card — Claude-like greeting + library stats so the user knows
    // what the AI can already see, before any conversation starts.
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 0.dp,
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Box(
          modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.colors.accentTint),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            Icons.AutoMirrored.Outlined.Chat,
            contentDescription = null,
            tint = tokens.colors.accent,
            modifier = Modifier.size(28.dp),
          )
        }
        Text(
          "你好，我是 VlogPilot",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
        )
        Text(
          "告诉我你想做什么 vlog——可以是「一个本周旅行的纪录」「妈妈的日常温馨」之类的描述，我会读你的相册，写分镜，帮你剪一条出来。",
          style = MaterialTheme.typography.bodyMedium,
          color = tokens.colors.secondaryLabel,
          textAlign = TextAlign.Center,
        )
        if (candidateCount > 0 || completedCount > 0) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
          ) {
            ChatStat(label = "AI 推荐故事", value = candidateCount.toString())
            ChatStat(label = "已生成作品", value = completedCount.toString())
          }
        }
      }
    }

    // Suggested starters — quick-tap chips that seed the conversation. In
    // Phase 1 these don't actually send anything; they're a hint at what the
    // chat will accept once Phase 3 wires the agent in.
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 0.dp,
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          "试试这样开始对话",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        SuggestionRow("💛 帮我做一条这周的家庭日常")
        SuggestionRow("🍜 把上周的美食出片，要有食欲感")
        SuggestionRow("🌅 把最近的旅行剪一条 30 秒怀旧风的")
        SuggestionRow("👶 孩子的成长记录，慢节奏暖色")
      }
    }

    // Disabled-state hint until Phase 3 lands.
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(tokens.colors.systemOrange.copy(alpha = if (tokens.colors.isDark) 0.18f else 0.10f))
        .padding(14.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
          Icons.Outlined.AutoAwesome,
          contentDescription = null,
          tint = tokens.colors.systemOrange,
          modifier = Modifier.size(20.dp),
        )
        Text(
          "对话功能 Phase 3 接入。当前可以从「重扫相册」开始让 AI 先理解你的素材，然后到「作品」tab 选候选生成。",
          modifier = Modifier.fillMaxWidth(),
          style = MaterialTheme.typography.bodySmall,
          color = tokens.colors.systemOrange,
        )
      }
    }
  }
}

@Composable
private fun ChatStat(label: String, value: String) {
  val tokens = VlogPilotTokens
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      value,
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
      color = tokens.colors.accent,
    )
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      color = tokens.colors.secondaryLabel,
    )
  }
}

@Composable
private fun SuggestionRow(text: String) {
  val tokens = VlogPilotTokens
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(if (tokens.colors.isDark) tokens.colors.groupedSurfaceRaised else tokens.colors.groupedBackground)
      .padding(horizontal = 14.dp, vertical = 12.dp),
  ) {
    Text(
      text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}
