/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Process viewer for the on-device VlogCopilot pipeline. The screen is built as
 * a compact editing console: input assets, agent outputs, timeline decisions,
 * critique notes, and the rendered candidate stay in one inspectable flow.
 */
package com.vlogcopilot

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
import androidx.compose.material.icons.outlined.Apps
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
import androidx.compose.material.icons.outlined.Settings
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
import com.vlogcopilot.agents.PromptStrings
import com.vlogcopilot.agents.EventScoutAgent
import com.vlogcopilot.agents.VlmAnnotator
import com.vlogcopilot.perception.MediaLoader
import com.vlogcopilot.runtime.GenerationIntent
import com.vlogcopilot.runtime.PowerProfile
import com.vlogcopilot.runtime.VlogCopilotRunConfig
import com.vlogcopilot.schemas.Asset
import com.vlogcopilot.schemas.Event
import com.vlogcopilot.schemas.MediaType
import com.vlogcopilot.schemas.Perception
import com.vlogcopilot.schemas.ShotSpec
import com.vlogcopilot.schemas.Timeline
import com.vlogcopilot.schemas.VlmTags
import com.vlogcopilot.worker.EventDecisions
import com.vlogcopilot.worker.EventCandidateSnapshot
import com.vlogcopilot.worker.EventSelectionManifest
import com.vlogcopilot.worker.EventSelectionStatus
import com.vlogcopilot.worker.StagePerf
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsCard(
  runConfig: VlogCopilotRunConfig,
  running: Boolean,
  onIntentSelect: (GenerationIntent) -> Unit,
  onPowerSelect: (PowerProfile) -> Unit,
  onOpenGallery: () -> Unit,
  onOpenModelManager: () -> Unit,
) {
  var showDeveloperEntrypoints by remember { mutableStateOf(false) }
  PanelCard {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      SectionHeader(
        icon = Icons.Outlined.Edit,
        title = "视频设置",
        subtitle = if (running) "正在制作时会先锁定设置。" else "告诉我你想要什么样的视频。",
      )
      ControlSegment(
        label = "想做什么样的视频？",
        options = GenerationIntent.entries.toList(),
        selected = runConfig.intent,
        optionLabel = ::friendlyIntentLabel,
        onSelect = onIntentSelect,
        enabled = !running,
      )
      ControlSegment(
        label = "生成偏好",
        options = PowerProfile.entries.toList(),
        selected = runConfig.powerProfile,
        optionLabel = ::friendlyPowerLabel,
        onSelect = onPowerSelect,
        enabled = !running,
      )
      com.vlogcopilot.ui.HairlineDivider(startInset = 0.dp)
      FilledTonalButton(
        onClick = onOpenModelManager,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("模型管理")
      }
      FilledTonalButton(
        onClick = { showDeveloperEntrypoints = !showDeveloperEntrypoints },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Outlined.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (showDeveloperEntrypoints) "收起开发者入口" else "开发者入口")
      }
      if (showDeveloperEntrypoints) {
        FilledTonalButton(
          onClick = onOpenGallery,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Outlined.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("打开 AI Edge Gallery")
        }
      }
    }
  }
}

@Composable
internal fun PromptDebugCard() {
  val tokens = com.vlogcopilot.ui.theme.VlogCopilotTokens
  var expanded by remember { mutableStateOf(false) }
  PanelCard {
    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { expanded = !expanded },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.colors.accentTint),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            Icons.Outlined.Visibility,
            contentDescription = null,
            tint = tokens.colors.accent,
            modifier = Modifier.size(16.dp),
          )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            "系统 Prompt / 调试信息",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            "默认收起，需要排查生成策略时再展开",
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.secondaryLabel,
          )
        }
        Icon(
          imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
          contentDescription = null,
          tint = tokens.colors.tertiaryLabel,
          modifier = Modifier.size(20.dp),
        )
      }
      com.vlogcopilot.ui.AnimatedExpand(expanded = expanded) {
        com.vlogcopilot.ui.HairlineDivider(startInset = 0.dp)
        Spacer(Modifier.height(12.dp))
        PromptCatalogInline()
      }
    }
  }
}

