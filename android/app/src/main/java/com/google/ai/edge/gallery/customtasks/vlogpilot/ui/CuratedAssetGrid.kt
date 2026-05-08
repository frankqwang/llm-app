/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Multi-select thumbnail grid for the Curator (user-driven story creation).
 *
 * Differences from CommonUi.AssetStrip / AssetThumb:
 *   - LazyVerticalGrid (not LazyRow) so users can scroll a large album in
 *     a familiar phone-photo-grid pattern
 *   - per-cell selection state with primaryContainer overlay + checkmark
 *   - tap to toggle (no long-press; we're already in "select mode")
 *   - small video duration badge in bottom-left for video assets
 *
 * Layout: GridCells.Adaptive(96.dp) so it adapts to screen widths without us
 * picking a specific column count. 4dp inter-cell gap. Each cell aspect ratio
 * 3:4 to preview both portrait + landscape source material reasonably.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CuratedAssetGrid(
  assets: List<Asset>,
  selectedIds: Set<String>,
  onToggle: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 96.dp),
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    items(assets, key = { it.id }) { asset ->
      CuratedAssetCell(
        asset = asset,
        selected = asset.id in selectedIds,
        onClick = { onToggle(asset.id) },
      )
    }
  }
}

@Composable
private fun CuratedAssetCell(
  asset: Asset,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val context = LocalContext.current
  val bitmap by produceState<Bitmap?>(initialValue = null, asset.id, asset.contentUri) {
    value = withContext(Dispatchers.IO) {
      MediaLoader.loadImage(context, asset, maxSide = 240)
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
          it.border(
            width = 2.dp,
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(10.dp),
          )
        } else {
          it.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(10.dp),
          )
        }
      },
  ) {
    val bmp = bitmap
    if (bmp != null) {
      Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = asset.displayName,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    }
    if (selected) {
      // Soft tint overlay so the photo stays readable but the selection is unambiguous.
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
      )
      Icon(
        Icons.Outlined.CheckCircle,
        contentDescription = "已选",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(4.dp)
          .size(22.dp)
          .background(Color.White, RoundedCornerShape(99.dp))
          .padding(1.dp),
      )
    }
    if (asset.mediaType == MediaType.VIDEO) {
      Surface(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(4.dp),
        shape = RoundedCornerShape(99.dp),
        color = Color.Black.copy(alpha = 0.62f),
        contentColor = Color.White,
      ) {
        androidx.compose.foundation.layout.Row(
          modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Icon(
            Icons.Outlined.PlayCircle,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
          )
          Text(
            text = formatVideoSec(asset.durationMs),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
  }
}

private fun formatVideoSec(durMs: Long): String {
  val sec = (durMs / 1000).toInt()
  return if (sec < 60) "${sec}s" else "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
}
