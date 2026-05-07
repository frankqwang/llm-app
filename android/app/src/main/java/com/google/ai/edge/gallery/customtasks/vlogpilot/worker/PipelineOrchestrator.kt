/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Pure orchestration layer (no Android Worker boilerplate). Sequences:
 *   ingest → segment → perceive (all assets) →
 *     for each event: montage → audience → director →
 *                     editor (per shot) → critic → render →
 *   emit progress / final paths via the supplied callback.
 *
 * Lives outside WorkManager so it can be unit-tested and reused by future
 * UIs (e.g. CLI, instrumented test, server-side parity check).
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.AgentRuntime
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.AudienceAgent
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.CriticAgent
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.DirectorAgent
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.EditorAgent
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.MontageAgent
import com.google.ai.edge.gallery.customtasks.vlogpilot.ingest.PhotoIngest
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.PerceptionEngine
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.EventSegmenter
import com.google.ai.edge.gallery.customtasks.vlogpilot.render.VideoRenderer
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.RevisedRequest
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRequest
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import com.google.ai.edge.gallery.data.Model

class PipelineOrchestrator(
  private val context: Context,
  private val gemmaModel: Model,
) {
  data class Result(val outputs: Map<String, String>) // eventId -> mp4 path

  suspend fun run(
    windowDays: Int = 30,
    onProgress: suspend (PipelineProgress) -> Unit,
  ): Result {
    // 1. Make sure perception models are present (best-effort).
    ModelDownloader.downloadAll(context).collect { (pct, label) ->
      onProgress(PipelineProgress.DownloadingModels(pct, label))
    }

    // 2. Ingest + segment.
    onProgress(PipelineProgress.Ingesting)
    val assets = PhotoIngest.loadRecent(context, windowDays)
    val events = EventSegmenter.segment(assets)
    val assetMap = assets.associateBy { it.id }
    onProgress(PipelineProgress.IngestDone(assets.size, events.size))
    if (events.isEmpty()) {
      onProgress(PipelineProgress.AllDone)
      return Result(emptyMap())
    }

    // 3. Perception over every asset.
    val perception = PerceptionEngine(context)
    val perceptions = HashMap<String, Perception>(assets.size)
    try {
      for ((i, asset) in assets.withIndex()) {
        perceptions[asset.id] = perception.analyze(asset)
        if (i % 5 == 0 || i == assets.size - 1) {
          onProgress(PipelineProgress.Perceiving(i + 1, assets.size))
        }
      }
    } finally {
      perception.close()
    }

    // 4. Boot Gemma 4 (cold start can take minutes — caller's foreground service should expose a notification).
    val agent = AgentRuntime(context, gemmaModel)
    val outputs = HashMap<String, String>(events.size)
    try {
      agent.ensureInitialized()
      val montage = MontageAgent(context, agent)
      val audience = AudienceAgent(agent)
      val director = DirectorAgent(agent)
      val editor = EditorAgent(context, agent)
      val critic = CriticAgent(agent)
      val renderer = VideoRenderer(context)
      val embedderForRecall = PerceptionEngine(context) // re-open just for embedText (cheap)

      for ((eventIdx, event) in events.withIndex()) {
        onProgress(PipelineProgress.EventStart(event.eventId, eventIdx + 1, events.size))
        val eventAssets = event.assetIds.mapNotNull { assetMap[it] }
        if (eventAssets.size < 3) {
          onProgress(PipelineProgress.EventFailed(event.eventId, "too few assets"))
          continue
        }
        try {
          onProgress(PipelineProgress.EventStage(event.eventId, "browse"))
          val memory = montage.browse(event.eventId, eventAssets)

          onProgress(PipelineProgress.EventStage(event.eventId, "audience"))
          val brief = audience.write(memory)

          onProgress(PipelineProgress.EventStage(event.eventId, "director"))
          val plan = director.draft(memory, brief, eventAssets.size)

          onProgress(PipelineProgress.EventStage(event.eventId, "editor"))
          val v1 = buildTimeline(plan.shotBlueprint, event, eventAssets, perceptions, embedderForRecall, editor)

          onProgress(PipelineProgress.EventStage(event.eventId, "critic"))
          val critique = critic.review(1, v1, plan, memory)
          val finalTl = if (critique.revisedRequests.isEmpty()) v1.copy(critiqueHistory = listOf(critique))
                        else applyRevisions(v1, critique.revisedRequests, event, eventAssets, perceptions, embedderForRecall, editor)
                          .copy(critiqueHistory = listOf(critique))

          onProgress(PipelineProgress.EventStage(event.eventId, "render"))
          val out = renderer.render(finalTl, assetMap, plan.tone)
          if (out != null) {
            outputs[event.eventId] = out.outputPath
            onProgress(PipelineProgress.EventDone(event.eventId, out.outputPath))
          } else {
            onProgress(PipelineProgress.EventFailed(event.eventId, "render returned null"))
          }
        } catch (t: Throwable) {
          Log.e(TAG, "event ${event.eventId} failed", t)
          onProgress(PipelineProgress.EventFailed(event.eventId, t.message ?: t::class.java.simpleName))
        }
      }
      embedderForRecall.close()
    } finally {
      agent.close()
    }

    onProgress(PipelineProgress.AllDone)
    return Result(outputs)
  }

  // ---- helpers ----

  private suspend fun buildTimeline(
    blueprint: List<ShotRequest>,
    event: Event,
    eventAssets: List<Asset>,
    perceptions: Map<String, Perception>,
    embedder: PerceptionEngine,
    editor: EditorAgent,
  ): Timeline {
    val used = mutableSetOf<String>()
    val shots = mutableListOf<ShotSpec>()
    var prevSummary = ""
    for ((order, req) in blueprint.withIndex()) {
      val q = embedder.embedText(req.visualRequirements)
      val spec = editor.pickShot(
        order = order + 1,
        request = req,
        eventAssets = eventAssets,
        perceptions = perceptions,
        queryEmbedding = q,
        excludedAssetIds = used,
        previousShot = prevSummary,
      ) ?: continue
      shots += spec
      used += spec.assetId
      prevSummary = "[${spec.mediaType.name.lowercase()}] ${req.role.name.lowercase()} dur=${spec.durationSec}s caption=${spec.caption}"
    }
    return Timeline(eventId = event.eventId, directorBriefRef = "", shots = shots)
  }

  private suspend fun applyRevisions(
    base: Timeline,
    revisions: List<RevisedRequest>,
    event: Event,
    eventAssets: List<Asset>,
    perceptions: Map<String, Perception>,
    embedder: PerceptionEngine,
    editor: EditorAgent,
  ): Timeline {
    val byOrder = base.shots.associateBy { it.order }.toMutableMap()
    val used = base.shots.map { it.assetId }.toMutableSet()
    for (rev in revisions) {
      val original = byOrder[rev.shotOrder] ?: continue
      used.remove(original.assetId)
      val q = embedder.embedText(rev.newRequest.visualRequirements)
      val replaced = editor.pickShot(
        order = rev.shotOrder,
        request = rev.newRequest,
        eventAssets = eventAssets,
        perceptions = perceptions,
        queryEmbedding = q,
        excludedAssetIds = used,
        previousShot = "(revised slot)",
      ) ?: original
      byOrder[rev.shotOrder] = replaced
      used += replaced.assetId
    }
    return base.copy(shots = byOrder.values.sortedBy { it.order })
  }

  companion object { private const val TAG = "PipelineOrchestrator" }
}
