/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Full-screen "I'll pick the assets myself" creator. Jobs-style minimal:
 *   1) one big question: "你想做一条什么样的视频？"
 *   2) one OutlinedTextField for free-text intent (optional)
 *   3) one LazyVerticalGrid of recent assets, multi-select via tap
 *   4) one sticky bottom bar: live count + 开始制作 button
 *
 * Deliberately NO chip rails for duration / pace / color / captions. The
 * IntentParserAgent extracts those from the textfield. Power users who want
 * fine control type "30秒 / 慢节奏 / 温暖" — same effect, fewer controls.
 *
 * Empty intent submission triggers EmptyIntentDialog (soft guidance), not
 * a hard block. < 3 selected assets still hard-disables the Start button —
 * a vlog with 1-2 sources doesn't have enough to work with.
 */
package com.vlogcopilot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vlogcopilot.schemas.Asset
import com.vlogcopilot.schemas.MediaType

private const val MIN_SELECTION = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CuratorScreen(
  assets: List<Asset>,
  loading: Boolean,
  /** Inline error banner shown at the top of the screen. Null = no error.
   *  The host clears this when the user submits successfully or dismisses. */
  errorMessage: String? = null,
  onBack: () -> Unit,
  onSubmit: (selectedIds: List<String>, intentText: String) -> Unit,
  onDismissError: () -> Unit = {},
) {
  var intentText by remember { mutableStateOf("") }
  var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
  var showEmptyIntentDialog by remember { mutableStateOf(false) }

  // Sort newest-first for selection — matches the way users mentally browse Photos.
  val sortedAssets = remember(assets) { assets.sortedByDescending { it.takenEpochMs } }
  val selectedCount = selectedIds.size
  val canSubmit = selectedCount >= MIN_SELECTION

  val attemptSubmit: () -> Unit = {
    if (canSubmit) {
      if (intentText.isBlank()) {
        showEmptyIntentDialog = true
      } else {
        onSubmit(selectedIds.toList(), intentText.trim())
      }
    }
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      androidx.compose.material3.CenterAlignedTopAppBar(
        title = {
          Text(
            "新作品",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.Outlined.ArrowBack,
              contentDescription = "返回",
              tint = com.vlogcopilot.ui.theme.VlogCopilotTokens.colors.accent,
            )
          }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
          containerColor = MaterialTheme.colorScheme.background,
          scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
      )
    },
    bottomBar = {
      CuratorBottomBar(
        selectedCount = selectedCount,
        videoCount = sortedAssets.count { it.id in selectedIds && it.mediaType == MediaType.VIDEO },
        videoSec = sortedAssets
          .filter { it.id in selectedIds && it.mediaType == MediaType.VIDEO }
          .sumOf { (it.durationMs / 1000).toInt() },
        canSubmit = canSubmit,
        onSubmit = attemptSubmit,
      )
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .imePadding(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      if (errorMessage != null) {
        item {
          ErrorBanner(message = errorMessage, onDismiss = onDismissError)
        }
      }
      item {
        Text(
          "你想做一条什么样的视频？",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
      item {
        OutlinedTextField(
          value = intentText,
          onValueChange = { intentText = it },
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 110.dp),
          placeholder = {
            Text(
              "比如：30秒怀旧风的成长vlog\n（不写也可以，AI 会自己想）",
              color = com.vlogcopilot.ui.theme.VlogCopilotTokens.colors.tertiaryLabel,
            )
          },
          shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
          colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = com.vlogcopilot.ui.theme.VlogCopilotTokens.colors.opaqueSeparator,
            focusedBorderColor = com.vlogcopilot.ui.theme.VlogCopilotTokens.colors.accent,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
          ),
          maxLines = 6,
        )
      }
      item {
        Text(
          "挑你的素材",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
      }
      if (loading) {
        item {
          Text(
            "正在读取相册…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else if (sortedAssets.isEmpty()) {
        item {
          Text(
            "没有找到可用素材。检查相册权限，或拍点新照片再来。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }
      } else {
        item {
          // The grid lives inside its own item so the LazyColumn can wrap it
          // and let the textfield + bottom bar dominate the layout.
          CuratedAssetGrid(
            assets = sortedAssets,
            selectedIds = selectedIds,
            onToggle = { id ->
              selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
            },
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 320.dp, max = 800.dp),
          )
        }
      }
    }
  }

  if (showEmptyIntentDialog) {
    EmptyIntentDialog(
      onDismiss = { showEmptyIntentDialog = false },
      onPickExample = { example ->
        intentText = example
        showEmptyIntentDialog = false
      },
      onProceedAnyway = {
        showEmptyIntentDialog = false
        onSubmit(selectedIds.toList(), "")
      },
    )
  }
}

@Composable
private fun CuratorBottomBar(
  selectedCount: Int,
  videoCount: Int,
  videoSec: Int,
  canSubmit: Boolean,
  onSubmit: () -> Unit,
) {
  // Soft floating bottom bar — translucent enough that the asset grid above
  // peeks through, with a hairline separator at the top.
  val tokens = com.vlogcopilot.ui.theme.VlogCopilotTokens
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f)),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(0.5.dp)
        .background(tokens.colors.opaqueSeparator),
    )
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 20.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      val label = buildString {
        append("已选 $selectedCount 张")
        if (videoCount > 0) {
          append(" · $videoCount 段视频 ${videoSec}s")
        }
        when {
          selectedCount == 0 -> append(" · 至少挑 $MIN_SELECTION 张")
          selectedCount < MIN_SELECTION -> append(" · 还差 ${MIN_SELECTION - selectedCount} 张")
          else -> append(" · 估计成片 ${estimatedDurationSec(selectedCount, videoSec)}s")
        }
      }
      Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.secondaryLabel,
      )
      com.vlogcopilot.ui.PrimaryActionButton(
        text = "开始制作",
        icon = Icons.Outlined.AutoAwesome,
        onClick = onSubmit,
        enabled = canSubmit,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

/** Rough heuristic: ~2.5s per still + clip the videoSec contribution at half its length.
 *  Just for the bottom-bar sneak peek — Director ultimately decides. */
private fun estimatedDurationSec(selectedCount: Int, videoSec: Int): Int {
  val stillContribution = (selectedCount * 2.5f).toInt()
  val videoContribution = (videoSec / 2)
  return (stillContribution + videoContribution).coerceIn(15, 45)
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
  val tokens = com.vlogcopilot.ui.theme.VlogCopilotTokens
  androidx.compose.material3.Surface(
    color = tokens.colors.systemRed.copy(alpha = if (tokens.colors.isDark) 0.22f else 0.12f),
    contentColor = tokens.colors.systemRed,
    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    androidx.compose.foundation.layout.Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
      androidx.compose.material3.Icon(
        Icons.Outlined.ErrorOutline,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
      )
      Text(
        message,
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyMedium,
      )
      com.vlogcopilot.ui.PlainTextButton(
        text = "知道了",
        onClick = onDismiss,
        color = tokens.colors.systemRed,
      )
    }
  }
}
