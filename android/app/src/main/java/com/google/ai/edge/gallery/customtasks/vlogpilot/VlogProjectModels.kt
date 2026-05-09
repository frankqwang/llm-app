/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Product-level project model. These types sit above the legacy event
 * decisions artifacts so the UI can talk about works, versions, and runs
 * instead of raw worker output files.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.QuickAction
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class ProjectSourceType {
  @SerialName("recommended") RECOMMENDED,
  @SerialName("curated") CURATED,
}

@Serializable
internal enum class ProjectStatus {
  @SerialName("draft") DRAFT,
  @SerialName("making") MAKING,
  @SerialName("ready") READY,
  @SerialName("optimizing") OPTIMIZING,
  @SerialName("failed") FAILED,
}

@Serializable
internal data class VlogProject(
  val projectId: String,
  val title: String,
  val sourceType: ProjectSourceType,
  val sourceEventId: String? = null,
  val selectedAssetIds: List<String> = emptyList(),
  val templateId: String = TemplateCatalog.DEFAULT_TEMPLATE_ID,
  val status: ProjectStatus = ProjectStatus.DRAFT,
  val currentVersion: Int = 0,
  val createdAtMs: Long = System.currentTimeMillis(),
  val updatedAtMs: Long = createdAtMs,
)

@Serializable
internal data class TimelineVersion(
  val projectId: String,
  val version: Int,
  val timeline: Timeline? = null,
  val mp4Path: String? = null,
  val templateId: String = TemplateCatalog.DEFAULT_TEMPLATE_ID,
  val feedbackSummary: String = "",
  val pipelineRunId: String? = null,
  val createdAtMs: Long = System.currentTimeMillis(),
)

@Serializable
internal enum class PipelineRunMode {
  @SerialName("refresh_stories") REFRESH_STORIES,
  @SerialName("make_first_cut") MAKE_FIRST_CUT,
  @SerialName("ai_edit") AI_EDIT,
  @SerialName("index_assets") INDEX_ASSETS,
}

@Serializable
internal enum class PipelineRunStatus {
  @SerialName("queued") QUEUED,
  @SerialName("running") RUNNING,
  @SerialName("done") DONE,
  @SerialName("failed") FAILED,
  @SerialName("cancelled") CANCELLED,
}

@Serializable
internal data class PipelineRunRecord(
  val runId: String,
  val mode: PipelineRunMode,
  val status: PipelineRunStatus,
  val projectId: String? = null,
  val stage: String = "",
  val progressCurrent: Int = 0,
  val progressTotal: Int = 0,
  val startedAtMs: Long = System.currentTimeMillis(),
  val endedAtMs: Long? = null,
  val errorMessage: String = "",
)

@Serializable
internal enum class AiEditScope {
  @SerialName("render_only") RENDER_ONLY,
  @SerialName("shot_level") SHOT_LEVEL,
  @SerialName("template_level") TEMPLATE_LEVEL,
  @SerialName("story_level") STORY_LEVEL,
}

@Serializable
internal data class AiEditRequest(
  val projectId: String,
  val baseVersion: Int,
  val scope: AiEditScope,
  val targetShotOrders: List<Int> = emptyList(),
  val text: String = "",
  val quickActions: List<QuickAction> = emptyList(),
  val createdAtMs: Long = System.currentTimeMillis(),
)
