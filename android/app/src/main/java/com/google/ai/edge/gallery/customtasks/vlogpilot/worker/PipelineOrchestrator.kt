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
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.VlmAnnotator
import com.google.ai.edge.gallery.customtasks.vlogpilot.ingest.PhotoIngest
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.MediaLoader
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
      val annotator = VlmAnnotator(agent)
      val renderer = VideoRenderer(context)
      val resumeIds = resumeEvents.map { it.eventId }.toSet()
      val cachedDecisions = DecisionStore.loadAll(context).associateBy { it.eventId }

      // ---- VLM annotation pass ----
      // For every kept non-junk asset whose Perception.vlmTags is empty, run a
      // single Gemma 4 call to fill scene/subjects/action/mood/salient. Replaces
      // the never-shipped CLIP/YOLO/FaceEmbedder small-model stack with VLM
      // understanding. Cached to disk; re-runs over the same album are free.
      var annotateMs = 0L
      var annotatedCount = 0
      run {
        val needsAnnotation = perceptions.values
          .filter { !it.isJunk && it.vlmTags.scene.isBlank() }
          .mapNotNull { perc -> assetMap[perc.assetId]?.let { perc to it } }
        if (needsAnnotation.isNotEmpty()) {
          val annotateStart = System.nanoTime()
          for ((idx, pair) in needsAnnotation.withIndex()) {
            val (perc, asset) = pair
            val thumb = MediaLoader.loadImage(context, asset, maxSide = 512) ?: continue
            val tags = try {
              annotator.annotate(thumb, asset.mediaType.name.lowercase())
            } catch (t: Throwable) {
              Log.w(TAG, "annotate failed for ${asset.id}: ${t.message}")
              null
            } finally {
              runCatching { thumb.recycle() }
            }
            if (tags != null && tags.scene.isNotBlank()) {
              val updated = perc.copy(vlmTags = tags)
              perceptions[perc.assetId] = updated
              PerceptionCache.put(context, asset, updated)
              annotatedCount++
            }
            if (idx % 5 == 0 || idx == needsAnnotation.size - 1) {
              onProgress(PipelineProgress.Annotating(idx + 1, needsAnnotation.size))
            }
          }
          annotateMs = (System.nanoTime() - annotateStart) / 1_000_000
          Log.d(TAG, "annotate: $annotatedCount/${needsAnnotation.size} assets in ${annotateMs}ms")
        }
      }

      for ((eventIdx, event) in events.withIndex()) {
        onProgress(PipelineProgress.EventStart(event.eventId, eventIdx + 1, events.size))
        val eventAssets = event.assetIds.mapNotNull { assetMap[it] }
        DecisionStore.writeEventInputs(context, event, eventAssets)
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
                DecisionStore.writeTimelineFinal(context, event.eventId, out.timeline)
                outputs[event.eventId] = out.outputPath
                onProgress(PipelineProgress.EventDone(event.eventId, out.outputPath))
              } else {
                onProgress(PipelineProgress.EventFailed(event.eventId, "render returned null (resume)"))
              }
              continue
            }
          }

          if (eventAssets.size < 3) {
            onProgress(PipelineProgress.EventFailed(event.eventId, "too few assets"))
            continue
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
          val v1 = timer.editor { buildTimeline(plan.shotBlueprint, plan.tone, event, eventAssets, perceptions, editor) }
          DecisionStore.writeTimelineV1(context, event.eventId, v1)

          // Up-to-2 critic iterations, mirroring pc-pilot/step6_critic.py MAX_ITER=2.
          // Stop early if a round returns no revisions; persist the full history on the
          // final timeline so the UI viewer can show what changed.
          onProgress(PipelineProgress.EventStage(event.eventId, "critic"))
          val critiques = mutableListOf<com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Critique>()
          var working = v1
          var lastCritique: com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Critique? = null
          for (it in 1..MAX_CRITIC_ITER) {
            val crit = timer.critic { critic.review(it, working, plan, memory) }
            critiques += crit
            lastCritique = crit
            if (crit.revisedRequests.isEmpty()) break
            working = applyRevisions(working, crit.revisedRequests, plan.tone, event, eventAssets, perceptions, editor)
          }
          // Keep a separate critique.json for the LATEST iteration so existing
          // DecisionStore consumers (UI) keep working unchanged; the full history
          // is also baked into the final Timeline.
          lastCritique?.let { DecisionStore.writeCritique(context, event.eventId, it) }
          val finalTl = working.copy(critiqueHistory = critiques.toList())
          DecisionStore.writeTimelineFinal(context, event.eventId, finalTl)

          onProgress(PipelineProgress.EventStage(event.eventId, "render"))
          val out = timer.render { renderer.render(finalTl, assetMap, plan.tone) }
          val perf = timer.finalize(context, perceptionCount, perceptionMs, cacheHits)
          Log.d(TAG, "perf ${event.eventId}: encoder=${com.google.ai.edge.gallery.customtasks.vlogpilot.render.EncoderProbe.selectedEncoderName()} total=${perf.totalMs}ms browse=${perf.browseMs} audience=${perf.audienceMs} director=${perf.directorMs} editor=${perf.editorMs} critic=${perf.criticMs} render=${perf.renderMs}")
          if (out != null) {
            DecisionStore.writeTimelineFinal(context, event.eventId, out.timeline)
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
    } finally {
      agent.close()
    }

    onProgress(PipelineProgress.AllDone)
    return Result(outputs)
  }

  // ---- helpers ----

  private suspend fun buildTimeline(
    blueprint: List<ShotRequest>,
    directorTone: String,
    event: Event,
    eventAssets: List<Asset>,
    perceptions: Map<String, Perception>,
    editor: EditorAgent,
  ): Timeline {
    val used = mutableSetOf<String>()
    val shots = mutableListOf<ShotSpec>()
    var prevSummary = ""
    val nBlueprint = blueprint.size
    for ((order, req) in blueprint.withIndex()) {
      var spec = editor.pickShot(
        order = order + 1,
        request = req,
        nBlueprint = nBlueprint,
        eventAssets = eventAssets,
        perceptions = perceptions,
        excludedAssetIds = used,
        previousShot = prevSummary,
        directorTone = directorTone,
      )
      if (spec == null && used.isNotEmpty()) {
        // Last-resort fill: allow reuse rather than silently dropping a required
        // director slot. A repeated shot is visible and critic/render can handle it;
        // a missing slot collapses the whole narrative without explanation.
        spec = editor.pickShot(
          order = order + 1,
          request = req,
          nBlueprint = nBlueprint,
          eventAssets = eventAssets,
          perceptions = perceptions,
          excludedAssetIds = emptySet(),
          previousShot = prevSummary,
          directorTone = directorTone,
        )
      }
      if (spec == null) {
        Log.w(TAG, "slot ${order + 1}/${nBlueprint} could not be filled for ${event.eventId}")
        continue
      }
      shots += spec
      used += spec.assetId
      prevSummary = "[${spec.mediaType.name.lowercase()}] ${req.role.name.lowercase()} dur=${spec.durationSec}s caption=${spec.caption}"
    }
    return Timeline(eventId = event.eventId, directorBriefRef = "", shots = shots)
  }

  private suspend fun applyRevisions(
    base: Timeline,
    revisions: List<RevisedRequest>,
    directorTone: String,
    event: Event,
    eventAssets: List<Asset>,
    perceptions: Map<String, Perception>,
    editor: EditorAgent,
  ): Timeline {
    val byOrder = base.shots.associateBy { it.order }.toMutableMap()
    // Keep ALL current picks in `used` so revised recall is forced to find a DIFFERENT asset;
    // pc-pilot/step6_critic.py:175 made the same call. Removing the original from `used` first
    // would let the editor re-pick the asset critic just flagged.
    val used = base.shots.map { it.assetId }.toMutableSet()
    val nBlueprint = base.shots.size
    for (rev in revisions) {
      val original = byOrder[rev.shotOrder] ?: continue
      val replaced = editor.pickShot(
        order = rev.shotOrder,
        request = rev.newRequest,
        nBlueprint = nBlueprint,
        eventAssets = eventAssets,
        perceptions = perceptions,
        excludedAssetIds = used,
        previousShot = "(revised slot)",
        directorTone = directorTone,
      )
      if (replaced == null || replaced.assetId == original.assetId) continue  // no different candidate, keep original
      used.remove(original.assetId)
      used += replaced.assetId
      byOrder[rev.shotOrder] = replaced
    }
    return base.copy(shots = byOrder.values.sortedBy { it.order })
  }

  companion object {
    private const val TAG = "PipelineOrchestrator"
    private const val MAX_CRITIC_ITER = 2
  }
}
