/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Full-album browser for VlogPilot. The story scanner can keep its
 * 90-day window, while this page shows the wider MediaStore library and links
 * assets back to the stories/videos that used them.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
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
  var selectedScope by remember { mutableStateOf(AlbumScope.All) }
  val loadedAssetIds = remember(assets) { assets.mapTo(mutableSetOf()) { it.id } }
  val albumUsages = remember(loadedAssetIds, usageByAssetId) {
    loadedAssetIds.mapNotNull { usageByAssetId[it] }
  }
  val filteredAssets = remember(assets, usageByAssetId, selectedScope) {
    assets.filter { asset -> selectedScope.matches(asset, usageByAssetId[asset.id]) }
  }
  val visibleAssets = remember(filteredAssets, visibleCount) {
    filteredAssets.take(visibleCount.coerceAtMost(filteredAssets.size))
  }
  val grouped = remember(visibleAssets) { visibleAssets.groupBy { assetDayLabel(it.takenEpochMs) } }
  val annotatedCount = remember(albumUsages) { albumUsages.count { it.annotated } }
  val selectedCount = remember(albumUsages) { albumUsages.count { it.selectedStoryIds.isNotEmpty() } }
  val shotCount = remember(albumUsages) { albumUsages.count { it.shotOrdersByVideo.isNotEmpty() } }
  val renderedCount = remember(albumUsages) { albumUsages.count { it.finishedVideoIds.isNotEmpty() } }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    AlbumHeader(
      totalCount = assets.size,
      filteredCount = filteredAssets.size,
      visibleCount = visibleAssets.size,
      selectedScope = selectedScope,
      loading = loading,
      onRefresh = onRefresh,
    )
    AlbumScopeBar(
      selected = selectedScope,
      onSelect = { selectedScope = it },
    )

    if (assets.isNotEmpty()) {
      AlbumAiSummary(
        annotatedCount = annotatedCount,
        selectedCount = selectedCount,
        shotCount = shotCount,
        renderedCount = renderedCount,
      )
    }

    if (loading) {
      LinearProgressIndicator(
        modifier = Modifier
          .fillMaxWidth()
          .height(4.dp)
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
    } else if (filteredAssets.isEmpty() && !loading) {
      Text(
        "这个筛选下没有内容。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    grouped.forEach { (day, dayAssets) ->
      AlbumDaySection(
        day = day,
        assets = dayAssets,
        usageByAssetId = usageByAssetId,
        onOpenAsset = { selectedAssetId = it },
      )
    }

    if (visibleAssets.size < filteredAssets.size) {
      FilledTonalButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onLoadMore,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
      ) {
        Text("加载更多", fontWeight = FontWeight.SemiBold)
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

private enum class AlbumScope(val label: String) {
  All("全部"),
  Videos("视频"),
  Photos("照片"),
  Annotated("已标注"),
  Used("已使用");

  fun matches(asset: Asset, usage: AssetUsage?): Boolean = when (this) {
    All -> true
    Videos -> asset.mediaType == MediaType.VIDEO
    Photos -> asset.mediaType == MediaType.IMAGE || asset.mediaType == MediaType.LIVE_PHOTO
    Annotated -> usage?.annotated == true
    Used -> usage?.selectedStoryIds?.isNotEmpty() == true ||
      usage?.shotOrdersByVideo?.isNotEmpty() == true ||
      usage?.finishedVideoIds?.isNotEmpty() == true
  }
}

@Composable
private fun AlbumHeader(
  totalCount: Int,
  filteredCount: Int,
  visibleCount: Int,
  selectedScope: AlbumScope,
  loading: Boolean,
  onRefresh: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text("相册", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
      Text(
        albumCountLine(totalCount, filteredCount, visibleCount, selectedScope),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    IconButton(onClick = onRefresh, enabled = !loading) {
      Icon(Icons.Outlined.Refresh, contentDescription = "重新读取相册")
    }
  }
}

@Composable
private fun AlbumScopeBar(
  selected: AlbumScope,
  onSelect: (AlbumScope) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    AlbumScope.entries.forEach { scope ->
      val selectedScope = selected == scope
      Surface(
        modifier = Modifier
          .weight(1f)
          .height(36.dp)
          .clickable { onSelect(scope) },
        shape = RoundedCornerShape(999.dp),
        color = if (selectedScope) {
          MaterialTheme.colorScheme.primaryContainer
        } else {
          MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        contentColor = if (selectedScope) {
          MaterialTheme.colorScheme.onPrimaryContainer
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
            scope.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selectedScope) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
          )
        }
      }
    }
  }
}

@Composable
private fun AlbumAiSummary(
  annotatedCount: Int,
  selectedCount: Int,
  shotCount: Int,
  renderedCount: Int,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    AlbumStatPill("标注", annotatedCount, Color(0xFF9CC2FF), Modifier.weight(1f))
    AlbumStatPill("故事", selectedCount, Color(0xFF78D8B2), Modifier.weight(1f))
    AlbumStatPill("入片", shotCount, Color(0xFFFFD166), Modifier.weight(1f))
    AlbumStatPill("成片", renderedCount, Color(0xFFFF9E9E), Modifier.weight(1f))
  }
}

@Composable
private fun AlbumStatPill(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(7.dp)
          .clip(CircleShape)
          .background(color),
      )
      Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(count.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun AlbumDaySection(
  day: String,
  assets: List<Asset>,
  usageByAssetId: Map<String, AssetUsage>,
  onOpenAsset: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Bottom,
    ) {
      Text(day, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Text("${assets.size} 项", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    assets.chunked(ALBUM_GRID_COLUMNS).forEach { rowAssets ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        rowAssets.forEach { asset ->
          AssetGridTile(
            asset = asset,
            usage = usageByAssetId[asset.id],
            onClick = { onOpenAsset(asset.id) },
            modifier = Modifier
              .weight(1f)
              .aspectRatio(1f),
          )
        }
        repeat(ALBUM_GRID_COLUMNS - rowAssets.size) {
          Spacer(
            modifier = Modifier
              .weight(1f)
              .aspectRatio(1f),
          )
        }
      }
    }
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
      .clip(RoundedCornerShape(3.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
      .clickable { onClick() },
  ) {
    AssetThumb(
      asset = asset,
      modifier = Modifier.fillMaxSize(),
      showType = false,
    )
    mediaCornerLabel(asset).takeIf { it.isNotBlank() }?.let { label ->
      Surface(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(4.dp),
        shape = RoundedCornerShape(999.dp),
        color = Color.Black.copy(alpha = 0.62f),
        contentColor = Color.White,
      ) {
        Text(
          label,
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
      }
    }
    AlbumStatusDots(
      usage = usage,
      modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(4.dp),
    )
  }
}

@Composable
private fun AlbumStatusDots(usage: AssetUsage?, modifier: Modifier = Modifier) {
  val colors = listOfNotNull(
    if (usage?.annotated == true) Color(0xFF9CC2FF) else null,
    if (usage?.selectedStoryIds?.isNotEmpty() == true) Color(0xFF78D8B2) else null,
    if (usage?.shotOrdersByVideo?.isNotEmpty() == true) Color(0xFFFFD166) else null,
    if (usage?.finishedVideoIds?.isNotEmpty() == true) Color(0xFFFF9E9E) else null,
  )
  if (colors.isEmpty()) return
  Row(
    modifier = modifier
      .clip(RoundedCornerShape(999.dp))
      .background(Color.Black.copy(alpha = 0.48f))
      .padding(horizontal = 4.dp, vertical = 3.dp),
    horizontalArrangement = Arrangement.spacedBy(3.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    colors.forEach { color ->
      Box(
        modifier = Modifier
          .size(6.dp)
          .clip(CircleShape)
          .background(color),
      )
    }
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
  MediaType.IMAGE -> ""
}

private fun albumCountLine(
  totalCount: Int,
  filteredCount: Int,
  visibleCount: Int,
  selectedScope: AlbumScope,
): String =
  if (selectedScope == AlbumScope.All) {
    "全相册 $totalCount 项 · 当前显示 $visibleCount 项"
  } else {
    "${selectedScope.label} $filteredCount 项 · 当前显示 $visibleCount 项"
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

private const val ALBUM_GRID_COLUMNS = 4
