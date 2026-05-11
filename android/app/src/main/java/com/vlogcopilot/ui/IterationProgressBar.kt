/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Compact progress strip shown above (or below) the Videos tab while an
 * iteration is in flight. It does NOT block interaction — the user can keep
 * scrolling, switch to another tab, or even submit a different feedback —
 * because RENDER_ONLY iterations finish in ~30s.
 *
 * Phases (matching IterationSnapshot.phase):
 *   queued / load / render → spinner-style, dismissible only by completing
 *   done                   → green check, auto-fades after a few seconds
 *   failed                 → error tint with the message and a "知道了" button
 */
package com.vlogcopilot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun IterationProgressStrip(
  snapshot: IterationSnapshot?,
  onDismiss: () -> Unit,
) {
  AnimatedVisibility(visible = snapshot != null) {
    val s = snapshot ?: return@AnimatedVisibility
    // Auto-fade the success state after ~5s so the chip doesn't linger forever
    // once the user has seen the new mp4 swap into the player.
    LaunchedEffect(s.phase, s.targetVersion, s.eventId) {
      if (s.phase == "done") {
        delay(5_000)
        onDismiss()
      }
    }
    val container = when (s.phase) {
      "done" -> MaterialTheme.colorScheme.secondaryContainer
      "failed" -> MaterialTheme.colorScheme.errorContainer
      else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val onContainer = when (s.phase) {
      "done" -> MaterialTheme.colorScheme.onSecondaryContainer
      "failed" -> MaterialTheme.colorScheme.onErrorContainer
      else -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = container,
      contentColor = onContainer,
      shape = RoundedCornerShape(14.dp),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        when (s.phase) {
          "done" -> Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
          )
          "failed" -> Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
          )
          else -> CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = onContainer,
          )
        }

        Column(modifier = Modifier.weight(1f)) {
          val title = when (s.phase) {
            "done" -> "已优化为 v${s.targetVersion}"
            "failed" -> "优化失败"
            else -> "正在优化 v${s.baseVersion}→v${s.targetVersion}"
          }
          Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (s.message.isNotBlank()) {
            Text(
              s.message,
              style = MaterialTheme.typography.bodySmall,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        if (s.phase == "failed") {
          TextButton(onClick = onDismiss) { Text("知道了") }
        } else if (s.phase == "done") {
          // Friendly hint of the magic moment — also serves as a static badge.
          Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
        }
      }
    }
  }
}
