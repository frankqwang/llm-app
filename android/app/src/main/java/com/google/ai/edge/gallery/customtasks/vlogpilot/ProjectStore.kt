/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Product-level persistence above legacy EventDecisions. The app keeps reading
 * existing decisions, then mirrors them into projects/versions so the UI can
 * behave like a creation tool instead of a raw pipeline log viewer.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.IterationStore
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object ProjectStore {

  private const val TAG = "ProjectStore"
  private const val PROJECTS_FILE = "projects.json"
  private const val RUNS_FILE = "pipeline_runs.json"
  private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

  fun loadProjects(context: Context): List<VlogProject> =
    readList(File(root(context), PROJECTS_FILE))

  fun writeProjects(context: Context, projects: List<VlogProject>) {
    write(File(root(context), PROJECTS_FILE), json.encodeToString(projects.sortedByDescending { it.updatedAtMs }))
  }

  fun upsertProject(context: Context, project: VlogProject) {
    val existing = loadProjects(context).associateBy { it.projectId }.toMutableMap()
    existing[project.projectId] = project.copy(updatedAtMs = System.currentTimeMillis())
    writeProjects(context, existing.values.toList())
  }

  fun syncFromDecisions(
    context: Context,
    decisions: List<EventDecisions>,
  ): List<VlogProject> {
    val now = System.currentTimeMillis()
    val existingById = loadProjects(context).associateBy { it.projectId }.toMutableMap()
    decisions.forEach { decision ->
      val existing = existingById.values.firstOrNull { it.sourceEventId == decision.eventId } ?: existingById[decision.eventId]
      val templateId = existing?.templateId?.takeIf { it.isNotBlank() } ?: TemplateCatalog.inferForDecision(decision).id
      val versionCount = decision.versionCount.coerceAtLeast(if (decision.mp4Path != null) 1 else 0)
      val projectId = existing?.projectId ?: decision.eventId
      val project = VlogProject(
        projectId = projectId,
        title = bestProjectTitle(decision, existing),
        sourceType = if (decision.event?.userCuration != null || projectId.startsWith("user_")) ProjectSourceType.CURATED else ProjectSourceType.RECOMMENDED,
        sourceEventId = decision.eventId,
        selectedAssetIds = decision.inputAssets.map { it.id },
        templateId = templateId,
        status = projectStatus(decision),
        currentVersion = versionCount,
        createdAtMs = existing?.createdAtMs ?: decision.event?.startEpochMs ?: now,
        updatedAtMs = maxOf(existing?.updatedAtMs ?: 0L, decision.videoModifiedMsForProject(), now),
      )
      existingById[project.projectId] = project
      mirrorVersion(context, project, decision)
    }
    val projects = existingById.values.sortedByDescending { it.updatedAtMs }
    writeProjects(context, projects)
    return projects
  }

  fun loadVersions(context: Context, projectId: String): List<TimelineVersion> =
    readList<TimelineVersion>(versionFile(context, projectId)).sortedByDescending { it.version }

  fun upsertVersion(context: Context, version: TimelineVersion) {
    val versions = loadVersions(context, version.projectId).associateBy { it.version }.toMutableMap()
    versions[version.version] = version
    write(versionFile(context, version.projectId), json.encodeToString(versions.values.sortedBy { it.version }))
  }

  fun loadRuns(context: Context): List<PipelineRunRecord> =
    readList<PipelineRunRecord>(File(root(context), RUNS_FILE)).sortedByDescending { it.startedAtMs }

  fun upsertRun(context: Context, run: PipelineRunRecord) {
    val runs = loadRuns(context).associateBy { it.runId }.toMutableMap()
    runs[run.runId] = run
    write(File(root(context), RUNS_FILE), json.encodeToString(runs.values.sortedByDescending { it.startedAtMs }.take(80)))
  }

  private fun mirrorVersion(context: Context, project: VlogProject, decision: EventDecisions) {
    val timeline = decision.timelineFinal ?: decision.timelineV1
    val history = IterationStore.loadHistory(context, decision.eventId)
    if (history?.iterations?.isNotEmpty() == true) {
      history.iterations.forEach { summary ->
        val versionTimeline = when (summary.version) {
          1 -> decision.timelineV1 ?: timeline
          history.currentVersion -> timeline ?: IterationStore.loadIterationTimeline(context, decision.eventId, summary.version)
          else -> IterationStore.loadIterationTimeline(context, decision.eventId, summary.version)
        }
        upsertVersion(
          context = context,
          version = TimelineVersion(
            projectId = project.projectId,
            version = summary.version,
            timeline = versionTimeline,
            mp4Path = summary.mp4Path,
            templateId = project.templateId,
            feedbackSummary = summary.changeSummary.ifBlank { if (summary.version == 1) "首版" else "AI 修改" },
            createdAtMs = summary.createdAtMs,
          ),
        )
      }
      return
    }

    if (timeline == null && decision.mp4Path == null) return
    val versionNo = decision.versionCount.coerceAtLeast(1)
    upsertVersion(
      context = context,
      version = TimelineVersion(
        projectId = project.projectId,
        version = versionNo,
        timeline = timeline,
        mp4Path = decision.mp4Path,
        templateId = project.templateId,
        feedbackSummary = if (versionNo <= 1) "首版" else "AI 修改",
        createdAtMs = decision.videoModifiedMsForProject().takeIf { it > 0 } ?: System.currentTimeMillis(),
      ),
    )
  }

  private fun projectStatus(decision: EventDecisions): ProjectStatus = when {
    decision.mp4Path != null -> ProjectStatus.READY
    decision.timelineFinal != null || decision.timelineV1 != null || decision.director != null -> ProjectStatus.MAKING
    else -> ProjectStatus.DRAFT
  }

  private fun bestProjectTitle(decision: EventDecisions, existing: VlogProject?): String {
    val directorTitle = decision.director?.title?.takeUnless(::isGenericTitle)
    val memoryTitle = decision.memory?.storylineSummary
      ?.take(18)
      ?.trimEnd('，', '。', ',', '.', ' ')
      ?.takeIf { it.isNotBlank() && !isGenericTitle(it) }
    return directorTitle
      ?: existing?.title?.takeIf { it.isNotBlank() && !isGenericTitle(it) }
      ?: memoryTitle
      ?: "未命名作品"
  }

  private fun isGenericTitle(title: String): Boolean {
    val normalized = title.trim().lowercase()
    return normalized in setOf("片段", "短片", "视频", "视频片段", "vlog", "回忆视频", "未命名")
  }

  private fun EventDecisions.videoModifiedMsForProject(): Long {
    val fileTime = mp4Path?.let(::File)?.takeIf { it.isFile }?.lastModified() ?: 0L
    return maxOf(fileTime, event?.endEpochMs ?: 0L)
  }

  private fun versionFile(context: Context, projectId: String): File =
    File(File(root(context), "versions").apply { mkdirs() }, "$projectId.json")

  private fun root(context: Context): File =
    File(context.filesDir, "vlog_projects").apply { mkdirs() }

  private inline fun <reified T> readList(file: File): List<T> {
    if (!file.isFile) return emptyList()
    return try {
      json.decodeFromString<List<T>>(file.readText())
    } catch (t: Throwable) {
      Log.w(TAG, "failed to read ${file.name}: ${t.message}")
      emptyList()
    }
  }

  private fun write(file: File, content: String) {
    try {
      file.parentFile?.mkdirs()
      file.writeText(content)
    } catch (t: Throwable) {
      Log.w(TAG, "failed to write ${file.name}: ${t.message}")
    }
  }
}
