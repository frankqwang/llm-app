/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Phase 2: single unified feed for the 作品 tab. Merges already-generated
 * vlogs (EventDecisions with mp4Path) and AI-clustered candidates
 * (EventCandidateSnapshot with no mp4 yet) into one chronological list with
 * shared filter chips. Tap a candidate → bumps the user to the Chat tab
 * with a prefilled "做这个" message; tap a vlog → opens its detail page
 * (existing flow).
 *
 * Lives alongside VideoShelf rather than replacing it (VideoShelf still
 * works as a standalone view from other entry points).
 */
package com.vlogcopilot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vlogcopilot.ui.CapsuleChip
import com.vlogcopilot.ui.HairlineDivider
import com.vlogcopilot.ui.InsetGroupedSurface
import com.vlogcopilot.ui.LargeTitleHeader
import com.vlogcopilot.ui.theme.VlogCopilotTokens
import com.vlogcopilot.worker.EventCandidateSnapshot
import com.vlogcopilot.worker.EventDecisions
import com.vlogcopilot.worker.EventSelectionManifest
import com.vlogcopilot.worker.EventSelectionStatus

internal enum class WorksFilter(val label: String) {
  All("全部"),
  Completed("已生成"),
  Candidates("待制作"),
}

internal sealed class WorkRow {
  data class Completed(val decision: EventDecisions) : WorkRow()
  data class Candidate(val snapshot: EventCandidateSnapshot) : WorkRow()
}

@Composable
internal fun UnifiedWorksFeed(
  decisions: List<EventDecisions>,
  manifest: EventSelectionManifest?,
  activeEventIds: Set<String> = emptySet(),
  onOpenVlog: (String) -> Unit,
  onMakeCandidate: (EventCandidateSnapshot) -> Unit,
) {
  val tokens = VlogCopilotTokens
  var filter by remember { mutableStateOf(WorksFilter.All) }

  val completedIds = remember(decisions) { decisions.mapTo(mutableSetOf()) { it.eventId } }
  val candidates = remember(manifest, completedIds) {
    manifest?.candidates
      ?.filter { it.eventId !in completedIds }
      ?.filter { it.status != EventSelectionStatus.EXCLUDED }
      ?: emptyList()
  }

  val rows = remember(decisions, candidates, filter) {
    val completed = decisions.map { WorkRow.Completed(it) }
    val candidateRows = candidates.map { WorkRow.Candidate(it) }
    val all = completed + candidateRows
    when (filter) {
      WorksFilter.All -> all
      WorksFilter.Completed -> completed
      WorksFilter.Candidates -> candidateRows
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)) {
    LargeTitleHeader(
      title = "作品",
      subtitle = "${decisions.size} 已生成 · ${candidates.size} 待制作",
    )

    LazyRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(tokens.spacing.xs),
      contentPadding = PaddingValues(horizontal = tokens.spacing.pageInset),
    ) {
      items(WorksFilter.entries.toList()) { f ->
        val count = when (f) {
          WorksFilter.All -> decisions.size + candidates.size
          WorksFilter.Completed -> decisions.size
          WorksFilter.Candidates -> candidates.size
        }
        CapsuleChip(
          text = if (count > 0) "${f.label} $count" else f.label,
          selected = filter == f,
          onClick = { filter = f },
        )
      }
    }

    InsetGroupedSurface {
      if (rows.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(tokens.spacing.xl),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            when (filter) {
              WorksFilter.Completed -> "还没有已生成的作品"
              WorksFilter.Candidates -> "还没有待制作的候选素材，到对话页让 AI 先扫一遍相册"
              WorksFilter.All -> "还没有作品。到对话页让 AI 帮你做一条"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.secondaryLabel,
          )
        }
      } else {
        rows.forEachIndexed { index, row ->
          when (row) {
            is WorkRow.Completed -> CompletedRow(
              d = row.decision,
              onClick = { onOpenVlog(row.decision.eventId) },
            )
            is WorkRow.Candidate -> CandidateRow(
              snapshot = row.snapshot,
              active = row.snapshot.eventId in activeEventIds,
              onClick = { onMakeCandidate(row.snapshot) },
            )
          }
          if (index != rows.lastIndex) {
            HairlineDivider(startInset = 96.dp)
          }
        }
      }
    }

    Spacer(Modifier.size(tokens.spacing.lg))
  }
}

@Composable
private fun CompletedRow(d: EventDecisions, onClick: () -> Unit) {
  val tokens = VlogCopilotTokens
  val durationSec = d.videoDurationSec()
  val timeline = d.videoTimeline()
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onClick() }
      .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.md),
    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.md),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val cover = d.videoCoverAsset()
    Box(
      modifier = Modifier
        .size(72.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(tokens.colors.groupedSurfaceRaised),
    ) {
      if (cover != null) {
        AssetThumb(asset = cover, modifier = Modifier.size(72.dp))
      } else {
        Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
          Icon(Icons.Outlined.Movie, contentDescription = null, tint = tokens.colors.tertiaryLabel)
        }
      }
      // Duration pill
      Box(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(4.dp)
          .clip(RoundedCornerShape(6.dp))
          .background(Color.Black.copy(alpha = 0.55f))
          .padding(horizontal = 5.dp, vertical = 1.dp),
      ) {
        Text(
          formatSec(durationSec),
          style = MaterialTheme.typography.labelSmall,
          color = Color.White,
          fontWeight = FontWeight.Medium,
        )
      }
    }

    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
        storyTitle(d),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        videoCompactMeta(d, null),
        style = MaterialTheme.typography.bodySmall,
        color = tokens.colors.secondaryLabel,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "${timeline?.shots?.size ?: 0} 镜头 · ${d.inputAssets.size} 素材${if (d.versionCount > 1) " · v${d.versionCount}" else ""}",
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.labelMedium,
          color = tokens.colors.tertiaryLabel,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        // Status: 已生成 (green tint)
        StatusTag("已生成", tokens.colors.systemGreen)
      }
    }
    Icon(
      imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
      contentDescription = "打开",
      tint = tokens.colors.tertiaryLabel,
      modifier = Modifier.size(20.dp),
    )
  }
}

@Composable
private fun CandidateRow(snapshot: EventCandidateSnapshot, active: Boolean, onClick: () -> Unit) {
  val tokens = VlogCopilotTokens
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onClick() }
      .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.md),
    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.md),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val cover = snapshot.assets.firstOrNull()
    Box(
      modifier = Modifier
        .size(72.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(tokens.colors.groupedSurfaceRaised),
      contentAlignment = Alignment.Center,
    ) {
      if (cover != null) {
        AssetThumb(asset = cover, modifier = Modifier.size(72.dp))
      } else {
        Icon(
          Icons.Outlined.AutoAwesome,
          contentDescription = null,
          tint = tokens.colors.systemOrange,
          modifier = Modifier.size(28.dp),
        )
      }
      Box(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(4.dp)
          .clip(RoundedCornerShape(6.dp))
          .background(tokens.colors.systemOrange.copy(alpha = 0.88f))
          .padding(horizontal = 5.dp, vertical = 1.dp),
      ) {
        Text(
          "AI",
          style = MaterialTheme.typography.labelSmall,
          color = Color.White,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
        storyTitle(snapshot),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        snapshot.scoutSummary.ifBlank { "${snapshot.assets.size} 素材 · ${snapshot.realVideoCount} 段视频" },
        style = MaterialTheme.typography.bodySmall,
        color = tokens.colors.secondaryLabel,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "${snapshot.assets.size} 素材 · 适合度 ${(snapshot.valueScore.coerceIn(0f, 1f) * 100).toInt()}",
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.labelMedium,
          color = tokens.colors.tertiaryLabel,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val statusText = when {
          active -> "制作中"
          snapshot.status == EventSelectionStatus.COMPLETED -> "需重导出"
          else -> "待制作"
        }
        StatusTag(statusText, if (active) tokens.colors.accent else tokens.colors.systemOrange)
      }
    }
    Icon(
      imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
      contentDescription = "打开编辑",
      tint = tokens.colors.tertiaryLabel,
      modifier = Modifier.size(20.dp),
    )
  }
}

@Composable
private fun StatusTag(text: String, tint: Color) {
  val tokens = VlogCopilotTokens
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(tint.copy(alpha = if (tokens.colors.isDark) 0.22f else 0.14f))
      .padding(horizontal = 7.dp, vertical = 2.dp),
  ) {
    Text(
      text,
      style = MaterialTheme.typography.labelSmall,
      color = tint,
      fontWeight = FontWeight.SemiBold,
    )
  }
}
