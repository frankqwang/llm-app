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
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.EventSelector
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.VideoFrameSheetBuilder
import com.google.ai.edge.gallery.customtasks.vlogpilot.render.VideoRenderer
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.MediaType
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.RevisedRequest
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRequest
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotSpec
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
import com.google.ai.edge.gallery.data.Model
import kotlinx.coroutines.CancellationException

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
    // Wrap the user-supplied progress callback so every event also writes
    // a one-line breadcrumb to filesDir/_state.txt — vivo OriginOS suppresses
    // adb logcat for unprivileged apps, so the file is the only reliable
    // place to see "where is the pipeline right now" from outside.
    val progress: suspend (PipelineProgress) -> Unit = { p ->
      runCatching { StateBreadcrumb.fromProgress(context, p) }
      runCatching { onProgress(p) }.onFailure { t ->
        if (t is CancellationException) throw t
        val progressName = p.javaClass.simpleName
        StateBreadcrumb.mark(context, "progress_callback_error", "$progressName ${t::class.java.simpleName}: ${t.message}")
        Log.w(TAG, "progress callback failed for $progressName: ${t.message}")
      }
    }
    StateBreadcrumb.mark(context, "orchestrator_start", "windowDays=$windowDays maxEvents=$maxEvents")
    progress(PipelineProgress.DownloadingModels(0, "starting"))

    // 1. Make sure perception models are present (best-effort).
    ModelDownloader.downloadAll(context).collect { (pct, label) ->
      progress(PipelineProgress.DownloadingModels(pct, label))
    }

    // 2. Ingest + segment + value-rank. Two-tier event list:
    //    - "resume": events from past runs that already have a cached
    //      timeline_final.json but no MP4 (render failed last time, e.g. font
    //      missing). These are basically free to finish — just need to call
    //      ffmpeg again.
    //    - "new": top-maxEvents high-value events that have no cached work.
    //
    //    Resume events bypass the maxEvents cap because they're cheap.
    progress(PipelineProgress.Ingesting)
    val assets = PhotoIngest.loadRecent(context, windowDays, ingestFilter)
    val allEvents = EventSegmenter.segment(assets)
    val assetMap = assets.associateBy { it.id }
    val selectionPerceptions = HashMap<String, Perception?>()
    fun selectionPerception(assetId: String): Perception? =
      selectionPerceptions.getOrPut(assetId) { PerceptionCache.get(context, assetId) }
    val rankedCandidates = EventSelector.rank(allEvents, assetMap, ::selectionPerception)
    val sortedDesc = allEvents.sortedByDescending { it.endEpochMs }
    val candidatesDir = java.io.File(context.filesDir, "candidates")
    val decisionsRoot = java.io.File(context.filesDir, "decisions")
    fun isResumable(eventId: String): Boolean {
      val tlf = java.io.File(java.io.File(decisionsRoot, eventId), "timeline_final.json")
      val mp4 = java.io.File(candidatesDir, "$eventId.mp4")
      return tlf.isFile && !mp4.isFile
    }
    val resumeEvents = sortedDesc.filter { isResumable(it.eventId) }
    val resumeEventIds = resumeEvents.map { it.eventId }.toSet()
    val newCandidates = rankedCandidates
      .filter { it.eventId !in resumeEventIds }
      .take(maxEvents)
    val newEvents = newCandidates.map { it.event }
    // Resume first (cheap render-only), then new (expensive full pipeline).
    val events = resumeEvents + newEvents
    val keptAssetIds = events.flatMap { it.assetIds }.toSet()
    val candidateSummary = rankedCandidates.take(8).joinToString(" | ") { it.compactSummary() }
    val selectedSummary = (
      resumeEvents.map { "${it.eventId}:resume" } +
        newCandidates.map { it.compactSummary() }
      ).joinToString(" | ")
    Log.d(TAG, "events: ${resumeEvents.size} resumable (${resumeEvents.map { it.eventId }}) + ${newEvents.size} selected by value (cap=$maxEvents)")
    StateBreadcrumb.mark(context, "event_candidates", candidateSummary.ifBlank { "none" })
    StateBreadcrumb.mark(
      context,
      "event_select",
      selectedSummary.ifBlank { "none" },
    )
    progress(PipelineProgress.SelectingEvents(rankedCandidates.size, events.size, selectedSummary.ifBlank { "none" }))
    if (events.isEmpty()) {
      progress(PipelineProgress.AllDone)
      return Result(emptyMap())
    }
    // Make the Process tab useful immediately. Perception + VLM annotation can
    // take minutes, especially on the first run; if we only write event_inputs
    // inside the per-event loop below, DecisionStore.loadAll() sees an empty
    // decisions/ directory during the whole pre-agent phase.
    for (event in events) {
      DecisionStore.writeEventInputs(context, event, event.assetIds.mapNotNull { assetMap[it] })
    }
    progress(PipelineProgress.IngestDone(keptAssetIds.size, events.size))

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
          progress(
            PipelineProgress.Perceiving(
              current = i + 1,
              total = toAnalyze.size,
              assetName = asset.displayName,
              mediaType = asset.mediaType.name.lowercase(),
              cacheHit = cached != null,
            ),
          )
        }
      }
      perceptionMs = (System.nanoTime() - perceptionStart) / 1_000_000
      Log.d(TAG, "perception: ${toAnalyze.size} assets in ${perceptionMs}ms (cache hits: $cacheHits)")
    } finally {
      perception.close()
    }

    // 4. Boot Gemma 4 (cold start ~5-15s; the actual model weights live in the gallery's
    //    LlmChatModelHelper which the user already imported separately).
    val agent = AgentRuntime(context, gemmaModel)
    val outputs = HashMap<String, String>(events.size)
    try {
      StateBreadcrumb.mark(context, "init", "starting agent.ensureInitialized")
      agent.ensureInitialized()
      StateBreadcrumb.mark(context, "init_done", "agent ready")
      StateBreadcrumb.mark(context, "agent_objects", "creating agents")
      val montage = MontageAgent(context, agent)
      val audience = AudienceAgent(agent)
      val director = DirectorAgent(agent)
      val editor = EditorAgent(context, agent)
      val critic = CriticAgent(context, agent)
      val annotator = VlmAnnotator(agent)
      val renderer = VideoRenderer(context)
      val resumeIds = resumeEvents.map { it.eventId }.toSet()
      StateBreadcrumb.mark(context, "decision_cache", "loading prior decisions")
      val cachedDecisions = DecisionStore.loadAll(context).associateBy { it.eventId }

      // VLM annotation pass — extracted into runAnnotationPass() for the same
      // reason as runEvent(): too many suspend points inline make run() exceed
      // ART's 64K bytecode limit.
      runAnnotationPass(
        perceptions = perceptions,
        assetMap = assetMap,
        annotator = annotator,
        progress = progress,
      )

      // Per-event body extracted into runEvent() — keeping it inline here makes
      // run()'s coroutine state machine exceed ART's 64K bytecode limit (logcat:
      // "Method exceeds compiler instruction limit"), which made every progress
      // suspension fall back to label 0 = the start of run() = an infinite
      // download → ingest → perceive → init restart loop on the same worker
      // invocation. Splitting per-event into its own suspend function keeps
      // each method's state machine small enough to JIT cleanly.
      for ((eventIdx, event) in events.withIndex()) {
        runEvent(
          event = event,
          eventIdx = eventIdx,
          eventsTotal = events.size,
          assetMap = assetMap,
          resumeIds = resumeIds,
          cachedDecisions = cachedDecisions,
          perceptions = perceptions,
          perceptionCount = perceptionCount,
          perceptionMs = perceptionMs,
          cacheHits = cacheHits,
          montage = montage,
          audience = audience,
          director = director,
          editor = editor,
          critic = critic,
          renderer = renderer,
          outputs = outputs,
          progress = progress,
        )
      }
    } catch (t: CancellationException) {
      StateBreadcrumb.mark(context, "cancelled", "${t::class.java.simpleName}: ${t.message}")
      throw t
    } catch (t: Throwable) {
      // Anything that throws OUTSIDE the per-event catch (e.g. agent.ensureInitialized,
      // VLM annotation pass, DecisionStore.loadAll) — record the exception class + message
      // to the breadcrumb so we can post-mortem from outside without logcat.
      val sw = java.io.StringWriter()
      t.printStackTrace(java.io.PrintWriter(sw))
      StateBreadcrumb.mark(context, "fatal", "${t::class.java.simpleName}: ${t.message}")
      runCatching {
        java.io.File(context.filesDir, "_state_fatal.txt").writeText(sw.toString())
      }
      Log.e(TAG, "pipeline fatal", t)
      throw t
    } finally {
      agent.close()
    }

    progress(PipelineProgress.AllDone)
    return Result(outputs)
  }

  // ---- helpers ----

  private suspend fun runAnnotationPass(
    perceptions: MutableMap<String, Perception>,
    assetMap: Map<String, Asset>,
    annotator: VlmAnnotator,
    progress: suspend (PipelineProgress) -> Unit,
  ) {
    StateBreadcrumb.mark(context, "annotate_plan", "planning annotation pass")
    val needsAnnotation = perceptions.values
      .mapNotNull { perc -> assetMap[perc.assetId]?.let { perc to it } }
      .filter { (perc, asset) -> !perc.isJunk && needsVlmAnnotation(perc, asset) }
    StateBreadcrumb.mark(context, "annotate_plan_done", "needs=${needsAnnotation.size}")
    if (needsAnnotation.isEmpty()) return

    val annotateStart = System.nanoTime()
    var annotatedCount = 0
    for ((idx, pair) in needsAnnotation.withIndex()) {
      val (perc, asset) = pair
      progress(
        PipelineProgress.Annotating(
          current = idx + 1,
          total = needsAnnotation.size,
          assetName = asset.displayName,
          mediaType = asset.mediaType.name.lowercase(),
          phase = "start",
        ),
      )
      val assetAnnotateStart = System.nanoTime()
      val annotation = try {
        annotateAsset(annotator, asset, perc.sceneCuts)
      } catch (t: CancellationException) {
        throw t
      } catch (t: Throwable) {
        Log.w(TAG, "annotate failed for ${asset.id}: ${t.message}")
        null
      }
      if (annotation != null && annotation.hasSignal()) {
        val updated = perc.copy(
          vlmTags = annotation.tags,
          videoInsight = annotation.videoInsight,
        )
        perceptions[perc.assetId] = updated
        PerceptionCache.put(context, asset, updated)
        annotatedCount++
      }
      progress(
        PipelineProgress.Annotating(
          current = idx + 1,
          total = needsAnnotation.size,
          assetName = asset.displayName,
          mediaType = asset.mediaType.name.lowercase(),
          phase = if (annotation?.hasSignal() == true) "done" else "skipped",
          elapsedMs = (System.nanoTime() - assetAnnotateStart) / 1_000_000,
        ),
      )
    }
    val annotateMs = (System.nanoTime() - annotateStart) / 1_000_000
    Log.d(TAG, "annotate: $annotatedCount/${needsAnnotation.size} assets in ${annotateMs}ms")
    progress(PipelineProgress.AnnotationDone(annotatedCount, needsAnnotation.size, annotateMs))
  }

  private suspend fun annotateAsset(
    annotator: VlmAnnotator,
    asset: Asset,
    sceneCutsSec: List<Float> = emptyList(),
  ): VlmAnnotator.Annotation? =
    if (asset.mediaType == MediaType.IMAGE) {
      val thumb = MediaLoader.loadImage(context, asset, maxSide = 512) ?: return null
      try {
        annotator.annotate(thumb, asset.mediaType.name.lowercase())
      } finally {
        runCatching { thumb.recycle() }
      }
    } else {
      val sheet = VideoFrameSheetBuilder.build(context, asset, sceneCutsSec)
      if (sheet != null) {
        try {
          annotator.annotateVideo(sheet.bitmap, sheet.frameTimestampsSec, asset.mediaType.name.lowercase())
        } finally {
          runCatching { sheet.bitmap.recycle() }
        }
      } else {
        val thumb = MediaLoader.loadImage(context, asset, maxSide = 512) ?: return null
        try {
          annotator.annotate(thumb, asset.mediaType.name.lowercase())
        } finally {
          runCatching { thumb.recycle() }
        }
      }
    }

  private suspend fun runEvent(
    event: Event,
    eventIdx: Int,
    eventsTotal: Int,
    assetMap: Map<String, Asset>,
    resumeIds: Set<String>,
    cachedDecisions: Map<String, EventDecisions>,
    perceptions: Map<String, Perception>,
    perceptionCount: Int,
    perceptionMs: Long,
    cacheHits: Int,
    montage: MontageAgent,
    audience: AudienceAgent,
    director: DirectorAgent,
    editor: EditorAgent,
    critic: CriticAgent,
    renderer: VideoRenderer,
    outputs: MutableMap<String, String>,
    progress: suspend (PipelineProgress) -> Unit,
  ) {
    StateBreadcrumb.mark(context, "event_loop", "${event.eventId} ${eventIdx + 1}/$eventsTotal")
    progress(PipelineProgress.EventStart(event.eventId, eventIdx + 1, eventsTotal))
    val eventAssets = event.assetIds.mapNotNull { assetMap[it] }
    DecisionStore.writeEventInputs(context, event, eventAssets)
    val timer = PerfTimer(event.eventId)
    try {
      if (event.eventId in resumeIds) {
        val cached = cachedDecisions[event.eventId]
        val cachedFinal = cached?.timelineFinal
        val cachedTone = cached?.director?.tone ?: "neutral"
        if (cachedFinal != null) {
          progress(PipelineProgress.EventStage(event.eventId, "render (resume from cache)", "timeline already exists; rendering cached cut"))
          val out = timer.render { renderer.render(cachedFinal, assetMap, cachedTone) }
          timer.finalize(context, perceptionCount, perceptionMs, cacheHits)
          if (out != null) {
            DecisionStore.writeTimelineFinal(context, event.eventId, out.timeline)
            outputs[event.eventId] = out.outputPath
            progress(PipelineProgress.EventDone(event.eventId, out.outputPath))
          } else {
            progress(PipelineProgress.EventFailed(event.eventId, "render returned null (resume)"))
          }
          return
        }
      }

      if (!isProcessableEvent(eventAssets)) {
        progress(PipelineProgress.EventFailed(event.eventId, "too few usable assets"))
        return
      }

      progress(PipelineProgress.EventStage(event.eventId, "browse", "building event contact sheet and EventMemory"))
      val memory = timer.browse { montage.browse(event.eventId, eventAssets) }
      DecisionStore.writeMemory(context, event.eventId, memory)

      progress(PipelineProgress.EventStage(event.eventId, "audience", "deriving viewer hook, payoff, and pacing"))
      val brief = timer.audience { audience.write(memory) }
      DecisionStore.writeAudience(context, event.eventId, brief)

      progress(PipelineProgress.EventStage(event.eventId, "director", "writing narrative arc and shot blueprint"))
      val plan = timer.director { director.draft(memory, brief, eventAssets.size) }
      DecisionStore.writeDirector(context, event.eventId, plan)

      progress(PipelineProgress.EventStage(event.eventId, "editor", "recalling candidates and selecting shot windows"))
      val v1 = timer.editor { buildTimeline(plan.shotBlueprint, plan.tone, event, eventAssets, perceptions, editor) }
      DecisionStore.writeTimelineV1(context, event.eventId, v1)

      progress(PipelineProgress.EventStage(event.eventId, "critic", "reviewing storyboard and requesting fixes"))
      val critiques = mutableListOf<com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Critique>()
      var working = v1
      var lastCritique: com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Critique? = null
      for (it in 1..MAX_CRITIC_ITER) {
        val crit = timer.critic { critic.review(it, working, plan, memory, assetMap) }
        critiques += crit
        lastCritique = crit
        if (crit.revisedRequests.isEmpty()) break
        working = applyRevisions(working, crit.revisedRequests, plan.tone, event, eventAssets, perceptions, editor)
      }
      lastCritique?.let { DecisionStore.writeCritique(context, event.eventId, it) }
      val finalTl = working.copy(critiqueHistory = critiques.toList())
      DecisionStore.writeTimelineFinal(context, event.eventId, finalTl)

      progress(PipelineProgress.EventStage(event.eventId, "render", "rendering shots, transitions, captions, and BGM"))
      val out = timer.render { renderer.render(finalTl, assetMap, plan.tone) }
      val perf = timer.finalize(context, perceptionCount, perceptionMs, cacheHits)
      Log.d(TAG, "perf ${event.eventId}: encoder=${com.google.ai.edge.gallery.customtasks.vlogpilot.render.EncoderProbe.selectedEncoderName()} total=${perf.totalMs}ms browse=${perf.browseMs} audience=${perf.audienceMs} director=${perf.directorMs} editor=${perf.editorMs} critic=${perf.criticMs} render=${perf.renderMs}")
      if (out != null) {
        DecisionStore.writeTimelineFinal(context, event.eventId, out.timeline)
        outputs[event.eventId] = out.outputPath
        progress(PipelineProgress.EventDone(event.eventId, out.outputPath))
      } else {
        progress(PipelineProgress.EventFailed(event.eventId, "render returned null"))
      }
    } catch (t: CancellationException) {
      throw t
    } catch (t: Throwable) {
      Log.e(TAG, "event ${event.eventId} failed", t)
      StateBreadcrumb.mark(context, "event_exception", "${event.eventId} ${t::class.java.simpleName}: ${t.message}")
      progress(PipelineProgress.EventFailed(event.eventId, t.message ?: t::class.java.simpleName))
    }
  }

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

  private fun isProcessableEvent(assets: List<Asset>): Boolean =
    assets.size >= 3 || assets.any { it.mediaType == MediaType.VIDEO && it.durationMs >= 15_000L }

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

  private fun needsVlmAnnotation(perception: Perception, asset: Asset): Boolean {
    if (perception.vlmTags.scene.isBlank()) return true
    if (asset.mediaType == MediaType.IMAGE) return false
    val expectedFrames = VideoFrameSheetBuilder.targetFrameCount(asset)
    return perception.videoInsight.frameTimestampsSec.size < expectedFrames
  }

  /**
   * Per-asset VLM annotation — extracted from run() so its many suspend points
   * (one per progress + one per annotator call) live in their own state machine.
   */
  private suspend fun runAnnotationPass(
    perceptions: HashMap<String, Perception>,
    assetMap: Map<String, Asset>,
    annotator: VlmAnnotator,
    progress: suspend (PipelineProgress) -> Unit,
  ) {
    StateBreadcrumb.mark(context, "annotate_plan", "planning annotation pass")
    val needsAnnotation = perceptions.values
      .mapNotNull { perc -> assetMap[perc.assetId]?.let { perc to it } }
      .filter { (perc, asset) -> !perc.isJunk && needsVlmAnnotation(perc, asset) }
    StateBreadcrumb.mark(context, "annotate_plan_done", "needs=${needsAnnotation.size}")
    if (needsAnnotation.isEmpty()) return

    val annotateStart = System.nanoTime()
    var annotatedCount = 0
    for ((idx, pair) in needsAnnotation.withIndex()) {
      val (perc, asset) = pair
      progress(
        PipelineProgress.Annotating(
          current = idx + 1,
          total = needsAnnotation.size,
          assetName = asset.displayName,
          mediaType = asset.mediaType.name.lowercase(),
          phase = "start",
        ),
      )
      val assetStart = System.nanoTime()
      val annotation = annotateAsset(asset, annotator, perc.sceneCuts)
      if (annotation != null && annotation.hasSignal()) {
        val updated = perc.copy(vlmTags = annotation.tags, videoInsight = annotation.videoInsight)
        perceptions[perc.assetId] = updated
        PerceptionCache.put(context, asset, updated)
        annotatedCount++
      }
      progress(
        PipelineProgress.Annotating(
          current = idx + 1,
          total = needsAnnotation.size,
          assetName = asset.displayName,
          mediaType = asset.mediaType.name.lowercase(),
          phase = if (annotation?.hasSignal() == true) "done" else "skipped",
          elapsedMs = (System.nanoTime() - assetStart) / 1_000_000,
        ),
      )
    }
    val annotateMs = (System.nanoTime() - annotateStart) / 1_000_000
    Log.d(TAG, "annotate: $annotatedCount/${needsAnnotation.size} assets in ${annotateMs}ms")
    progress(PipelineProgress.AnnotationDone(annotatedCount, needsAnnotation.size, annotateMs))
  }

  /** Annotate a single asset; returns null if loading failed or annotator threw. */
  private suspend fun annotateAsset(
    asset: Asset,
    annotator: VlmAnnotator,
    sceneCutsSec: List<Float> = emptyList(),
  ): VlmAnnotator.Annotation? {
    return try {
      if (asset.mediaType == MediaType.IMAGE) {
        val thumb = MediaLoader.loadImage(context, asset, maxSide = 512) ?: return null
        try { annotator.annotate(thumb, asset.mediaType.name.lowercase()) } finally { runCatching { thumb.recycle() } }
      } else {
        val sheet = VideoFrameSheetBuilder.build(context, asset, sceneCutsSec)
        if (sheet != null) {
          try { annotator.annotateVideo(sheet.bitmap, sheet.frameTimestampsSec, asset.mediaType.name.lowercase()) }
          finally { runCatching { sheet.bitmap.recycle() } }
        } else {
          val thumb = MediaLoader.loadImage(context, asset, maxSide = 512) ?: return null
          try { annotator.annotate(thumb, asset.mediaType.name.lowercase()) } finally { runCatching { thumb.recycle() } }
        }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "annotate failed for ${asset.id}: ${t.message}")
      null
    }
  }

  /**
   * Per-event Browser → Audience → Director → Editor → Critic → Render — the
   * heavy body of the events loop. Extracted from run() so each event's suspend
   * points live in their own state machine, keeping run()'s bytecode well below
   * ART's 64K limit. (Inline, run() exceeded the limit, ART fell back to
   * interpreter, and progress-callback suspensions resumed at label 0 → infinite
   * download/ingest/perceive replay on a single worker invocation.)
   */
  private suspend fun runEvent(
    event: Event,
    eventIdx: Int,
    eventsTotal: Int,
    assetMap: Map<String, Asset>,
    resumeIds: Set<String>,
    cachedDecisions: Map<String, EventDecisions>,
    perceptions: Map<String, Perception>,
    perceptionCount: Int,
    perceptionMs: Long,
    cacheHits: Int,
    montage: MontageAgent,
    audience: AudienceAgent,
    director: DirectorAgent,
    editor: EditorAgent,
    critic: CriticAgent,
    renderer: VideoRenderer,
    outputs: HashMap<String, String>,
    progress: suspend (PipelineProgress) -> Unit,
  ) {
    StateBreadcrumb.mark(context, "event_loop", "${event.eventId} ${eventIdx + 1}/$eventsTotal")
    progress(PipelineProgress.EventStart(event.eventId, eventIdx + 1, eventsTotal))
    val eventAssets = event.assetIds.mapNotNull { assetMap[it] }
    DecisionStore.writeEventInputs(context, event, eventAssets)
    val timer = PerfTimer(event.eventId)
    try {
      // Resume support: cached timeline_final exists but no MP4 — just re-render.
      if (event.eventId in resumeIds) {
        val cached = cachedDecisions[event.eventId]
        val cachedFinal = cached?.timelineFinal
        val cachedTone = cached?.director?.tone ?: "neutral"
        if (cachedFinal != null) {
          progress(PipelineProgress.EventStage(event.eventId, "render (resume from cache)", "timeline already exists; rendering cached cut"))
          val out = timer.render { renderer.render(cachedFinal, assetMap, cachedTone) }
          timer.finalize(context, perceptionCount, perceptionMs, cacheHits)
          if (out != null) {
            DecisionStore.writeTimelineFinal(context, event.eventId, out.timeline)
            outputs[event.eventId] = out.outputPath
            progress(PipelineProgress.EventDone(event.eventId, out.outputPath))
          } else {
            progress(PipelineProgress.EventFailed(event.eventId, "render returned null (resume)"))
          }
          return
        }
      }

      if (!isProcessableEvent(eventAssets)) {
        progress(PipelineProgress.EventFailed(event.eventId, "too few usable assets"))
        return
      }

      progress(PipelineProgress.EventStage(event.eventId, "browse", "building event contact sheet and EventMemory"))
      val memory = timer.browse { montage.browse(event.eventId, eventAssets) }
      DecisionStore.writeMemory(context, event.eventId, memory)

      progress(PipelineProgress.EventStage(event.eventId, "audience", "deriving viewer hook, payoff, and pacing"))
      val brief = timer.audience { audience.write(memory) }
      DecisionStore.writeAudience(context, event.eventId, brief)

      progress(PipelineProgress.EventStage(event.eventId, "director", "writing narrative arc and shot blueprint"))
      val plan = timer.director { director.draft(memory, brief, eventAssets.size) }
      DecisionStore.writeDirector(context, event.eventId, plan)

      progress(PipelineProgress.EventStage(event.eventId, "editor", "recalling candidates and selecting shot windows"))
      val v1 = timer.editor { buildTimeline(plan.shotBlueprint, plan.tone, event, eventAssets, perceptions, editor) }
      DecisionStore.writeTimelineV1(context, event.eventId, v1)

      progress(PipelineProgress.EventStage(event.eventId, "critic", "reviewing storyboard and requesting fixes"))
      val critiques = mutableListOf<com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Critique>()
      var working = v1
      var lastCritique: com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Critique? = null
      for (it in 1..MAX_CRITIC_ITER) {
        val crit = timer.critic { critic.review(it, working, plan, memory, assetMap) }
        critiques += crit
        lastCritique = crit
        if (crit.revisedRequests.isEmpty()) break
        working = applyRevisions(working, crit.revisedRequests, plan.tone, event, eventAssets, perceptions, editor)
      }
      lastCritique?.let { DecisionStore.writeCritique(context, event.eventId, it) }
      val finalTl = working.copy(critiqueHistory = critiques.toList())
      DecisionStore.writeTimelineFinal(context, event.eventId, finalTl)

      progress(PipelineProgress.EventStage(event.eventId, "render", "rendering shots, transitions, captions, and BGM"))
      val out = timer.render { renderer.render(finalTl, assetMap, plan.tone) }
      val perf = timer.finalize(context, perceptionCount, perceptionMs, cacheHits)
      Log.d(TAG, "perf ${event.eventId}: encoder=${com.google.ai.edge.gallery.customtasks.vlogpilot.render.EncoderProbe.selectedEncoderName()} total=${perf.totalMs}ms browse=${perf.browseMs} audience=${perf.audienceMs} director=${perf.directorMs} editor=${perf.editorMs} critic=${perf.criticMs} render=${perf.renderMs}")
      if (out != null) {
        DecisionStore.writeTimelineFinal(context, event.eventId, out.timeline)
        outputs[event.eventId] = out.outputPath
        progress(PipelineProgress.EventDone(event.eventId, out.outputPath))
      } else {
        progress(PipelineProgress.EventFailed(event.eventId, "render returned null"))
      }
    } catch (t: CancellationException) {
      throw t
    } catch (t: Throwable) {
      Log.e(TAG, "event ${event.eventId} failed", t)
      StateBreadcrumb.mark(context, "event_exception", "${event.eventId} ${t::class.java.simpleName}: ${t.message}")
      progress(PipelineProgress.EventFailed(event.eventId, t.message ?: t::class.java.simpleName))
    }
  }

  companion object {
    private const val TAG = "PipelineOrchestrator"
    private const val MAX_CRITIC_ITER = 2
  }
}
