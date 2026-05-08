/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * UI state holder for VlogPilot. It exposes both the durable decision artifacts
 * and a live progress snapshot so the process viewer can explain what the
 * on-device agents are doing while long VLM calls are running.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.ai.edge.gallery.customtasks.vlogpilot.agents.AgentRuntime
import com.google.ai.edge.gallery.customtasks.vlogpilot.ingest.PhotoIngest
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.EventSegmenter
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.EventScoutSheetBuilder
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.GenerationIntent
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.PowerProfile
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.VlogPilotRunConfig
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.VlogPilotModelRegistry
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.DecisionStore
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionManifest
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionPlanner
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionStatus
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventScoutRunner
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.PipelineEventBus
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.PipelineProgress
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.StateBreadcrumb
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.VlogPipelineWorker
import com.google.ai.edge.gallery.data.Model
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PipelineState {
  data object Idle : PipelineState
  data class Scanning(val phase: String) : PipelineState
  data class Ready(val assets: List<Asset>, val events: List<Event>) : PipelineState
  data class Running(val message: String) : PipelineState
  data class Done(val outputs: List<EventResult>) : PipelineState
  data class Error(val message: String) : PipelineState
}

data class EventResult(val eventId: String, val outputPath: String)

private data class CandidateRefreshResult(
  val assets: List<Asset>,
  val events: List<Event>,
  val manifest: EventSelectionManifest,
)

data class ProgressSnapshot(
  val headline: String = "等待开始",
  val detail: String = "",
  val stage: String = "idle",
  val current: Int = 0,
  val total: Int = 0,
  val assetName: String = "",
  val mediaType: String = "",
  val elapsedMs: Long = 0L,
  val recent: List<String> = emptyList(),
)

@HiltViewModel
class VlogPilotViewModel @Inject constructor(
  @ApplicationContext private val appContext: Context,
) : ViewModel() {

  private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
  val state: StateFlow<PipelineState> = _state.asStateFlow()

  private val _decisions = MutableStateFlow<List<EventDecisions>>(emptyList())
  val decisions: StateFlow<List<EventDecisions>> = _decisions.asStateFlow()

  private val _progress = MutableStateFlow(ProgressSnapshot())
  val progress: StateFlow<ProgressSnapshot> = _progress.asStateFlow()

  private val _runConfig = MutableStateFlow(VlogPilotRunConfig.load(appContext))
  val runConfig: StateFlow<VlogPilotRunConfig> = _runConfig.asStateFlow()

  private val _eventSelection = MutableStateFlow<EventSelectionManifest?>(null)
  val eventSelection: StateFlow<EventSelectionManifest?> = _eventSelection.asStateFlow()

  private val collectedOutputs = mutableMapOf<String, String>()
  private var decisionRefreshJob: Job? = null
  private val workManager by lazy { WorkManager.getInstance(appContext) }

  init {
    viewModelScope.launch {
      PipelineEventBus.flow.collect { progress -> handleProgress(progress) }
    }
    viewModelScope.launch {
      _decisions.value = withContext(Dispatchers.IO) { DecisionStore.loadAll(appContext) }
      _eventSelection.value = withContext(Dispatchers.IO) { DecisionStore.loadEventSelection(appContext) }
      while (true) {
        delay(3_000)
        val s = _state.value
        if (s is PipelineState.Running || s is PipelineState.Scanning || s is PipelineState.Done) {
          _decisions.value = withContext(Dispatchers.IO) { DecisionStore.loadAll(appContext) }
          _eventSelection.value = withContext(Dispatchers.IO) { DecisionStore.loadEventSelection(appContext) }
        }
      }
    }
    viewModelScope.launch {
      while (true) {
        syncWorkState()
        delay(1_500)
      }
    }
  }

  fun scanAlbum(windowDays: Int = 30) {
    viewModelScope.launch {
      _state.value = PipelineState.Scanning("正在读取相册")
      _progress.value = progressWithRecent(ProgressSnapshot("正在读取相册", "扫描 MediaStore 中的图片、视频和 Live Photo", "scan"))
      try {
        val assets = PhotoIngest.loadRecent(appContext, windowDays)
        _state.value = PipelineState.Scanning("正在把 ${assets.size} 个素材切分成事件")
        _progress.value = progressWithRecent(ProgressSnapshot("正在切分事件", "按时间间隔和事件跨度合并相邻素材", "segment"))
        val events = EventSegmenter.segment(assets)
        _state.value = PipelineState.Ready(assets, events)
        _progress.value = progressWithRecent(ProgressSnapshot("预扫完成", "${assets.size} 个素材，${events.size} 个事件，可开始生成", "ready"))
      } catch (t: Throwable) {
        val msg = t.message ?: t::class.java.simpleName
        _state.value = PipelineState.Error(msg)
        _progress.value = progressWithRecent(ProgressSnapshot("扫描失败", msg, "error"))
      }
    }
  }

  fun refreshCandidates(model: Model, windowDays: Int = 30) {
    viewModelScope.launch {
      refreshCandidatesInternal(windowDays, model)
    }
  }

  fun setIntent(intent: GenerationIntent) {
    updateRunConfig(refreshCandidates = true) {
      copy(intent = intent)
    }
  }

  fun setPowerProfile(profile: PowerProfile) {
    updateRunConfig(refreshCandidates = false) {
      copy(powerProfile = profile)
    }
  }

  fun pinEvent(eventId: String) {
    updateRunConfig(refreshCandidates = false) {
      copy(
        pinnedEventIds = pinnedEventIds + eventId,
        excludedEventIds = excludedEventIds - eventId,
        onlySelectedEventIds = emptySet(),
      )
    }
  }

  fun excludeEvent(eventId: String) {
    updateRunConfig(refreshCandidates = false) {
      copy(
        pinnedEventIds = pinnedEventIds - eventId,
        excludedEventIds = excludedEventIds + eventId,
        forceRegenerateEventIds = forceRegenerateEventIds - eventId,
        onlySelectedEventIds = onlySelectedEventIds - eventId,
      )
    }
  }

  fun clearExcluded(eventId: String) {
    updateRunConfig(refreshCandidates = false) {
      copy(excludedEventIds = excludedEventIds - eventId)
    }
  }

  fun onlyGenerateEvent(eventId: String) {
    updateRunConfig(refreshCandidates = false) {
      copy(
        onlySelectedEventIds = setOf(eventId),
        excludedEventIds = excludedEventIds - eventId,
      )
    }
  }

  fun forceRegenerateEvent(eventId: String) {
    updateRunConfig(refreshCandidates = false) {
      copy(
        onlySelectedEventIds = setOf(eventId),
        forceRegenerateEventIds = forceRegenerateEventIds + eventId,
        excludedEventIds = excludedEventIds - eventId,
      )
    }
  }

  fun clearOnlySelected() {
    updateRunConfig(refreshCandidates = false) {
      copy(onlySelectedEventIds = emptySet())
    }
  }

  fun runOnlyEvent(eventId: String, model: Model) {
    viewModelScope.launch {
      val next = _runConfig.value.copy(
        onlySelectedEventIds = setOf(eventId),
        excludedEventIds = _runConfig.value.excludedEventIds - eventId,
      )
      persistRunConfig(next, refreshCandidates = false)
      runFullPipelineInternal(model)
    }
  }

  private fun updateRunConfig(
    refreshCandidates: Boolean,
    transform: VlogPilotRunConfig.() -> VlogPilotRunConfig,
  ) {
    viewModelScope.launch {
      persistRunConfig(transform(_runConfig.value), refreshCandidates)
    }
  }

  private suspend fun persistRunConfig(next: VlogPilotRunConfig, refreshCandidates: Boolean) {
    _runConfig.value = next
    withContext(Dispatchers.IO) { next.save(appContext) }
    if (!refreshCandidates) {
      val syncedManifest = _eventSelection.value?.syncWithRunConfig(next)
      if (syncedManifest != null) {
        _eventSelection.value = syncedManifest
        withContext(Dispatchers.IO) { DecisionStore.writeEventSelection(appContext, syncedManifest) }
      }
    }
    if (refreshCandidates && _state.value !is PipelineState.Running) {
      refreshCandidatesInternal()
    }
  }

  private fun EventSelectionManifest.syncWithRunConfig(config: VlogPilotRunConfig): EventSelectionManifest {
    val candidateIds = candidates.map { it.eventId }.toSet()
    val selectedIds = if (config.onlySelectedEventIds.isNotEmpty()) {
      config.onlySelectedEventIds.filter { it in candidateIds }
    } else {
      selectedEventIds.filter { it !in config.excludedEventIds }
    }
    val selectedSet = selectedIds.toSet()
    val candidatesWithStatus = candidates.map { candidate ->
      val status = when {
        candidate.eventId in config.excludedEventIds -> EventSelectionStatus.EXCLUDED
        candidate.eventId in selectedSet -> EventSelectionStatus.SELECTED
        candidate.status == EventSelectionStatus.EXCLUDED && candidate.eventId !in config.excludedEventIds -> EventSelectionStatus.NOT_SELECTED
        candidate.status == EventSelectionStatus.SELECTED && candidate.eventId !in selectedSet -> EventSelectionStatus.NOT_SELECTED
        else -> candidate.status
      }
      candidate.copy(status = status)
    }
    return copy(
      pinnedEventIds = config.pinnedEventIds.sorted(),
      excludedEventIds = config.excludedEventIds.sorted(),
      forceRegenerateEventIds = config.forceRegenerateEventIds.sorted(),
      onlySelectedEventIds = config.onlySelectedEventIds.sorted(),
      selectedEventIds = selectedIds,
      candidates = candidatesWithStatus,
    )
  }

  private suspend fun refreshCandidatesInternal(windowDays: Int = 30, model: Model? = null): EventSelectionManifest? {
    _state.value = PipelineState.Scanning("正在刷新候选事件")
    _progress.value = progressWithRecent(
      ProgressSnapshot(
        "正在刷新候选事件",
        if (model == null) "读取 MediaStore、事件切分和已有 scout 缓存" else "Gemma 将按 3x3 contact sheet 浏览候选事件",
        "candidate_refresh",
      ),
    )
    return try {
      val result = withContext(Dispatchers.IO) {
        val assets = PhotoIngest.loadRecent(appContext, windowDays)
        val events = EventSegmenter.segment(assets)
        val coarsePlan = EventSelectionPlanner.plan(
          context = appContext,
          assets = assets,
          events = events,
          runConfig = _runConfig.value,
        )
        val scouts = if (model != null && coarsePlan.rankedCandidates.isNotEmpty()) {
          AgentRuntime(appContext, model).use { agent ->
            agent.ensureInitialized()
            EventScoutRunner.scout(
              context = appContext,
              agentRuntime = agent,
              candidates = coarsePlan.rankedCandidates,
              runConfig = _runConfig.value,
            ) { scout ->
              _progress.value = progressWithRecent(
                ProgressSnapshot(
                  "VLM 浏览候选事件 ${scout.eventIndex}/${scout.eventCount}",
                  "${scout.eventId} · 3x3 第 ${scout.pageIndex}/${scout.pageCount} 页 · ${if (scout.cacheHit) "读取 scout 缓存" else "Gemma 正在看 contact sheet"}",
                  "event_scout",
                  current = scout.pageIndex,
                  total = scout.pageCount,
                ),
              )
            }
          }
        } else {
          coarsePlan.rankedCandidates.mapNotNull { candidate ->
            val signature = EventScoutSheetBuilder.signature(candidate.assets, _runConfig.value.powerProfile)
            DecisionStore.loadEventScout(appContext, candidate.eventId, signature)?.let { candidate.eventId to it }
          }.toMap()
        }
        val plan = EventSelectionPlanner.plan(
          context = appContext,
          assets = assets,
          events = events,
          runConfig = _runConfig.value,
          scouts = scouts,
        )
        DecisionStore.writeEventSelection(appContext, plan.manifest)
        CandidateRefreshResult(assets, events, plan.manifest)
      }
      _eventSelection.value = result.manifest
      _state.value = PipelineState.Ready(result.assets, result.events)
      _progress.value = progressWithRecent(
        ProgressSnapshot(
          "候选事件已刷新",
          "扫描 ${result.assets.size} 个素材，切出 ${result.events.size} 个事件，候选 ${result.manifest.candidateCount} 个，选中 ${result.manifest.selectedEventIds.size} 个",
          "candidate_ready",
          current = result.manifest.selectedEventIds.size,
          total = result.manifest.candidateCount,
        ),
      )
      result.manifest
    } catch (t: Throwable) {
      val msg = t.message ?: t::class.java.simpleName
      _state.value = PipelineState.Error(msg)
      _progress.value = progressWithRecent(ProgressSnapshot("刷新候选失败", msg, "error"))
      null
    }
  }

  fun reportError(message: String) {
    _state.value = PipelineState.Error(message)
    _progress.value = progressWithRecent(ProgressSnapshot("错误", message, "error"))
  }

  fun cancelPipeline() {
    StateBreadcrumb.mark(appContext, "ui_cancel_request", "cancel unique work")
    workManager.cancelUniqueWork(VlogPipelineWorker.WORK_NAME)
    _state.value = PipelineState.Idle
    _progress.value = progressWithRecent(ProgressSnapshot("已取消", "后台生成任务已取消", "idle"))
  }

  fun runFullPipeline(model: Model) {
    viewModelScope.launch {
      runFullPipelineInternal(model)
    }
  }

  private suspend fun runFullPipelineInternal(model: Model) {
    StateBreadcrumb.mark(appContext, "ui_run_request", "model=${model.name}")
    val active = withContext(Dispatchers.IO) { activeWorkInfo() }
    if (active != null && !isStaleWork()) {
      StateBreadcrumb.mark(appContext, "ui_run_existing", "id=${active.id} state=${active.state}")
      _state.value = PipelineState.Running("已有后台任务正在运行")
      _progress.value = progressWithRecent(
        ProgressSnapshot(
          "已有后台任务正在运行",
          "没有重新入队，避免再次点击生成把当前 Worker 取消；需要重跑请先点取消",
          "work_running",
        ),
      )
      return
    }

    val cachedManifest = withContext(Dispatchers.IO) { DecisionStore.loadEventSelection(appContext) }
    val currentConfig = _runConfig.value
    val manifest = if (cachedManifest == null || cachedManifest.needsGenerationRefresh(currentConfig)) {
      refreshCandidatesInternal(30, model)
    } else {
      cachedManifest.syncWithRunConfig(currentConfig)
    }
    if (manifest == null) return
    if (manifest.selectedEventIds.isEmpty()) {
      val existing = listExistingCandidates().map { (id, path) -> EventResult(id, path) }
      _state.value = PipelineState.Done(existing)
      _progress.value = progressWithRecent(
        ProgressSnapshot(
          "没有待制作的故事",
          "先在故事页选一组故事，再点“开始制作”。",
          "no_selected_event",
        ),
      )
      return
    }

    collectedOutputs.clear()
    VlogPilotModelRegistry.stash(appContext, model)
    VlogPipelineWorker.ensureChannel(appContext)
    val req = OneTimeWorkRequestBuilder<VlogPipelineWorker>().build()
    val policy = if (active != null) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
    StateBreadcrumb.mark(appContext, "ui_enqueue", "policy=$policy request=${req.id}")
    workManager.enqueueUniqueWork(VlogPipelineWorker.WORK_NAME, policy, req)
    _state.value = PipelineState.Running("任务已加入后台队列")
    _progress.value = progressWithRecent(
      ProgressSnapshot(
        "开始制作视频",
        "我会按你选中的故事来剪，完成后会出现在“视频”里。",
        "queued",
      ),
    )
  }

  private fun EventSelectionManifest.needsGenerationRefresh(config: VlogPilotRunConfig): Boolean {
    if (intent != config.intent || powerProfile != config.powerProfile) return true
    if (pinnedEventIds.toSet() != config.pinnedEventIds) return true
    if (excludedEventIds.toSet() != config.excludedEventIds) return true
    if (forceRegenerateEventIds.toSet() != config.forceRegenerateEventIds) return true
    if (onlySelectedEventIds.toSet() != config.onlySelectedEventIds) return true
    if (candidates.isEmpty()) return true

    val candidateIds = candidates.map { it.eventId }.toSet()
    val importantIds = config.onlySelectedEventIds + config.pinnedEventIds + config.forceRegenerateEventIds
    if (importantIds.any { it !in candidateIds }) return true

    val scoutIds = candidates
      .filter { it.rankingMode == "vlm_scout" }
      .map { it.eventId }
      .toSet()
    if (scoutIds.isEmpty()) return true
    if (importantIds.any { it !in scoutIds }) return true
    if (selectedEventIds.isEmpty() && candidates.none { it.rankingMode == "vlm_scout" }) return true
    return false
  }

  private fun handleProgress(p: PipelineProgress) {
    val snapshot = snapshotFor(p)
    when (p) {
      is PipelineProgress.EventDone -> {
        collectedOutputs[p.eventId] = p.outputPath
        _state.value = PipelineState.Running(snapshot.headline)
      }
      PipelineProgress.AllDone -> {
        val all = collectedOutputs.toList()
          .ifEmpty { listExistingCandidates() }
          .map { (id, path) -> EventResult(id, path) }
        _state.value = PipelineState.Done(all)
      }
      is PipelineProgress.Failed -> _state.value = PipelineState.Error(p.message)
      else -> _state.value = PipelineState.Running(snapshot.headline)
    }
    _progress.value = progressWithRecent(snapshot)
    if (shouldRefreshDecisions(p)) scheduleDecisionRefresh()
  }

  private fun shouldRefreshDecisions(p: PipelineProgress): Boolean = when (p) {
    is PipelineProgress.DownloadingModels,
    PipelineProgress.Ingesting,
    is PipelineProgress.ScoutingEvents,
    is PipelineProgress.Perceiving,
    is PipelineProgress.Annotating -> false
    else -> true
  }

  private fun snapshotFor(p: PipelineProgress): ProgressSnapshot = when (p) {
    is PipelineProgress.DownloadingModels -> ProgressSnapshot(
      headline = "下载/检查模型 ${p.percent}%",
      detail = p.label,
      stage = "download",
      current = p.percent,
      total = 100,
    )
    PipelineProgress.Ingesting -> ProgressSnapshot("扫描相册", "读取 MediaStore，过滤非相机目录和过大文件", "ingest")
    is PipelineProgress.ScoutingEvents -> ProgressSnapshot(
      headline = "VLM 浏览候选事件 ${p.currentEvent}/${p.totalEvents}",
      detail = "${p.eventId} · 3x3 第 ${p.currentPage}/${p.totalPages} 页 · ${if (p.cacheHit) "读取 scout 缓存" else "Gemma 正在看 contact sheet"}",
      stage = "event_scout",
      current = p.currentPage,
      total = p.totalPages,
    )
    is PipelineProgress.SelectingEvents -> ProgressSnapshot(
      headline = "筛选高价值事件 ${p.selectedCount}/${p.candidateCount}",
      detail = p.detail,
      stage = "event_select",
      current = p.selectedCount,
      total = p.candidateCount,
    )
    is PipelineProgress.IngestDone -> ProgressSnapshot(
      headline = "相册扫描完成",
      detail = "已收集 ${p.assetCount} 个输入素材，切出 ${p.eventCount} 个事件；接下来做本地视觉感知",
      stage = "ingest_done",
      current = p.assetCount,
      total = p.assetCount,
    )
    is PipelineProgress.Perceiving -> ProgressSnapshot(
      headline = "视觉感知 ${p.current}/${p.total}",
      detail = "${p.assetName.ifBlank { "当前素材" }} · ${p.mediaType.ifBlank { "media" }} · ${if (p.cacheHit) "读取缓存" else "计算清晰度/亮度/人脸/NSFW/视频切点"}",
      stage = "perceive",
      current = p.current,
      total = p.total,
      assetName = p.assetName,
      mediaType = p.mediaType,
    )
    is PipelineProgress.Annotating -> {
      val action = when (p.phase) {
        "start" -> "Gemma 正在看素材"
        "skipped" -> "语义标注跳过"
        else -> "语义标注完成"
      }
      val why = if (p.mediaType.contains("video") || p.mediaType.contains("live")) {
        "视频会按时长和切点自适应抽帧，让 VLM 判断动作变化和 best moment"
      } else {
        "图片会送 512px 缩略图，让 VLM 输出 scene/subjects/action/mood"
      }
      ProgressSnapshot(
        headline = "素材语义标注 ${p.current}/${p.total}",
        detail = "$action：${p.assetName.ifBlank { "当前素材" }} · ${p.mediaType.ifBlank { "media" }} · $why${if (p.elapsedMs > 0) " · 用时 ${formatDuration(p.elapsedMs)}" else ""}",
        stage = "annotate",
        current = p.current,
        total = p.total,
        assetName = p.assetName,
        mediaType = p.mediaType,
        elapsedMs = p.elapsedMs,
      )
    }
    is PipelineProgress.AnnotationDone -> ProgressSnapshot(
      headline = "素材语义标注完成",
      detail = "完成 ${p.annotated}/${p.total} 个素材，用时 ${formatDuration(p.elapsedMs)}；接下来进入事件 browse / director / editor",
      stage = "annotate_done",
      current = p.annotated,
      total = p.total,
      elapsedMs = p.elapsedMs,
    )
    is PipelineProgress.EventStart -> ProgressSnapshot(
      headline = "处理事件 ${p.index}/${p.total}",
      detail = "${p.eventId}：开始生成事件级故事和时间线",
      stage = "event_start",
      current = p.index,
      total = p.total,
    )
    is PipelineProgress.EventStage -> ProgressSnapshot(
      headline = "${p.eventId}: ${stageLabel(p.stage)}",
      detail = p.detail.ifBlank { stageDetail(p.stage) },
      stage = p.stage,
    )
    is PipelineProgress.EventDone -> ProgressSnapshot(
      headline = "${p.eventId} 已生成",
      detail = p.outputPath,
      stage = "event_done",
    )
    is PipelineProgress.EventFailed -> ProgressSnapshot(
      headline = "${p.eventId} 失败",
      detail = p.message,
      stage = "event_failed",
    )
    PipelineProgress.AllDone -> ProgressSnapshot("全部完成", "候选视频已写入本地 candidates 目录", "done")
    is PipelineProgress.Failed -> ProgressSnapshot("任务失败", p.message, "failed")
  }

  private fun progressWithRecent(snapshot: ProgressSnapshot): ProgressSnapshot {
    val line = listOfNotNull(
      snapshot.headline,
      snapshot.assetName.takeIf { it.isNotBlank() },
      snapshot.elapsedMs.takeIf { it > 0 }?.let { formatDuration(it) },
    ).joinToString(" · ")
    val recent = (listOf(line) + _progress.value.recent).distinct().take(8)
    return snapshot.copy(recent = recent)
  }

  private fun stageLabel(stage: String): String = when {
    stage.startsWith("browse") -> "浏览素材"
    stage.startsWith("audience") -> "观众策略"
    stage.startsWith("director") -> "导演分镜"
    stage.startsWith("editor") -> "剪辑选片"
    stage.startsWith("critic") -> "审片返工"
    stage.startsWith("render") -> "渲染成片"
    else -> stage
  }

  private fun stageDetail(stage: String): String = when {
    stage.startsWith("browse") -> "把事件素材拼成 contact sheet，生成 EventMemory"
    stage.startsWith("audience") -> "决定 hook、情绪回报、节奏和避坑项"
    stage.startsWith("director") -> "生成叙事弧线和 shot blueprint"
    stage.startsWith("editor") -> "召回候选素材/视频窗口，并让 VLM 选择镜头"
    stage.startsWith("critic") -> "看 storyboard，检查重复、高潮、节奏和结尾"
    stage.startsWith("render") -> "FFmpeg 渲染镜头、转场、字幕和 BGM"
    else -> ""
  }

  private fun scheduleDecisionRefresh() {
    if (decisionRefreshJob?.isActive == true) return
    decisionRefreshJob = viewModelScope.launch {
      delay(750)
      val (decisions, selection) = withContext(Dispatchers.IO) {
        DecisionStore.loadAll(appContext) to DecisionStore.loadEventSelection(appContext)
      }
      _decisions.value = decisions
      _eventSelection.value = selection
    }
  }

  private suspend fun syncWorkState() {
    val active = withContext(Dispatchers.IO) { activeWorkInfo() }
    if (active != null && _state.value !is PipelineState.Running && _state.value !is PipelineState.Scanning) {
      _state.value = PipelineState.Running("后台生成任务仍在运行")
      _progress.value = progressWithRecent(
        ProgressSnapshot(
          "后台生成任务仍在运行",
          "WorkManager: ${active.state} · ${active.id}",
          "work_running",
        ),
      )
    }
  }

  private fun activeWorkInfo(): WorkInfo? {
    val activeStates = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
    return try {
      workManager.getWorkInfosForUniqueWork(VlogPipelineWorker.WORK_NAME)
        .get(2, TimeUnit.SECONDS)
        .firstOrNull { it.state in activeStates }
    } catch (_: Throwable) {
      null
    }
  }

  private fun isStaleWork(): Boolean {
    val stateFile = File(appContext.filesDir, "_state.txt")
    val lastTick = runCatching {
      stateFile.readText().substringBefore('\t').toLong()
    }.getOrNull() ?: return false
    return System.currentTimeMillis() - lastTick > STALE_WORK_HEARTBEAT_MS
  }

  private fun listExistingCandidates(): List<Pair<String, String>> {
    val dir = File(appContext.filesDir, "candidates")
    return dir.listFiles()
      ?.filter { it.isFile && it.name.endsWith(".mp4") }
      ?.map { it.nameWithoutExtension to it.absolutePath }
      ?.sortedBy { it.first }
      .orEmpty()
  }

  private fun formatDuration(ms: Long): String =
    if (ms < 1000) "${ms}ms" else "${ms / 1000}.${(ms % 1000) / 100}s"

  companion object {
    private const val STALE_WORK_HEARTBEAT_MS = 10 * 60 * 1000L
  }
}
