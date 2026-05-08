/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Bottom sheet that opens when the user taps "改一改" on a generated video.
 * Milestone C: full feedback surface — storyboard tap-to-target, free-text
 * input, and 4 quick chips (更快 / 更慢 / 去字幕 / 换调色).
 *
 * Submit logic:
 *   - When userText is empty AND no cell tapped: chip-only client-side path
 *     via IterationPlanner.fromQuickActions. Worker runs RENDER_ONLY (or
 *     GLOBAL if FASTER/SLOWER chip set).
 *   - When userText non-empty OR cell tapped: same fromQuickActions seed,
 *     but the worker also runs IntentParserAgent.parseFeedback to refine
 *     scope + populate parsedRevisions / parsedGlobal.
 *
 * Storyboard rendering uses the in-memory ShotSpec list (timeline_final.json)
 * — we don't reuse TimelineStoryboardBuilder.build because that produces a
 * single Bitmap; for tap interactivity we need separate Composable cells.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.MediaLoader
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.IterationPlanner
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ColorGrade
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Pace
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.QuickAction
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IterationSheet(
  decisions: EventDecisions,
  onDismiss: () -> Unit,
  onSubmit: (com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.IterationFeedback) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var selectedActions by remember { mutableStateOf<Set<QuickAction>>(emptySet()) }
  var feedbackText by remember { mutableStateOf("") }
  var targetedShotOrder by remember { mutableStateOf<Int?>(null) }

  val baseVersion = decisions.versionCount.coerceAtLeast(1)
  val timeline = decisions.timelineFinal ?: decisions.timelineV1
  val shots = timeline?.shots.orEmpty().sortedBy { it.order }
  val assetMap = remember(decisions.inputAssets) { decisions.inputAssets.associateBy { it.id } }
  val currentColorGrade = shots.firstOrNull()?.colorGrade ?: ColorGrade.NEUTRAL
  val currentPace = decisions.audience?.pace ?: Pace.BALANCED
  val canSubmit = selectedActions.isNotEmpty() || feedbackText.isNotBlank() || targetedShotOrder != null

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
        "想怎么改？",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
      )
      if (shots.isNotEmpty()) {
        Text(
          "点你想改的那一格（不点就改整片）。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StoryboardGrid(
          shots = shots,
          assetMap = assetMap,
          targetedOrder = targetedShotOrder,
          onTap = { order ->
            targetedShotOrder = if (targetedShotOrder == order) null else order
          },
        )
      }

      OutlinedTextField(
        value = feedbackText,
        onValueChange = { feedbackText = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
          Text(
            text = if (targetedShotOrder != null) {
              "比如：太静态了，换一张"
            } else {
              "比如：整体太慢，做成欢快的（不写也行）"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        },
        maxLines = 4,
      )

      // 4 quick chips arranged 2x2 so the labels stay readable.
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          QuickActionChip(
            label = "更快",
            icon = Icons.Outlined.FastForward,
            selected = QuickAction.FASTER_OVERALL in selectedActions,
            onClick = { selectedActions = selectedActions.toggle(QuickAction.FASTER_OVERALL) },
            modifier = Modifier.weight(1f),
          )
          QuickActionChip(
            label = "更慢",
            icon = Icons.Outlined.HourglassEmpty,
            selected = QuickAction.SLOWER_OVERALL in selectedActions,
            onClick = { selectedActions = selectedActions.toggle(QuickAction.SLOWER_OVERALL) },
            modifier = Modifier.weight(1f),
          )
        }
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
      }

      FilledTonalButton(
        onClick = {
          val targetedOrders = listOfNotNull(targetedShotOrder)
          val feedback = IterationPlanner.fromQuickActions(
            iterationId = "iter_%03d".format(baseVersion + 1),
            baseTimelineVersion = baseVersion,
            actions = selectedActions.toList(),
            targetedShotOrders = targetedOrders,
            userText = feedbackText.trim(),
            currentColorGrade = currentColorGrade,
            currentPace = currentPace,
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

@Composable
private fun StoryboardGrid(
  shots: List<ShotSpec>,
  assetMap: Map<String, Asset>,
  targetedOrder: Int?,
  onTap: (Int) -> Unit,
) {
  // 3-column adaptive grid mimics TimelineStoryboardBuilder's layout. Cap
  // the height so very long timelines don't dominate the sheet.
  val rows = (shots.size + 2) / 3
  val rowHeight = 120.dp
  val gridHeight = (rowHeight.value * rows.coerceIn(1, 3)).dp
  LazyVerticalGrid(
    columns = GridCells.Fixed(3),
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    userScrollEnabled = rows > 3,
  ) {
    items(shots, key = { it.order }) { shot ->
      StoryboardCell(
        shot = shot,
        asset = assetMap[shot.assetId],
        selected = targetedOrder == shot.order,
        onClick = { onTap(shot.order) },
      )
    }
  }
}

@Composable
private fun StoryboardCell(
  shot: ShotSpec,
  asset: Asset?,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val context = LocalContext.current
  // For video shots: prefer the trim-midpoint frame so the cell shows the
  // exact moment we're presenting in the rendered MP4. Falls back to image
  // load if frame extraction fails.
  val bitmap by produceState<Bitmap?>(
    initialValue = null,
    shot.assetId,
    shot.videoTrim?.startSec,
    shot.videoTrim?.endSec,
  ) {
    value = withContext(Dispatchers.IO) {
      if (asset == null) return@withContext null
      val trim = shot.videoTrim
      if (asset.mediaType != MediaType.IMAGE && trim != null) {
        val tSec = (trim.startSec + trim.endSec) / 2f
        MediaLoader.loadVideoFrame(context, asset, tSec, maxSide = 240)
          ?: MediaLoader.loadImage(context, asset, maxSide = 240)
      } else {
        MediaLoader.loadImage(context, asset, maxSide = 240)
      }
    }
  }
  Box(
    modifier = Modifier
      .aspectRatio(3f / 4f)
      .clip(RoundedCornerShape(10.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .clickable(onClick = onClick)
      .let {
        if (selected) {
          it.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
        } else {
          it.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
        }
      },
  ) {
    val bmp = bitmap
    if (bmp != null) {
      Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = "镜头 ${shot.order}",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    }
    if (selected) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
      )
    }
    // Order badge top-left, like TimelineStoryboardBuilder's badge.
    Surface(
      modifier = Modifier
        .align(Alignment.TopStart)
        .padding(4.dp),
      shape = RoundedCornerShape(99.dp),
      color = Color.Black.copy(alpha = 0.7f),
      contentColor = Color.White,
    ) {
      Text(
        text = "#${shot.order}  ${"%.1fs".format(shot.durationSec)}",
        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
      )
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
