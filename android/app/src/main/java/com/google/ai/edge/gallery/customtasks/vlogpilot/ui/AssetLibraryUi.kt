/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Full-album browser for VlogPilot. The story scanner can keep its
 * 90-day window, while this page shows the wider MediaStore library and links
 * assets back to the stories/videos that used them.
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PhotoLibrary
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.PerceptionCache
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.MediaLoader
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionManifest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Holds the album-tab UI state across the LazyListScope items + the
 *  detail dialog. Hoisted so the dialog can render outside the list scope
 *  (Dialogs aren't valid inside LazyListScope). */
internal class AssetLibraryUiState {
  var selectedAssetId by mutableStateOf<String?>(null)
  internal var selectedScope by mutableStateOf(AlbumScope.All)
  var autoLoadRequested by mutableStateOf(false)
}

@Composable
internal fun rememberAssetLibraryUiState(): AssetLibraryUiState =
  remember { AssetLibraryUiState() }

/** Yields the album browser into a parent [LazyListScope]. Each photo row
 *  becomes its own LazyColumn item — the OS only composes / decodes the
 *  rows currently in the viewport, which is the difference between "smooth
 *  like the system gallery" and "redecode 60 thumbnails on every scroll
 *  tick". The detail dialog is rendered separately via [AssetLibraryDialog]
 *  because Dialog cannot live inside LazyListScope. */
internal fun LazyListScope.assetLibraryItems(
  state: AssetLibraryUiState,
  assets: List<Asset>,
  visibleCount: Int,
  loading: Boolean,
  errorMessage: String?,
  usageByAssetId: Map<String, AssetUsage>,
  onLoad: () -> Unit,
  onRefresh: () -> Unit,
  onLoadMore: () -> Unit,
) {
  // Filter snapshots — these are pure data so plain val is fine; LazyListScope
  // body re-evaluates whenever a state read in the parent recomposes.
  val filteredAssets = assets.filter { asset -> state.selectedScope.matches(asset, usageByAssetId[asset.id]) }
  val visibleAssets = filteredAssets.take(visibleCount.coerceAtMost(filteredAssets.size))
  val grouped = visibleAssets.groupBy { assetDayLabel(it.takenEpochMs) }
  val loadedAssetIds = assets.mapTo(mutableSetOf()) { it.id }
  val albumUsages = loadedAssetIds.mapNotNull { usageByAssetId[it] }
  val annotatedCount = albumUsages.count { it.isAnnotatedForUi() }
  val selectedCount = albumUsages.count { it.selectedStoryIds.isNotEmpty() }
  val shotCount = albumUsages.count { it.shotOrdersByVideo.isNotEmpty() }
  val renderedCount = albumUsages.count { it.finishedVideoIds.isNotEmpty() }

  item(key = "album-auto-load") {
    LaunchedEffect(assets.isEmpty()) {
      if (assets.isEmpty() && !state.autoLoadRequested) {
        state.autoLoadRequested = true
        onLoad()
      }
    }
  }
  item(key = "album-header") {
    AlbumHeader(
      totalCount = assets.size,
      filteredCount = filteredAssets.size,
      visibleCount = visibleAssets.size,
      selectedScope = state.selectedScope,
      loading = loading,
      onRefresh = onRefresh,
    )
  }
  item(key = "album-scope") {
    AlbumScopeBar(
      selected = state.selectedScope,
      onSelect = { state.selectedScope = it },
    )
  }
  if (assets.isNotEmpty()) {
    item(key = "album-summary") {
      AlbumAiSummary(
        annotatedCount = annotatedCount,
        selectedCount = selectedCount,
        shotCount = shotCount,
        renderedCount = renderedCount,
      )
    }
  }
  if (loading) {
    item(key = "album-progress") {
      LinearProgressIndicator(
        modifier = Modifier
          .fillMaxWidth()
          .height(4.dp)
          .clip(RoundedCornerShape(999.dp)),
      )
    }
  }
  errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
    item(key = "album-error") {
      Text(
        "相册读取失败：$message",
        style = MaterialTheme.typography.bodyMedium,
        color = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens.colors.systemRed,
      )
    }
  }
  if (assets.isEmpty() && !loading) {
    item(key = "album-empty") {
      Text(
        "还没有读到相册内容。检查相册权限后点右上角重新读取。",
        style = MaterialTheme.typography.bodyMedium,
        color = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens.colors.secondaryLabel,
      )
    }
  } else if (filteredAssets.isEmpty() && !loading) {
    item(key = "album-empty-filter") {
      Text(
        "这个筛选下没有内容。",
        style = MaterialTheme.typography.bodyMedium,
        color = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens.colors.secondaryLabel,
      )
    }
  }

  // Each day = 1 sticky-style header item + N row items. LazyColumn now
  // virtualizes per-row rather than treating the whole album as one giant
  // composable.
  grouped.forEach { (day, dayAssets) ->
    item(key = "day-$day", contentType = "day-header") {
      AlbumDayHeader(day = day, count = dayAssets.size)
    }
    val rows = dayAssets.chunked(ALBUM_GRID_COLUMNS)
    items(
      count = rows.size,
      key = { i -> "$day-row-$i" },
      contentType = { "asset-row" },
    ) { i ->
      AlbumGridRow(
        assets = rows[i],
        usageByAssetId = usageByAssetId,
        onOpenAsset = { state.selectedAssetId = it },
      )
    }
  }

  if (visibleAssets.size < filteredAssets.size) {
    item(key = "album-load-more") {
      com.google.ai.edge.gallery.customtasks.vlogpilot.ui.TintedActionButton(
        text = "加载更多",
        onClick = onLoadMore,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

/** Renders the asset detail dialog (when an asset is selected). Call this
 *  ALONGSIDE [assetLibraryItems] in the host composable. */
@Composable
internal fun AssetLibraryDialog(
  state: AssetLibraryUiState,
  assets: List<Asset>,
  usageByAssetId: Map<String, AssetUsage>,
  decisions: List<EventDecisions>,
  manifest: EventSelectionManifest?,
  onAnnotateAsset: (Asset) -> Boolean,
  onOpenStory: (String) -> Unit,
  onOpenVideo: (String) -> Unit,
) {
  state.selectedAssetId
    ?.let { id -> assets.firstOrNull { it.id == id } }
    ?.let { asset ->
      AssetDetailDialog(
        asset = asset,
        usage = usageByAssetId[asset.id] ?: AssetUsage(),
        decisions = decisions,
        manifest = manifest,
        onAnnotateAsset = onAnnotateAsset,
        onDismiss = { state.selectedAssetId = null },
        onOpenStory = { eventId ->
          state.selectedAssetId = null
          onOpenStory(eventId)
        },
        onOpenVideo = { eventId ->
          state.selectedAssetId = null
          onOpenVideo(eventId)
        },
      )
    }
}

/** Header row for a day group. Compact and aligned with iOS Photos' style. */
@Composable
private fun AlbumDayHeader(day: String, count: Int) {
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 8.dp, bottom = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Bottom,
  ) {
    Text(day, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text("$count 项", style = MaterialTheme.typography.labelSmall, color = tokens.colors.tertiaryLabel)
  }
}

/** A single grid row of up to ALBUM_GRID_COLUMNS thumbnails. Stored as its
 *  own composable so LazyColumn can recycle / skip it when off-screen. */
@Composable
private fun AlbumGridRow(
  assets: List<Asset>,
  usageByAssetId: Map<String, AssetUsage>,
  onOpenAsset: (String) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    assets.forEach { asset ->
      AssetGridTile(
        asset = asset,
        usage = usageByAssetId[asset.id],
        onClick = { onOpenAsset(asset.id) },
        modifier = Modifier
          .weight(1f)
          .aspectRatio(1f),
      )
    }
    repeat(ALBUM_GRID_COLUMNS - assets.size) {
      Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
    }
  }
}

internal enum class AlbumScope(val label: String) {
  All("全部"),
  Videos("视频"),
  Photos("照片"),
  Annotated("已标注"),
  Used("已使用");

  fun matches(asset: Asset, usage: AssetUsage?): Boolean = when (this) {
    All -> true
    Videos -> asset.mediaType == MediaType.VIDEO
    Photos -> asset.mediaType == MediaType.IMAGE || asset.mediaType == MediaType.LIVE_PHOTO
    Annotated -> usage?.isAnnotatedForUi() == true
    Used -> usage?.selectedStoryIds?.isNotEmpty() == true ||
      usage?.shotOrdersByVideo?.isNotEmpty() == true ||
      usage?.finishedVideoIds?.isNotEmpty() == true
  }
}

private fun AssetUsage.isAnnotatedForUi(): Boolean =
  annotated ||
    perception?.let(::hasSemanticAnnotationForUi) == true

private fun chooseUiPerception(existing: Perception?, incoming: Perception?): Perception? =
  when {
    incoming == null -> existing
    existing == null -> incoming
    !hasSemanticAnnotationForUi(existing) && hasSemanticAnnotationForUi(incoming) -> incoming
    else -> existing
  }

private fun hasSemanticAnnotationForUi(perception: Perception): Boolean =
  perception.vlmTags.scene.isNotBlank() ||
    perception.vlmTags.subjects.isNotEmpty() ||
    perception.vlmTags.action.isNotBlank() ||
    perception.vlmTags.mood.isNotBlank() ||
    perception.vlmTags.salient.isNotBlank() ||
    perception.vlmTags.visualDescription.isNotBlank() ||
    perception.vlmTags.composition.isNotBlank() ||
    perception.vlmTags.lighting.isNotBlank() ||
    perception.vlmTags.motionHint.isNotBlank() ||
    perception.videoInsight.summary.isNotBlank() ||
    perception.videoInsight.visualDescription.isNotBlank() ||
    perception.videoInsight.actionArc.isNotBlank()

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
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.xs),
  ) {
    AlbumScope.entries.forEach { scope ->
      val isSelected = selected == scope
      val bg = if (isSelected) tokens.colors.accentTint
               else if (tokens.colors.isDark) tokens.colors.groupedSurfaceRaised
               else MaterialTheme.colorScheme.surface
      val fg = if (isSelected) tokens.colors.accent else MaterialTheme.colorScheme.onSurface
      Surface(
        modifier = Modifier
          .weight(1f)
          .height(36.dp)
          .clickable { onSelect(scope) },
        shape = RoundedCornerShape(50),
        color = bg,
        contentColor = fg,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
            scope.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
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
    if (usage?.isAnnotatedForUi() == true) Color(0xFF9CC2FF) else null,
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
  onAnnotateAsset: (Asset) -> Boolean,
  onDismiss: () -> Unit,
  onOpenStory: (String) -> Unit,
  onOpenVideo: (String) -> Unit,
) {
  val context = LocalContext.current
  var annotateRequested by remember(asset.id) { mutableStateOf(false) }
  var perception by remember(asset.id) { mutableStateOf(usage.perception) }

  LaunchedEffect(asset.id, usage.perception) {
    val cached = withContext(Dispatchers.IO) {
      PerceptionCache.get(context, asset) ?: PerceptionCache.get(context, asset.id)
    }
    perception = chooseUiPerception(usage.perception, cached)
  }

  LaunchedEffect(annotateRequested, asset.id) {
    if (!annotateRequested) return@LaunchedEffect
    repeat(45) {
      val cached = withContext(Dispatchers.IO) {
        PerceptionCache.get(context, asset) ?: PerceptionCache.get(context, asset.id)
      }
      perception = chooseUiPerception(perception, cached)
      if (cached?.let(::hasSemanticAnnotationForUi) == true) return@LaunchedEffect
      delay(2_000)
    }
  }

  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  val dialogColor = if (tokens.colors.isDark) Color(0xFF1C1C1E) else MaterialTheme.colorScheme.surface
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth(0.985f)
        .fillMaxHeight(0.92f),
      shape = RoundedCornerShape(18.dp),
      color = dialogColor,
      tonalElevation = 0.dp,
    ) {
      Column(
        modifier = Modifier
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        AssetHeroPreview(asset = asset)
        CompactAssetHeader(asset = asset)
        com.google.ai.edge.gallery.customtasks.vlogpilot.ui.TintedActionButton(
          text = if (annotateRequested) "已加入后台标注" else "用本地模型标注",
          icon = Icons.Outlined.AutoAwesome,
          enabled = !annotateRequested,
          onClick = {
            annotateRequested = onAnnotateAsset(asset)
          },
          modifier = Modifier.fillMaxWidth(),
        )

        DecisionSection(
          icon = Icons.Outlined.Search,
          title = "VLM 标注",
          subtitle = annotationSummary(perception?.vlmTags, perception?.videoInsight?.summary),
        ) {
          val p = perception
          if (p == null) {
            Text(
              "还没有 VLM 标注缓存。素材进入故事扫描或制作后会写入这里。",
              style = MaterialTheme.typography.bodyMedium,
              color = tokens.colors.secondaryLabel,
            )
          } else if (!hasSemanticAnnotationForUi(p)) {
            Text(
              "已有基础检测，但还没有 VLM 语义标注。通常是模型返回空、素材被质量过滤，或者旧缓存只保存了清晰度/亮度/人脸等基础信号。点上方按钮会强制重新用本地模型标注。",
              style = MaterialTheme.typography.bodyMedium,
              color = tokens.colors.secondaryLabel,
            )
          } else {
            // Quality summary as inline metric pills — visually distinct from
            // the long key/value list below.
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              QualityPill("清晰", "%.2f".format(Locale.US, p.sharpness))
              QualityPill("亮度", "%.2f".format(Locale.US, p.brightness))
              QualityPill("人脸", p.faces.size.toString())
              QualityPill("NSFW", "%.2f".format(Locale.US, p.nsfwScore))
            }
            if (p.isJunk) {
              Text(
                "已过滤：${p.junkReason}",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.systemOrange,
              )
            }
            com.google.ai.edge.gallery.customtasks.vlogpilot.ui.HairlineDivider(startInset = 0.dp)
            AnnotationKeyValues(p.vlmTags)
            VideoInsightKeyValues(p)
          }
        }

        if (usage.storyIds.isNotEmpty() || usage.shotOrdersByVideo.isNotEmpty()) {
          DecisionSection(icon = Icons.Outlined.Visibility, title = "关联", subtitle = "故事候选 / 成品视频") {
            usage.storyIds.take(4).forEach { eventId ->
              RelatedJumpRow(
                label = "故事",
                tint = tokens.colors.systemPurple,
                title = storyLabel(eventId, decisions, manifest),
                onClick = { onOpenStory(eventId) },
              )
            }
            usage.shotOrdersByVideo.keys.take(4).forEach { eventId ->
              RelatedJumpRow(
                label = "视频",
                tint = tokens.colors.accent,
                title = "${storyLabel(eventId, decisions, manifest)} · 镜头 ${usage.shotOrdersByVideo[eventId].orEmpty().joinToString(",")}",
                onClick = { onOpenVideo(eventId) },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AssetHeroPreview(asset: Asset) {
  val context = LocalContext.current
  val bitmap by produceState<Bitmap?>(initialValue = null, asset.id) {
    value = withContext(Dispatchers.IO) {
      MediaLoader.loadImage(context, asset, maxSide = 1800, preferRgb565 = false)
    }
  }
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  val heroShape = RoundedCornerShape(16.dp)
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 360.dp, max = 620.dp)
      .clip(heroShape)
      .background(if (tokens.colors.isDark) Color(0xFF111318) else tokens.colors.groupedBackground)
      .border(1.dp, tokens.colors.opaqueSeparator, heroShape),
    contentAlignment = Alignment.Center,
  ) {
    val bmp = bitmap
    if (bmp != null) {
      Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = asset.displayName,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
      )
    } else {
      Icon(
        Icons.Outlined.PhotoLibrary,
        contentDescription = null,
        tint = Color.White.copy(alpha = 0.72f),
        modifier = Modifier.size(34.dp),
      )
    }
    val badge = listOfNotNull(
      friendlyMediaType(asset),
      if (asset.durationMs > 0) formatSec(asset.durationMs / 1000.0) else null,
    ).joinToString(" · ")
    Surface(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(8.dp),
      shape = RoundedCornerShape(999.dp),
      color = Color.Black.copy(alpha = 0.58f),
      contentColor = Color.White,
    ) {
      Text(
        badge,
        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
private fun CompactAssetHeader(asset: Asset) {
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 2.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      asset.displayName.ifBlank { shortId(asset.id) },
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onBackground,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      compactAssetMeta(asset),
      style = MaterialTheme.typography.bodySmall,
      color = tokens.colors.secondaryLabel,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

private fun compactAssetMeta(asset: Asset): String = listOfNotNull(
  friendlyMediaType(asset),
  if (asset.widthPx > 0 && asset.heightPx > 0) "${asset.widthPx}×${asset.heightPx}" else null,
  if (asset.durationMs > 0) formatSec(asset.durationMs / 1000.0) else null,
  readableBytes(asset.sizeBytes).takeIf { it != "-" },
  assetDateTimeLabel(asset.takenEpochMs).takeIf { it.isNotBlank() },
).joinToString(" · ")

@Composable
private fun QualityPill(label: String, value: String) {
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  Column(
    modifier = Modifier
      .clip(RoundedCornerShape(10.dp))
      .background(if (tokens.colors.isDark) tokens.colors.groupedSurfaceRaised else MaterialTheme.colorScheme.surfaceVariant)
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = tokens.colors.tertiaryLabel)
    Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
  }
}

@Composable
private fun RelatedJumpRow(
  label: String,
  title: String,
  tint: androidx.compose.ui.graphics.Color,
  onClick: () -> Unit,
) {
  // Apple Settings-style cell: tinted category pill on left, title in middle,
  // chevron on right; whole row is the tap target.
  val tokens = com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .padding(vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .clip(RoundedCornerShape(8.dp))
        .background(tint.copy(alpha = if (tokens.colors.isDark) 0.24f else 0.14f))
        .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
      Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = tint,
        fontWeight = FontWeight.SemiBold,
      )
    }
    Text(
      title,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    Icon(
      Icons.Outlined.ChevronRight,
      contentDescription = "打开",
      tint = tokens.colors.tertiaryLabel,
      modifier = Modifier.size(20.dp),
    )
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
