/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Soft-guidance modal that fires when the user taps "开始制作" with an empty
 * intent text. We don't block the submission — the user chose "强烈引导但不
 * 强制必填" — but we offer 3 one-tap example seeds that load into the input
 * box, plus a "直接生成" escape hatch.
 *
 * The 3 examples cover the main vlog vibes (怀旧 / 欢快 / 简单串烧) so users
 * who don't know what to write get a visible menu of options.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val EXAMPLE_PROMPTS = listOf(
  "30秒怀旧风的成长记录",
  "做条欢快有节奏的",
  "简单串烧，结尾用最近一张",
)

@Composable
internal fun EmptyIntentDialog(
  onDismiss: () -> Unit,
  /** Called when the user taps an example chip — fills the textfield + dismisses. */
  onPickExample: (String) -> Unit,
  /** Called when the user explicitly says "I'll just generate" — submits with empty text. */
  onProceedAnyway: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("不写也可以", fontWeight = FontWeight.SemiBold) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          "AI 会自己想个故事，但写一句话能让它更懂你想要什么。",
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          "试试这些（点一下填到输入框）：",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          EXAMPLE_PROMPTS.forEach { example ->
            AssistChip(
              onClick = { onPickExample(example) },
              label = { Text(example) },
              colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
              ),
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onProceedAnyway) {
        Text("直接生成")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("我来写")
      }
    },
  )
}
