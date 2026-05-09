/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Process viewer for the on-device VlogPilot pipeline. The screen is built as
 * a compact editing console: input assets, agent outputs, timeline decisions,
 * critique notes, and the rendered candidate stay in one inspectable flow.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

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
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.PromptStrings
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.EventScoutAgent
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.VlmAnnotator
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.MediaLoader
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.GenerationIntent
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.PowerProfile
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.VlogPilotRunConfig
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.VlmTags
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventCandidateSnapshot
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionManifest
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionStatus
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.StagePerf
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class StageUi(val label: String, val done: Boolean)

internal data class FriendlyProgress(
  val title: String,
  val detail: String,
  val current: Int = 0,
  val total: Int = 0,
)

internal fun eventSubtitle(d: EventDecisions): String {
  val range = d.event?.let { formatEventRange(it.startEpochMs, it.endEpochMs) }
  val place = d.event?.placeHint?.takeIf { it.isNotBlank() }
  return listOfNotNull(range, place, d.eventId).joinToString(" · ")
}

internal fun videoSubtitle(d: EventDecisions): String {
  val range = d.event?.let { formatEventRange(it.startEpochMs, it.endEpochMs) }
  val timeline = d.timelineFinal ?: d.timelineV1
  val duration = timeline?.shots?.sumOf { it.durationSec.toDouble() }?.let { formatSec(it) }
  return listOfNotNull(range, "${d.inputAssets.size} 个素材", duration).joinToString(" · ")
}

internal fun storyTitle(candidate: EventCandidateSnapshot): String =
  storyTitle(candidate.event, candidate.scoutSummary, candidate.scoutEventType)

internal fun storyTitle(d: EventDecisions): String {
  cleanStoryTitle(d.director?.title)?.let { return it }
  cleanStoryTitle(d.event?.userCuration?.intentText)?.let { return it }
  cleanStoryTitle(d.event?.userBrief?.rawText)?.let { return it }
  return d.event?.let { event ->
    val semanticText = listOfNotNull(
      d.memory?.storylineSummary?.takeIf { it.isNotBlank() },
      d.audience?.emotionalPayoff?.takeIf { it.isNotBlank() },
      d.director?.tone?.takeIf { it.isNotBlank() },
    ).joinToString(" ")
    storyTitle(event, semanticText, d.director?.tone.orEmpty())
  } ?: fallbackVideoTitle(d)
}

private fun cleanStoryTitle(raw: String?): String? {
  val title = raw
    ?.trim()
    ?.trim('「', '」', '“', '”', '"', '\'', '《', '》')
    ?.replace(Regex("\\s+"), " ")
    .orEmpty()
  return title.takeIf { it.isNotBlank() && !isGenericStoryTitle(it) }
}

private fun isGenericStoryTitle(title: String): Boolean {
  val normalized = title.lowercase(Locale.ROOT)
  return normalized in setOf(
    "片段",
    "生活片段",
    "日常片段",
    "日常记录",
    "一段片段",
    "视频片段",
    "短片",
    "视频",
    "小视频",
    "vlog",
    "一段 vlog",
    "一段回忆视频",
    "回忆视频",
  )
}

private fun fallbackVideoTitle(d: EventDecisions): String {
  val asset = d.inputAssets.minByOrNull { asset ->
    if (asset.takenEpochMs > 0L) asset.takenEpochMs else Long.MAX_VALUE
  }
  val day = asset?.takenEpochMs?.takeIf { it > 0L }?.let(::storyDay)
  val name = asset?.displayName
    ?.substringBeforeLast('.', missingDelimiterValue = asset.displayName)
    ?.replace('_', ' ')
    ?.takeIf { it.isNotBlank() }
  return listOfNotNull(day, name).joinToString(" · ").ifBlank { "未命名视频" }
}

internal fun storyTitle(event: Event, summary: String, eventType: String): String {
  val day = storyDay(event.startEpochMs)
  val theme = storyTheme(eventType, summary)
  return "${day}的$theme"
}

internal fun storyMeta(candidate: EventCandidateSnapshot): String =
  listOfNotNull(
    formatEventRange(candidate.event.startEpochMs, candidate.event.endEpochMs).takeIf { it.isNotBlank() },
    "${candidate.event.assetIds.size} 张/段素材",
    "${candidate.realVideoCount} 段视频",
    formatSec(candidate.realVideoSeconds.toDouble()).takeIf { candidate.realVideoSeconds > 0f },
  ).joinToString(" · ")

internal fun compactStoryMeta(candidate: EventCandidateSnapshot): String =
  listOfNotNull(
    storyDay(candidate.event.startEpochMs),
    "${candidate.event.assetIds.size}素材",
    if (candidate.realVideoCount > 0) "${candidate.realVideoCount}视频" else null,
    formatSec(candidate.realVideoSeconds.toDouble()).takeIf { candidate.realVideoSeconds > 0f },
  ).joinToString(" · ")

internal fun storyListHint(candidate: EventCandidateSnapshot): String = when {
  candidate.status == EventSelectionStatus.COMPLETED -> "已经剪过"
  candidate.status == EventSelectionStatus.EXCLUDED -> "已放到不喜欢"
  candidate.realVideoSeconds >= 180f -> "视频素材很足"
  candidate.realVideoCount >= 8 -> "视频片段多"
  candidate.scoutRecommended -> "画面连贯"
  candidate.gpsAssetCount > 0 -> "有地点线索"
  else -> storyTheme(candidate.scoutEventType, candidate.scoutSummary)
}

internal fun storyReason(candidate: EventCandidateSnapshot): String {
  val theme = storyTheme(candidate.scoutEventType, candidate.scoutSummary)
  val videoHint = when {
    candidate.realVideoSeconds >= 60f -> "视频素材比较充足"
    candidate.realVideoCount > 0 -> "有可用的视频片段"
    else -> "照片内容比较集中"
  }
  val scoutHint = when {
    candidate.scoutRecommended -> "画面和内容比较适合剪成 vlog"
    candidate.scoutSummary.isNotBlank() -> "我已经看过这组缩略图，内容有一定连贯性"
    else -> "这组素材时间上比较接近，可以先作为一个故事看看"
  }
  return "这像是一组$theme，$videoHint，$scoutHint。"
}

internal fun storyTheme(eventType: String, summary: String): String {
  val text = "$eventType $summary".lowercase(Locale.ROOT)
  return when {
    listOf("food", "meal", "pizza", "restaurant", "dinner", "lunch", "breakfast", "餐", "美食").any { it in text } -> "美食时光"
    listOf("travel", "trip", "street", "hotel", "beach", "outdoor", "旅行", "出游").any { it in text } -> "出游回忆"
    listOf("zoo", "animal", "pet", "dog", "cat", "动物").any { it in text } -> "动物故事"
    listOf("child", "kid", "people", "family", "friend", "person", "人物", "孩子", "家人").any { it in text } -> "家庭时光"
    else -> "日常记录"
  }
}

internal fun storyDay(epochMs: Long): String {
  if (epochMs <= 0L) return "今天"
  val fmt = SimpleDateFormat("M月d日", Locale.getDefault())
  return fmt.format(Date(epochMs))
}

internal fun storyBrowseCategories(candidates: List<EventCandidateSnapshot>): List<StoryBrowseCategory> =
  StoryBrowseCategory.entries.filter { category ->
    category == StoryBrowseCategory.Recommended ||
      category == StoryBrowseCategory.All ||
      candidates.any { matchesStoryCategory(it, category) }
  }

internal fun matchesStoryCategory(candidate: EventCandidateSnapshot, category: StoryBrowseCategory): Boolean = when (category) {
  StoryBrowseCategory.Recommended -> candidate.status == EventSelectionStatus.SELECTED || candidate.scoutRecommended
  StoryBrowseCategory.All -> true
  StoryBrowseCategory.Travel -> storyTheme(candidate.scoutEventType, candidate.scoutSummary) == "出游回忆"
  StoryBrowseCategory.Family -> storyTheme(candidate.scoutEventType, candidate.scoutSummary) == "家庭时光"
  StoryBrowseCategory.Food -> storyTheme(candidate.scoutEventType, candidate.scoutSummary) == "美食时光"
  StoryBrowseCategory.Animal -> storyTheme(candidate.scoutEventType, candidate.scoutSummary) == "动物故事"
  StoryBrowseCategory.Video -> candidate.realVideoCount >= 3 || candidate.realVideoSeconds >= 45f
  StoryBrowseCategory.Done -> candidate.status == EventSelectionStatus.COMPLETED
  StoryBrowseCategory.Hidden -> candidate.status == EventSelectionStatus.EXCLUDED
}

internal fun storySortComparator(mode: StorySortMode): Comparator<EventCandidateSnapshot> = when (mode) {
  StorySortMode.Recommended -> compareByDescending<EventCandidateSnapshot> { if (it.status == EventSelectionStatus.SELECTED) 1 else 0 }
    .thenByDescending { it.valueScore }
    .thenByDescending { it.realVideoSeconds }
    .thenByDescending { it.event.startEpochMs }
  StorySortMode.Newest -> compareByDescending { it.event.startEpochMs }
  StorySortMode.Oldest -> compareBy { it.event.startEpochMs }
}

internal fun friendlyProgress(progress: ProgressSnapshot, making: Boolean = false): FriendlyProgress {
  if (making) {
    return when {
      progress.stage == "queued" || progress.stage == "work_running" -> FriendlyProgress(
        title = "正在准备制作",
        detail = progress.detail.ifBlank { "我会按你选中的故事来剪，完成后会出现在“作品”里。" },
        current = progress.current,
        total = progress.total,
      )
      isStoryRefreshStage(progress.stage) -> FriendlyProgress(
        title = "正在重扫故事",
        detail = "正在重新浏览候选事件，不会开始导出视频。",
        current = progress.current,
        total = progress.total,
      )
      progress.stage == "ingest" -> FriendlyProgress(
        title = "正在扫描相册",
        detail = "正在读取最近 90 天的素材。",
        current = progress.current,
        total = progress.total,
      )
      progress.stage.startsWith("perceive") || progress.stage.startsWith("annotate") -> FriendlyProgress(
        title = "正在挑选入片画面",
        detail = "我在给素材打分，避开模糊、重复或不适合入片的画面。",
        current = progress.current,
        total = progress.total,
      )
      progress.stage.startsWith("render") -> FriendlyProgress(
        title = "正在导出视频",
        detail = "马上就能在“作品”里看到成片。",
        current = progress.current,
        total = progress.total,
      )
      else -> FriendlyProgress(
        title = progress.headline.ifBlank { "正在制作视频" },
        detail = progress.detail.ifBlank { "制作中，完成后会出现在“作品”里。" },
        current = progress.current,
        total = progress.total,
      )
    }
  }
  val defaultDetail = "我在找画面清楚、故事连贯、适合剪成视频的片段。"
  return when {
    isStoryRefreshStage(progress.stage) -> FriendlyProgress(
      title = "正在帮你看相册",
      detail = defaultDetail,
      current = progress.current,
      total = progress.total,
    )
    progress.stage == "download" -> FriendlyProgress(
      title = "正在准备模型",
      detail = "第一次使用需要先确认本地模型可以运行。",
      current = progress.current,
      total = progress.total,
    )
    progress.stage.startsWith("perceive") || progress.stage.startsWith("annotate") -> FriendlyProgress(
      title = "正在挑清楚的画面",
      detail = "我会避开模糊、重复或不适合入片的素材。",
      current = progress.current,
      total = progress.total,
    )
    progress.stage.startsWith("render") -> FriendlyProgress(
      title = "正在导出视频",
      detail = "马上就能在“视频”里看到成片。",
      current = progress.current,
      total = progress.total,
    )
    else -> FriendlyProgress(
      title = progress.headline.ifBlank { "正在制作视频" },
      detail = progress.detail.ifBlank { defaultDetail },
      current = progress.current,
      total = progress.total,
    )
  }
}

internal fun friendlyIntentLabel(intent: GenerationIntent): String = when (intent) {
  GenerationIntent.AUTO -> "自动帮我选"
  GenerationIntent.TRAVEL -> "旅行回忆"
  GenerationIntent.ZOO -> "动物园/宠物"
  GenerationIntent.PEOPLE -> "家人朋友"
  GenerationIntent.FOOD -> "美食日常"
}

internal fun friendlyPowerLabel(profile: PowerProfile): String = when (profile) {
  PowerProfile.LOW_POWER -> "快一点"
  PowerProfile.BALANCED -> "平衡"
  PowerProfile.HIGH_QUALITY -> "效果更好"
}

internal fun formatEventRange(startMs: Long, endMs: Long): String {
  val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
  if (startMs <= 0L || endMs <= 0L) return ""
  return "${fmt.format(Date(startMs))} - ${fmt.format(Date(endMs))}"
}

internal fun formatMs(ms: Long): String = when {
  ms < 1000 -> "${ms}ms"
  ms < 60_000 -> "${ms / 1000}s"
  else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

/** "刚刚 / 5 分钟前 / 2 小时前 / 3 天前 / 06-15" — Apple-style relative
 *  time, switching to absolute date when older than a week. */
internal fun formatRelativeTime(timestampMs: Long): String {
  if (timestampMs <= 0L) return ""
  val now = System.currentTimeMillis()
  val deltaSec = ((now - timestampMs) / 1000L).coerceAtLeast(0L)
  return when {
    deltaSec < 30 -> "刚刚"
    deltaSec < 60 -> "${deltaSec}秒前"
    deltaSec < 3600 -> "${deltaSec / 60}分钟前"
    deltaSec < 86_400 -> "${deltaSec / 3600}小时前"
    deltaSec < 604_800 -> "${deltaSec / 86_400}天前"
    else -> SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(timestampMs))
  }
}

internal fun formatSec(sec: Double): String = when {
  sec <= 0.0 -> "0s"
  sec < 60.0 -> "${"%.1f".format(Locale.US, sec)}s"
  else -> "${(sec / 60).toInt()}m${(sec % 60).toInt()}s"
}

internal fun shortId(id: String): String =
  if (id.length <= 8) id else id.take(6) + "…" + id.takeLast(4)

internal fun assetMeta(asset: Asset): String {
  val type = asset.mediaType.name.lowercase()
  val size = if (asset.widthPx > 0 && asset.heightPx > 0) "${asset.widthPx}x${asset.heightPx}" else "unknown size"
  val dur = if (asset.durationMs > 0) " / ${formatSec(asset.durationMs / 1000.0)}" else ""
  return "$type / $size$dur"
}

internal fun annotationSummary(tags: VlmTags?, videoSummary: String? = null): String {
  if (tags == null) return "未读取到标注缓存"
  if (tags.scene.isBlank() && tags.subjects.isEmpty() && tags.action.isBlank() && tags.mood.isBlank()) {
    return "VLM 标签为空"
  }
  return listOfNotNull(
    tags.visualDescription.takeIf { it.isNotBlank() },
    tags.scene.takeIf { it.isNotBlank() },
    tags.subjects.takeIf { it.isNotEmpty() }?.joinToString("、"),
    tags.action.takeIf { it.isNotBlank() },
    tags.mood.takeIf { it.isNotBlank() },
    tags.salient.takeIf { it.isNotBlank() },
    tags.composition.takeIf { it.isNotBlank() },
    tags.lighting.takeIf { it.isNotBlank() },
    tags.motionHint.takeIf { it.isNotBlank() },
    videoSummary?.takeIf { it.isNotBlank() },
  ).joinToString(" · ")
}

