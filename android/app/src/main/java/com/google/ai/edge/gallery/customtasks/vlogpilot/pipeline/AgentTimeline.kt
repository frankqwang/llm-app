/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Reads filesDir/_state_history.jsonl into a structured timeline so the UI
 * can show the AI's work the way Claude shows tool calls: chronological
 * list of {stage, time, detail} entries, grouped by event when applicable.
 *
 * The history file is the only persistent log of the pipeline — useful both
 * for the "currently making" progress card (live tail) and the "已完成的
 * 作品详情→制作过程" view (replay).
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One row from `_state_history.jsonl`. `eventId` is best-effort extracted
 *  from the detail field so the detail page can filter to a single vlog. */
data class AgentTimelineEntry(
  val timestampMs: Long,
  val stage: String,
  val detail: String,
) {
  /** Most stages embed the eventId as the first space-separated token in
   *  detail — see StateBreadcrumb.fromProgress for the format. */
  val eventId: String? get() = when (stage) {
    in EVENT_SCOPED_STAGES -> detail.substringBefore(' ').takeIf { it.isNotBlank() }
    else -> null
  }

  /** Visual category — drives tint and icon in the timeline UI. */
  val category: Category get() = when (stage) {
    "download" -> Category.SETUP
    "ingest", "ingest_done" -> Category.SETUP
    "event_scout", "event_select_progress" -> Category.PLANNING
    "perceive", "annotate", "annotate_done", "vlm_step" -> Category.PERCEPTION
    "event_start" -> Category.PIPELINE
    "event_stage" -> when {
      detail.contains(" browse ") -> Category.BROWSE
      detail.contains(" audience ") -> Category.AUDIENCE
      detail.contains(" director ") -> Category.DIRECTOR
      detail.contains(" editor ") -> Category.EDITOR
      detail.contains(" critic ") -> Category.CRITIC
      detail.contains(" render ") || detail.contains(" rendering") -> Category.RENDER
      else -> Category.PIPELINE
    }
    "event_done", "all_done", "iterate_done" -> Category.DONE
    "event_failed", "failed", "iterate_failed" -> Category.ERROR
    "iterate_start", "iterate_stage" -> Category.ITERATION
    "soft_warnings", "critic_needed", "critic_skip", "critic_abort" -> Category.CRITIC
    else -> Category.PIPELINE
  }

  enum class Category {
    SETUP,        // download / ingest
    PLANNING,     // event scout / select
    PERCEPTION,   // VLM annotation pass (per-asset)
    PIPELINE,     // event start / generic event_stage
    BROWSE,       // browser agent reading event
    AUDIENCE,     // audience agent
    DIRECTOR,     // director agent
    EDITOR,       // editor agent
    CRITIC,       // critic agent
    RENDER,       // shot render + composite
    ITERATION,    // user iteration
    DONE,         // success terminal
    ERROR,        // failure terminal
  }

  companion object {
    private val EVENT_SCOPED_STAGES = setOf(
      "event_start", "event_stage", "event_done", "event_failed",
      "iterate_start", "iterate_stage", "iterate_done", "iterate_failed",
    )
  }
}

object AgentTimeline {

  private const val HISTORY_FILE = "_state_history.jsonl"
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  @Serializable
  private data class HistoryRow(val t: Long, val stage: String, val detail: String = "")

  /** Read the entire history file. Returns oldest-first. Bounded by [tailLimit]
   *  (default 800 entries — the file can grow indefinitely so we cap the read
   *  to keep parse cost off the main thread predictable). */
  fun read(context: Context, tailLimit: Int = 800): List<AgentTimelineEntry> {
    val file = File(context.filesDir, HISTORY_FILE)
    if (!file.isFile) return emptyList()
    return try {
      file.readLines()
        .takeLast(tailLimit)
        .mapNotNull { line ->
          if (line.isBlank()) return@mapNotNull null
          runCatching {
            val row = json.decodeFromString(HistoryRow.serializer(), line)
            AgentTimelineEntry(row.t, row.stage, row.detail)
          }.getOrNull()
        }
    } catch (_: Throwable) {
      emptyList()
    }
  }

  /** Filter to entries scoped to a single event. Includes both event_*
   *  entries that mention the eventId and any iterate_* entries on it. */
  fun forEvent(entries: List<AgentTimelineEntry>, eventId: String): List<AgentTimelineEntry> =
    entries.filter { it.eventId == eventId }

  /** Friendly Chinese label for a stage tag — keeps the timeline readable
   *  to non-engineers ("感知" instead of "perceive"). */
  fun friendlyLabel(stage: String, detail: String = ""): String = when (stage) {
    "download" -> "下载模型"
    "ingest" -> "扫描相册"
    "ingest_done" -> "相册扫描完成"
    "event_scout" -> "查看事件画面"
    "event_select_progress" -> "挑选事件"
    "perceive" -> "感知素材"
    "annotate" -> "VLM 标注素材"
    "annotate_done" -> "标注完成"
    "vlm_step" -> "标注一张素材"
    "event_start" -> "开始制作"
    "event_stage" -> when {
      detail.contains(" browse") -> "Browse · 读懂事件"
      detail.contains(" audience") -> "Audience · 设计观感"
      detail.contains(" director") -> "Director · 写分镜"
      detail.contains(" editor") -> "Editor · 选镜头"
      detail.contains(" critic") -> "Critic · 审片"
      detail.contains(" render") -> "Render · 渲染"
      else -> "处理中"
    }
    "event_done" -> "事件完成"
    "event_failed" -> "事件失败"
    "iterate_start" -> "开始迭代"
    "iterate_stage" -> "迭代中"
    "iterate_done" -> "迭代完成"
    "iterate_failed" -> "迭代失败"
    "all_done" -> "全部完成"
    "failed" -> "失败"
    "soft_warnings" -> "软警告"
    "critic_needed" -> "Critic 需介入"
    "critic_skip" -> "Critic 跳过"
    "critic_abort" -> "Critic 放弃"
    else -> stage
  }

  /** Strips the leading eventId / sub-tag noise from detail so the UI shows
   *  only the human-readable remainder. */
  fun friendlyDetail(stage: String, detail: String): String {
    if (detail.isBlank()) return ""
    return when (stage) {
      "event_start", "event_done", "event_failed",
      "iterate_start", "iterate_stage", "iterate_done", "iterate_failed",
      "event_stage" -> detail.substringAfter(' ', detail).trim()
      else -> detail.trim()
    }
  }
}
