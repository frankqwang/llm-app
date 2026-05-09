/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Full-album browser for VlogPilot. The story scanner can keep its
 * 90-day window, while this page shows the wider MediaStore library and links
 * assets back to the stories/videos that used them.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.PerceptionCache
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionManifest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AssetLibraryTab(
  assets: List<Asset>,
  visibleCount: Int,
  loading: Boolean,
  errorMessage: String?,
  usageByAssetId: Map<String, AssetUsage>,
  decisions: List<EventDecisions>,
  manifest: EventSelectionManifest?,
  onLoad: () -> Unit,
  onRefresh: () -> Unit,
  onLoadMore: () -> Unit,
  onOpenStory: (String) -> Unit,
  onOpenVideo: (String) -> Unit,
) {
  var autoLoadRequested by remember { mutableStateOf(false) }
  LaunchedEffect(assets.isEmpty()) {
    if (assets.isEmpty() && !autoLoadRequested) {
      autoLoadRequested = true
      onLoad()
    }
  }

  var selectedAssetId by remember { mutableStateOf<String?>(null) }
  val visibleAssets = remember(assets, visibleCount) { assets.take(visibleCount.coerceAtMost(assets.size)) }
  val grouped = remember(visibleAssets) { visibleAssets.groupBy { assetDayLabel(it.takenEpochMs) } }
  val annotatedCount = remember(usageByAssetId) { usageByAssetId.values.count { it.annotated } }
  val selectedCount = remember(usageByAssetId) { usageByAssetId.values.count { it.selectedStoryIds.isNotEmpty() } }
  val shotCount = remember(usageByAssetId) { usageByAssetId.values.count { it.shotOrdersByVideo.isNotEmpty() } }
  val renderedCount = remember(usageByAssetId) { usageByAssetId.values.count { it.finishedVideoIds.isNotEmpty() } }

  PanelCard(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f)) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
          Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.padding(9.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text("相册素材", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(
            "全相册 ${assets.size} 个 · 当前显示 ${visibleAssets.size} 个",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(onClick = onRefresh, enabled = !loading) {
          Icon(Icons.Outlined.Search, contentDescription = "重新读取相册")
        }
      }

      MetricGrid(
        items = listOf(
          MetricDatum("已标注", annotatedCount.toString(), annotatedCount > 0),
          MetricDatum("入选故事", selectedCount.toString(), selectedCount > 0),
          MetricDatum("已入片", shotCount.toString(), shotCount > 0),
          MetricDatum("已成片", renderedCount.toString(), renderedCount > 0),
        ),
        columns = 4,
      )

      if (loading) {
        LinearProgressIndicator(
          modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp)),
        )
      }
      errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
        Text(
          "相册读取失败：$message",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
      if (assets.isEmpty() && !loading) {
        Text(
          "还没有读到相册内容。检查相册权限后点右上角重新读取。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      grouped.forEach { (day, dayAssets) ->
        Text(day, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        dayAssets.chunked(3).forEach { rowAssets ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
          ) {
            rowAssets.forEach { asset ->
              AssetGridTile(
                asset = asset,
                usage = usageByAssetId[asset.id],
                onClick = { selectedAssetId = asset.id },
                modifier = Modifier
                  .weight(1f)
                  .aspectRatio(1f),
              )
            }
            repeat(3 - rowAssets.size) {
              Spacer(
                modifier = Modifier
                  .weight(1f)
                  .aspectRatio(1f),
              )
            }
          }
        }
      }

      if (visibleAssets.size < assets.size) {
        FilledTonalButton(
          modifier = Modifier.fillMaxWidth(),
          onClick = onLoadMore,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        ) {
          Text("加载更多", fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }

  selectedAssetId
    ?.let { id -> assets.firstOrNull { it.id == id } }
    ?.let { asset ->
      AssetDetailDialog(
        asset = asset,
        usage = usageByAssetId[asset.id] ?: AssetUsage(),
        decisions = decisions,
        manifest = manifest,
        onDismiss = { selectedAssetId = null },
        onOpenStory = { eventId ->
          selectedAssetId = null
          onOpenStory(eventId)
        },
        onOpenVideo = { eventId ->
          selectedAssetId = null
          onOpenVideo(eventId)
        },
      )
    }
}

@Composable
private fun AssetGridTile(
  asset: Asset,
  usage: AssetUsage?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surface)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f), RoundedCornerShape(8.dp))
      .clickable { onClick() },
  ) {
    AssetThumb(
      asset = asset,
      modifier = Modifier.fillMaxSize(),
      showType = false,
    )
    Surface(
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(5.dp),
      shape = RoundedCornerShape(999.dp),
      color = Color.Black.copy(alpha = 0.62f),
      contentColor = Color.White,
    ) {
      Text(
        mediaCornerLabel(asset),
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
    }
    Column(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(5.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      if (usage?.annotated == true) AssetBadge("已标注")
      if (usage?.selectedStoryIds?.isNotEmpty() == true) AssetBadge("入选")
      if (usage?.shotOrdersByVideo?.isNotEmpty() == true) AssetBadge("入片")
      if (usage?.finishedVideoIds?.isNotEmpty() == true) AssetBadge("成片")
    }
  }
}

@Composable
private fun AssetBadge(label: String) {
  Surface(
    shape = RoundedCornerShape(6.dp),
    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
  ) {
    Text(
      label,
      modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
    )
  }
}

@Composable
private fun AssetDetailDialog(
  asset: Asset,
  usage: AssetUsage,
  decisions: List<EventDecisions>,
  manifest: EventSelectionManifest?,
  onDismiss: () -> Unit,
  onOpenStory: (String) -> Unit,
  onOpenVideo: (String) -> Unit,
) {
  val context = LocalContext.current
  val perception by produceState<Perception?>(initialValue = usage.perception, asset.id) {
    if (usage.perception == null) {
      value = withContext(Dispatchers.IO) { PerceptionCache.get(context, asset) }
    }
  }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 760.dp),
      shape = RoundedCornerShape(18.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
    ) {
      Column(
        modifier = Modifier
          .verticalScroll(rememberScrollState())
          .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
          AssetThumb(
            asset = asset,
            modifier = Modifier
              .width(88.dp)
              .height(112.dp),
          )
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
              asset.displayName.ifBlank { shortId(asset.id) },
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            Text(assetMeta(asset), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
              assetDateTimeLabel(asset.takenEpochMs),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        MetricGrid(
          items = listOf(
            MetricDatum("类型", friendlyMediaType(asset)),
            MetricDatum("尺寸", if (asset.widthPx > 0 && asset.heightPx > 0) "${asset.widthPx}x${asset.heightPx}" else "-"),
            MetricDatum("时长", if (asset.durationMs > 0) formatSec(asset.durationMs / 1000.0) else "-"),
            MetricDatum("大小", readableBytes(asset.sizeBytes)),
          ),
          columns = 2,
        )

        DecisionSection(icon = Icons.Outlined.Search, title = "VLM 标注", subtitle = annotationSummary(perception?.vlmTags, perception?.videoInsight?.summary)) {
          val p = perception
          if (p == null) {
            Text("还没有 VLM 标注缓存。素材进入故事扫描或制作后会写入这里。", style = MaterialTheme.typography.bodySmall)
          } else {
            KeyValue(
              "质量",
              "sharp=${"%.2f".format(Locale.US, p.sharpness)} / bright=${"%.2f".format(Locale.US, p.brightness)} / faces=${p.faces.size} / nsfw=${"%.2f".format(Locale.US, p.nsfwScore)}",
            )
            KeyValue("过滤", if (p.isJunk) "junk: ${p.junkReason}" else "可用")
            AnnotationKeyValues(p.vlmTags)
            VideoInsightKeyValues(p)
          }
        }

        if (usage.storyIds.isNotEmpty() || usage.shotOrdersByVideo.isNotEmpty()) {
          DecisionSection(icon = Icons.Outlined.Visibility, title = "关联", subtitle = "故事候选 / 成品视频") {
            usage.storyIds.take(4).forEach { eventId ->
              RelatedJumpRow(
                label = "故事",
                title = storyLabel(eventId, decisions, manifest),
                onClick = { onOpenStory(eventId) },
              )
            }
            usage.shotOrdersByVideo.keys.take(4).forEach { eventId ->
              RelatedJumpRow(
                label = "视频",
                title = "${storyLabel(eventId, decisions, manifest)} · 镜头 ${usage.shotOrdersByVideo[eventId].orEmpty().joinToString(",")}",
                onClick = { onOpenVideo(eventId) },
              )
            }
          }
        } else {
          Text(
            "还没有关联到故事或成品视频。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun RelatedJumpRow(label: String, title: String, onClick: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      shape = RoundedCornerShape(7.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
      Text(label, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
    Text(
      title,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodySmall,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)) {
      Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(15.dp))
      Spacer(Modifier.width(3.dp))
      Text("打开", maxLines = 1)
    }
  }
}

private fun storyLabel(
  eventId: String,
  decisions: List<EventDecisions>,
  manifest: EventSelectionManifest?,
): String {
  decisions.firstOrNull { it.eventId == eventId }?.let { return storyTitle(it) }
  manifest?.candidates?.firstOrNull { it.eventId == eventId }?.let { return storyTitle(it) }
  return "事件 ${shortId(eventId)}"
}

private fun mediaCornerLabel(asset: Asset): String = when (asset.mediaType) {
  MediaType.VIDEO -> if (asset.durationMs > 0) formatSec(asset.durationMs / 1000.0) else "VID"
  MediaType.LIVE_PHOTO -> "LIVE"
  MediaType.IMAGE -> "IMG"
}

private fun friendlyMediaType(asset: Asset): String = when (asset.mediaType) {
  MediaType.IMAGE -> "图片"
  MediaType.VIDEO -> "视频"
  MediaType.LIVE_PHOTO -> "Live"
}

private fun assetDayLabel(ms: Long): String =
  if (ms <= 0L) "未知日期" else SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(ms))

private fun assetDateTimeLabel(ms: Long): String =
  if (ms <= 0L) "未知时间" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun readableBytes(bytes: Long): String = when {
  bytes <= 0L -> "-"
  bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
  bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)
  else -> "%.1f GB".format(Locale.US, bytes / 1024.0 / 1024.0 / 1024.0)
}
