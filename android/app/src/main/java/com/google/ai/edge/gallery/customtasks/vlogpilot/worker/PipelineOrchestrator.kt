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
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.VideoFrameSheetBuilder
import com.google.ai.edge.gallery.customtasks.vlogpilot.render.VideoRenderer
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.PowerPacer
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.VlogPilotRunConfig
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.CriticVerdict
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.DirectorBrief
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
    runConfig: VlogPilotRunConfig = VlogPilotRunConfig.load(context),
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
    PowerPacer.setProfile(runConfig.powerProfile)
    StateBreadcrumb.mark(context, "orchestrator_start", "windowDays=$windowDays maxEvents=$maxEvents intent=${runConfig.intent.name} power=${runConfig.powerProfile.name}")
    StateBreadcrumb.mark(context, "power_pacing", "profile=${runConfig.powerProfile.name}; background priority + paced decode/model calls")
    PowerPacer.applyBackgroundThreadPriority()
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
    val coarsePlan = EventSelectionPlanner.plan(
      context = context,
      assets = assets,
      events = allEvents,
      runConfig = runConfig,
      maxEvents = maxEvents,
    )
    DecisionStore.writeEventSelection(context, coarsePlan.manifest)
    if (coarsePlan.rankedCandidates.isEmpty()) {
      progress(PipelineProgress.SelectingEvents(0, 0, "none"))
      progress(PipelineProgress.AllDone)
      return Result(emptyMap())
    }

    // 3. Boot Gemma 4 before final event selection. The VLM scout reads 3x3
    //    event contact sheets and supplies the semantic signal for valueScore.
    //    LlmChatModelHelper which the user already imported separately).
    val agent = AgentRuntime(context, gemmaModel)
    val outputs = HashMap<String, String>()
    try {
      StateBreadcrumb.mark(context, "init", "starting agent.ensureInitialized")
      agent.ensureInitialized()
      StateBreadcrumb.mark(context, "init_done", "agent ready")
      val scoutMap = EventScoutRunner.scout(
        context = context,
        agentRuntime = agent,
        candidates = coarsePlan.rankedCandidates,
        runConfig = runConfig,
      ) { scout ->
        progress(
          PipelineProgress.ScoutingEvents(
            currentEvent = scout.eventIndex,
            totalEvents = scout.eventCount,
            eventId = scout.eventId,
            currentPage = scout.pageIndex,
            totalPages = scout.pageCount,
            cacheHit = scout.cacheHit,
          ),
        )
      }
      val selectionPlan = EventSelectionPlanner.plan(
        context = context,
        assets = assets,
        events = allEvents,
        runConfig = runConfig,
        scouts = scoutMap,
        maxEvents = maxEvents,
      )
      DecisionStore.writeEventSelection(context, selectionPlan.manifest)
      val rankedCandidates = selectionPlan.rankedCandidates
      val events = selectionPlan.selectedEvents
      val keptAssetIds = events.flatMap { it.assetIds }.toSet()
      val candidateSummary = rankedCandidates.take(8).joinToString(" | ") { it.compactSummary() }
      val selectedSummary = selectionPlan.manifest.candidates
        .filter { it.eventId in selectionPlan.manifest.selectedEventIds }
        .joinToString(" | ") { "${it.eventId}:value=${(it.valueScore * 100).toInt()} reason=${it.reasons.joinToString("+")}" }
      Log.d(TAG, "events: selected=${events.size} resume=${selectionPlan.resumeEventIds.size} candidates=${rankedCandidates.size} intent=${runConfig.intent.name} power=${runConfig.powerProfile.name}")
      StateBreadcrumb.mark(context, "event_candidates", candidateSummary.ifBlank { "none" })
      StateBreadcrumb.mark(context, "event_select", selectedSummary.ifBlank { "none" })
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

      // 4. Perception over every asset that's actually used by a kept event.
      //    Per-asset Perception is JSON-cached; identical asset on a re-run skips
      //    the model calls entirely (~1-2s saved per asset).
      val perceptions = HashMap<String, Perception>(keptAssetIds.size)
      var perceptionMs = 0L
      var cacheHits = 0
      var perceptionCount = 0
      val perception = PerceptionEngine(context)
      try {
        val toAnalyze = assets.filter { it.id in keptAssetIds }
        perceptionCount = toAnalyze.size
        val perceptionStart = System.nanoTime()
        for ((i, asset) in toAnalyze.withIndex()) {
          val cached = PerceptionCache.get(context, asset)
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
          PowerPacer.afterPerceptionAsset(cacheHit = cached != null)
        }
        perceptionMs = (System.nanoTime() - perceptionStart) / 1_000_000
        Log.d(TAG, "perception: ${toAnalyze.size} assets in ${perceptionMs}ms (cache hits: $cacheHits)")
      } finally {
        perception.close()
      }

      StateBreadcrumb.mark(context, "agent_objects", "creating agents")
      val montage = MontageAgent(context, agent)
      val audience = AudienceAgent(agent)
      val director = DirectorAgent(agent)
      val editor = EditorAgent(context, agent)
      val critic = CriticAgent(context, agent)
      val annotator = VlmAnnotator(agent)
      val renderer = VideoRenderer(context)
      val resumeIds = selectionPlan.resumeEventIds
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

  // (NB: the live runAnnotationPass / annotateAsset / runEvent overloads are
  // further down — they take HashMap rather than MutableMap so the orchestrator
  // body's HashMap call sites resolve to those. Earlier copies of this file had
  // both overloads coexisting; the MutableMap-typed copies were dead and have
  // been removed.)

private suspend fun buildTimeline(
    blueprint: List<ShotRequest>,
    director: DirectorBrief,
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
        directorTone = director.tone,
        directorColorGrade = director.colorGrade,
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
          directorTone = director.tone,
          directorColorGrade = director.colorGrade,
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

  /**
   * Compact "what does this event actually contain" summary fed into the
   * Director prompt so its `visual_requirements` stay grounded in real
   * footage. Without this, Director hallucinates shot ideas (e.g.
   * "举杯特写") that recall can't satisfy and the Editor falls back to
   * reusing already-picked assets.
   *
   * Format (≤ ~25 lines):
   *   场景 户外烧烤聚会: 3 张 / 0 段视频
   *   场景 室内厨房:    4 张 / 0 段视频
   *   长视频片段:        1 段（25s, 户外打闹）
   *   人物子集:          黑长发女士=#1,3,5; 戴眼镜男士=#2,4,8
   */
  private fun buildAvailableSignals(
    eventAssets: List<Asset>,
    perceptions: Map<String, Perception>,
  ): String {
    if (eventAssets.isEmpty()) return ""
    val nonJunk = eventAssets.filter { perceptions[it.id]?.isJunk != true }
    if (nonJunk.isEmpty()) return ""

    // Group by VLM scene tag (or "未分类" when annotation hasn't filled in yet)
    val byScene = nonJunk.groupBy { perceptions[it.id]?.vlmTags?.scene?.takeIf { s -> s.isNotBlank() } ?: "未分类" }
    val sceneLines = byScene.entries
      .sortedByDescending { it.value.size }
      .take(8)
      .map { (scene, group) ->
        val photos = group.count { it.mediaType != MediaType.VIDEO }
        val videos = group.count { it.mediaType == MediaType.VIDEO }
        val totalVideoSec = group.filter { it.mediaType == MediaType.VIDEO }
          .sumOf { it.durationMs.coerceAtLeast(0L) } / 1000
        val seg = if (videos > 0) "$photos 张 / $videos 段视频(${totalVideoSec}s)" else "$photos 张"
        "  场景「$scene」: $seg"
      }

    val longVideos = nonJunk.filter { it.mediaType == MediaType.VIDEO && it.durationMs >= 12_000L }
    val longVideoLine = if (longVideos.isEmpty()) null else
      "  长视频片段: ${longVideos.size} 段（${longVideos.joinToString("; ") { v ->
        val tags = perceptions[v.id]?.vlmTags
        val label = listOfNotNull(tags?.scene?.takeIf { it.isNotBlank() }, tags?.action?.takeIf { it.isNotBlank() }).joinToString(" ").ifBlank { "无标签" }
        "${v.durationMs / 1000}s $label"
      }}）"

    // Salient highlights — pick a few of the brightest VLM tags so the director
    // sees concrete moments (e.g. "中间男士笑容自然") not just scene names.
    val salientLine = nonJunk.mapNotNull { perceptions[it.id]?.vlmTags?.salient?.takeIf { s -> s.isNotBlank() } }
      .distinct()
      .take(5)
      .takeIf { it.isNotEmpty() }
      ?.let { "  亮点提示: " + it.joinToString(" / ") }

    return (sceneLines + listOfNotNull(longVideoLine, salientLine)).joinToString("\n")
  }

  private suspend fun applyRevisions(
    base: Timeline,
    revisions: List<RevisedRequest>,
    director: DirectorBrief,
    event: Event,
    eventAssets: List<Asset>,
    perceptions: Map<String, Perception>,
    editor: EditorAgent,
  ): Timeline {
    val byOrder = base.shots.associateBy { it.order }.toMutableMap()
    // Keep ALL current picks in `used` so revised recall is forced to find a DIFFERENT asset.
    val used = base.shots.map { it.assetId }.toMutableSet()
    val nBlueprint = base.shots.size
    val originalRequests = director.shotBlueprint.associateBy { it.position }
    for (rev in revisions) {
      val original = byOrder[rev.shotOrder] ?: continue
      // Build the effective request by either:
      //  (1) using rev.newRequest if the model emitted a full ShotRequest (legacy path), OR
      //  (2) applying rev.patches as field-level updates on the director's original request
      //      for that slot (preferred patch path — much smaller, more reliable on Gemma 4 E2B).
      val effective = rev.newRequest
        ?: rev.patches.takeIf { it.isNotEmpty() }
          ?.let { patches -> originalRequests[rev.shotOrder]?.let { applyPatches(it, patches) } }
        ?: continue
      val replaced = editor.pickShot(
        order = rev.shotOrder,
        request = effective,
        nBlueprint = nBlueprint,
        eventAssets = eventAssets,
        perceptions = perceptions,
        excludedAssetIds = used,
        previousShot = "(revised slot)",
        directorTone = director.tone,
        directorColorGrade = director.colorGrade,
      )
      if (replaced == null || replaced.assetId == original.assetId) continue
      used.remove(original.assetId)
      used += replaced.assetId
      byOrder[rev.shotOrder] = replaced
    }
    return base.copy(shots = byOrder.values.sortedBy { it.order })
  }

  /**
   * Apply field-level patches to a ShotRequest. Unknown keys are ignored.
   * Defensive against Gemma 4 E2B quirks: blank strings on narrative fields
   * (mood / visual_requirements) are skipped — those would silently degrade
   * the spec; the model usually means "leave it" when it omits a field but
   * sometimes still emits an empty value. Cosmetic fields (caption / transition
   * / ken_burns) honor blanks because empty IS a valid "remove this" intent.
   */
  private fun applyPatches(base: ShotRequest, patches: Map<String, String>): ShotRequest {
    var role = base.role
    var moodTarget = base.moodTarget
    var visualReq = base.visualRequirements
    var dur = base.durationSec
    var person = base.personConstraint
    var caption = base.captionText
    var kenBurns = base.kenBurnsHint
    var transitionIn = base.transitionInHint
    for ((k, v) in patches) {
      when (k.trim().lowercase()) {
        "role" -> if (v.isNotBlank()) runCatching { role = com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRole.valueOf(v.uppercase()) }
        "mood_target", "mood" -> if (v.isNotBlank()) moodTarget = v
        "visual_requirements", "visual" -> if (v.isNotBlank()) visualReq = v
        "duration_sec", "duration" -> v.toFloatOrNull()?.let { dur = it.coerceIn(0.4f, 8f) }
        "person_constraint", "person" -> person = v.takeIf { it.isNotBlank() && it.lowercase() != "null" }
        "caption_text", "caption" -> caption = v
        "ken_burns_hint", "ken_burns" -> kenBurns = v
        "transition_in_hint", "transition_in", "transition" -> transitionIn = v
      }
    }
    return base.copy(
      role = role,
      moodTarget = moodTarget,
      visualRequirements = visualReq,
      durationSec = dur,
      personConstraint = person,
      captionText = caption,
      kenBurnsHint = kenBurns,
      transitionInHint = transitionIn,
    )
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
      PowerPacer.afterVlmAsset()
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
      val plan = timer.director {
        director.draft(
          memory = memory,
          audience = brief,
          assetCount = eventAssets.size,
          availableSignals = buildAvailableSignals(eventAssets, perceptions),
        )
      }
      DecisionStore.writeDirector(context, event.eventId, plan)

      progress(PipelineProgress.EventStage(event.eventId, "editor", "recalling candidates and selecting shot windows"))
      val v1 = timer.editor { buildTimeline(plan.shotBlueprint, plan, event, eventAssets, perceptions, editor) }
      DecisionStore.writeTimelineV1(context, event.eventId, v1)

      progress(PipelineProgress.EventStage(event.eventId, "critic", "reviewing storyboard and requesting fixes"))
      val critiques = mutableListOf<com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Critique>()
      var working = v1
      var lastCritique: com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Critique? = null
      var aborted = false
      critic_loop@ for (it in 1..MAX_CRITIC_ITER) {
        val crit = timer.critic { critic.review(it, working, plan, memory, assetMap) }
        critiques += crit
        lastCritique = crit
        when (crit.verdict) {
          CriticVerdict.ACCEPT -> break@critic_loop
          CriticVerdict.ABORT -> {
            // Critic believes patches can't fix this. Ship the latest `working`
            // (we have nothing better) but stop iterating so we don't burn
            // additional model calls on a known dead end.
            aborted = true
            StateBreadcrumb.mark(
              context,
              "critic_abort",
              "${event.eventId} iter=$it issues=${crit.issues.take(3).joinToString("; ")}",
            )
            Log.w(TAG, "critic abort on ${event.eventId} iter=$it: ${crit.issues.joinToString(" / ")}")
            break@critic_loop
          }
          CriticVerdict.REVISE -> {
            if (crit.revisedRequests.isEmpty()) {
              // Malformed: model said REVISE but emitted no patches. Treat as
              // accept-with-warning rather than spinning the loop forever.
              StateBreadcrumb.mark(
                context,
                "critic_revise_empty",
                "${event.eventId} iter=$it verdict=REVISE but no requests",
              )
              break@critic_loop
            }
            working = applyRevisions(working, crit.revisedRequests, plan, event, eventAssets, perceptions, editor)
          }
        }
      }
      lastCritique?.let { DecisionStore.writeCritique(context, event.eventId, it) }
      if (aborted) {
        progress(PipelineProgress.EventStage(event.eventId, "critic", "abort verdict; rendering current cut anyway"))
      }
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
