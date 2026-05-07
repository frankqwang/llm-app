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
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.PerceptionCache
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
    maxEvents: Int = 2,
    ingestFilter: PhotoIngest.Filter = PhotoIngest.Filter(),
    onProgress: suspend (PipelineProgress) -> Unit,
  ): Result {
    // 1. Make sure perception models are present (best-effort).
    ModelDownloader.downloadAll(context).collect { (pct, label) ->
      onProgress(PipelineProgress.DownloadingModels(pct, label))
    }

    // 2. Ingest + segment + cap. Two-tier event list:
    //    - "resume": events from past runs that already have a cached
    //      timeline_final.json but no MP4 (render failed last time, e.g. font
    //      missing). These are basically free to finish — just need to call
    //      ffmpeg again.
    //    - "new": top-maxEvents most-recent events that have no cached work.
    //
    //    Resume events bypass the maxEvents cap because they're cheap.
    onProgress(PipelineProgress.Ingesting)
    val assets = PhotoIngest.loadRecent(context, windowDays, ingestFilter)
    val allEvents = EventSegmenter.segment(assets)
    val sortedDesc = allEvents.sortedByDescending { it.endEpochMs }
    val candidatesDir = java.io.File(context.filesDir, "candidates")
    val decisionsRoot = java.io.File(context.filesDir, "decisions")
    fun isResumable(eventId: String): Boolean {
      val tlf = java.io.File(java.io.File(decisionsRoot, eventId), "timeline_final.json")
      val mp4 = java.io.File(candidatesDir, "$eventId.mp4")
      return tlf.isFile && !mp4.isFile
    }
    val resumeEvents = sortedDesc.filter { isResumable(it.eventId) }
    val newEvents = sortedDesc.filter { !isResumable(it.eventId) }.take(maxEvents)
    // Resume first (cheap render-only), then new (expensive full pipeline).
    val events = resumeEvents + newEvents
    val keptAssetIds = events.flatMap { it.assetIds }.toSet()
    val assetMap = assets.associateBy { it.id }
    Log.d(TAG, "events: ${resumeEvents.size} resumable (${resumeEvents.map { it.eventId }}) + ${newEvents.size} new (cap=$maxEvents)")
    onProgress(PipelineProgress.IngestDone(keptAssetIds.size, events.size))
    if (events.isEmpty()) {
      onProgress(PipelineProgress.AllDone)
      return Result(emptyMap())
    }

    // 3. Perception over every asset that's actually used by a kept event.
    //    Per-asset Perception is JSON-cached; identical asset on a re-run skips
    //    the model calls entirely (~1-2s saved per asset).
    val perception = PerceptionEngine(context)
    val perceptions = HashMap<String, Perception>(keptAssetIds.size)
    var perceptionMs = 0L
    var cacheHits = 0
    var perceptionCount = 0
    try {
      val toAnalyze = assets.filter { it.id in keptAssetIds }
      perceptionCount = toAnalyze.size
      val perceptionStart = System.nanoTime()
      for ((i, asset) in toAnalyze.withIndex()) {
        val cached = PerceptionCache.get(context, asset.id)
        if (cached != null) {
          perceptions[asset.id] = cached
          cacheHits++
        } else {
          val fresh = perception.analyze(asset)
          perceptions[asset.id] = fresh
          PerceptionCache.put(context, asset, fresh)
        }
        if (i % 5 == 0 || i == toAnalyze.size - 1) {
          onProgress(PipelineProgress.Perceiving(i + 1, toAnalyze.size))
        }
      }
      perceptionMs = (System.nanoTime() - perceptionStart) / 1_000_000
      Log.d(TAG, "perception: ${toAnalyze.size} assets in ${perceptionMs}ms (cache hits: $cacheHits)")
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
      val resumeIds = resumeEvents.map { it.eventId }.toSet()
      val cachedDecisions = DecisionStore.loadAll(context).associateBy { it.eventId }

      for ((eventIdx, event) in events.withIndex()) {
        onProgress(PipelineProgress.EventStart(event.eventId, eventIdx + 1, events.size))
        val eventAssets = event.assetIds.mapNotNull { assetMap[it] }
        if (eventAssets.size < 3) {
          onProgress(PipelineProgress.EventFailed(event.eventId, "too few assets"))
          continue
        }
        val timer = PerfTimer(event.eventId)
        try {
          // Resume support: this event has a cached timeline_final but no
          // MP4 yet (likely from a previous cancelled / font-broken run).
          // Skip all 5 agent stages and just re-render. Salvages the
          // 10-15 min of VLM work the user already spent.
          if (event.eventId in resumeIds) {
            val cached = cachedDecisions[event.eventId]
            val cachedFinal = cached?.timelineFinal
            val cachedTone = cached?.director?.tone ?: "neutral"
            if (cachedFinal != null) {
              onProgress(PipelineProgress.EventStage(event.eventId, "render (resume from cache)"))
              val out = timer.render { renderer.render(cachedFinal, assetMap, cachedTone) }
              timer.finalize(context, perceptionCount, perceptionMs, cacheHits)
              if (out != null) {
                outputs[event.eventId] = out.outputPath
                onProgress(PipelineProgress.EventDone(event.eventId, out.outputPath))
              } else {
                onProgress(PipelineProgress.EventFailed(event.eventId, "render returned null (resume)"))
              }
              continue
            }
          }

          onProgress(PipelineProgress.EventStage(event.eventId, "browse"))
          val memory = timer.browse { montage.browse(event.eventId, eventAssets) }
          DecisionStore.writeMemory(context, event.eventId, memory)

          onProgress(PipelineProgress.EventStage(event.eventId, "audience"))
          val brief = timer.audience { audience.write(memory) }
          DecisionStore.writeAudience(context, event.eventId, brief)

          onProgress(PipelineProgress.EventStage(event.eventId, "director"))
          val plan = timer.director { director.draft(memory, brief, eventAssets.size) }
          DecisionStore.writeDirector(context, event.eventId, plan)

          onProgress(PipelineProgress.EventStage(event.eventId, "editor"))
          val v1 = timer.editor { buildTimeline(plan.shotBlueprint, event, eventAssets, perceptions, embedderForRecall, editor) }
          DecisionStore.writeTimelineV1(context, event.eventId, v1)

          onProgress(PipelineProgress.EventStage(event.eventId, "critic"))
          val critique = timer.critic { critic.review(1, v1, plan, memory) }
          DecisionStore.writeCritique(context, event.eventId, critique)
          val finalTl = if (critique.revisedRequests.isEmpty()) v1.copy(critiqueHistory = listOf(critique))
                        else applyRevisions(v1, critique.revisedRequests, event, eventAssets, perceptions, embedderForRecall, editor)
                          .copy(critiqueHistory = listOf(critique))
          DecisionStore.writeTimelineFinal(context, event.eventId, finalTl)

          onProgress(PipelineProgress.EventStage(event.eventId, "render"))
          val out = timer.render { renderer.render(finalTl, assetMap, plan.tone) }
          val perf = timer.finalize(context, perceptionCount, perceptionMs, cacheHits)
          Log.d(TAG, "perf ${event.eventId}: encoder=${com.google.ai.edge.gallery.customtasks.vlogpilot.render.EncoderProbe.selectedEncoderName()} total=${perf.totalMs}ms browse=${perf.browseMs} audience=${perf.audienceMs} director=${perf.directorMs} editor=${perf.editorMs} critic=${perf.criticMs} render=${perf.renderMs}")
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
