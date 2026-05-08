/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Bottom sheet that opens when the user taps "重新剪一次" on a generated video.
 * Milestone A: chip-only RENDER_ONLY feedback (去字幕 / 换调色). Milestone C
 * adds a textfield + storyboard tap-to-target + GLOBAL/SHOT_LEVEL chips.
 *
 * Why a bottom sheet instead of a full screen: the user is in playback context,
 * comparing what they see to what they want. Keeping the player visible while
 * collecting feedback reduces context-switching cost.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.IterationPlanner
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ColorGrade
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.IterationFeedback
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.QuickAction
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IterationSheet(
  decisions: EventDecisions,
  onDismiss: () -> Unit,
  onSubmit: (IterationFeedback) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  // Selected chip set. Each chip toggles independently.
  var selectedActions by remember { mutableStateOf<Set<QuickAction>>(emptySet()) }
  val canSubmit = selectedActions.isNotEmpty()
  val baseVersion = decisions.versionCount.coerceAtLeast(1)
  val currentColorGrade = decisions.timelineFinal?.shots?.firstOrNull()?.colorGrade
    ?: ColorGrade.NEUTRAL

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        "想怎么改？",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        "选一项或多项，AI 会用现有时间线快速重渲。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        QuickActionChip(
          label = "去字幕",
          icon = Icons.Outlined.Subtitles,
          selected = QuickAction.REMOVE_CAPTIONS in selectedActions,
          onClick = { selectedActions = selectedActions.toggle(QuickAction.REMOVE_CAPTIONS) },
          modifier = Modifier.weight(1f),
        )
        QuickActionChip(
          label = "换调色",
          icon = Icons.Outlined.Palette,
          selected = QuickAction.CHANGE_COLOR_GRADE in selectedActions,
          onClick = { selectedActions = selectedActions.toggle(QuickAction.CHANGE_COLOR_GRADE) },
          modifier = Modifier.weight(1f),
        )
      }

      FilledTonalButton(
        onClick = {
          val feedback = IterationPlanner.fromQuickActions(
            iterationId = "iter_%03d".format(baseVersion + 1),
            baseTimelineVersion = baseVersion,
            actions = selectedActions.toList(),
            currentColorGrade = currentColorGrade,
          )
          onSubmit(feedback)
        },
        enabled = canSubmit,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
        Text(text = "  让 AI 优化", fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionChip(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  FilterChip(
    modifier = modifier,
    selected = selected,
    onClick = onClick,
    label = { Text(label) },
    leadingIcon = {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 4.dp),
      )
    },
    colors = FilterChipDefaults.filterChipColors(
      selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
      selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ),
  )
}

private fun <T> Set<T>.toggle(value: T): Set<T> =
  if (value in this) this - value else this + value
