/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * UI state holder for the M1 scaffolding. Drives ingest + segmentation and
 * exposes a sealed PipelineState the screen can render. M2-M5 grow this into
 * a full pipeline orchestrator.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.ai.edge.gallery.customtasks.vlogpilot.ingest.PhotoIngest
import com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline.EventSegmenter
import com.google.ai.edge.gallery.customtasks.vlogpilot.runtime.VlogPilotModelRegistry
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.DecisionStore
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.PipelineEventBus
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.PipelineProgress
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.VlogPipelineWorker
import com.google.ai.edge.gallery.data.Model
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

@HiltViewModel
class VlogPilotViewModel @Inject constructor(
  @ApplicationContext private val appContext: Context,
) : ViewModel() {

  private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
  val state: StateFlow<PipelineState> = _state.asStateFlow()

  private val _decisions = MutableStateFlow<List<EventDecisions>>(emptyList())
  val decisions: StateFlow<List<EventDecisions>> = _decisions.asStateFlow()

  init {
    // Listen to per-stage progress events.
    viewModelScope.launch {
      PipelineEventBus.flow.collect { progress -> handleProgress(progress) }
    }
    // Poll the decisions/ dir so the UI sees Browser/Director/Editor/Critic
    // outputs progressively appear as each agent finishes. Reading 5-7 JSON
    // files per event off the disk every refresh has to happen on Dispatchers.IO
    // — running it on Main causes the kind of jank the user notices when
    // scrolling/expanding cards mid-run.
    viewModelScope.launch {
      _decisions.value = withContext(Dispatchers.IO) { DecisionStore.loadAll(appContext) }
      while (true) {
        delay(3_000)
        val s = _state.value
        if (s is PipelineState.Running || s is PipelineState.Scanning || s is PipelineState.Done) {
          _decisions.value = withContext(Dispatchers.IO) { DecisionStore.loadAll(appContext) }
        }
      }
    }
  }

  fun scanAlbum(windowDays: Int = 30) {
    viewModelScope.launch {
      _state.value = PipelineState.Scanning("正在读取相册")
      try {
        val assets = PhotoIngest.loadRecent(appContext, windowDays)
        _state.value = PipelineState.Scanning("正在把 ${assets.size} 个素材切分成事件")
        val events = EventSegmenter.segment(assets)
        _state.value = PipelineState.Ready(assets, events)
      } catch (t: Throwable) {
        _state.value = PipelineState.Error(t.message ?: t::class.java.simpleName)
      }
    }
  }

  fun reportError(message: String) {
    _state.value = PipelineState.Error(message)
  }

  fun cancelPipeline() {
    WorkManager.getInstance(appContext).cancelUniqueWork(VlogPipelineWorker.WORK_NAME)
    _state.value = PipelineState.Idle
  }

  fun runFullPipeline(model: Model) {
    // The Worker can't easily inject Hilt singletons or look the Model up via task.models
    // (that's an in-memory ViewModel state). Stash the resolved Model here, then the worker
    // pulls it back. Single concurrent pipeline is enforced by enqueueUniqueWork+KEEP below.
    VlogPilotModelRegistry.stash(appContext, model)
    VlogPipelineWorker.ensureChannel(appContext)
    val req = OneTimeWorkRequestBuilder<VlogPipelineWorker>().build()
    WorkManager.getInstance(appContext)
      .enqueueUniqueWork(VlogPipelineWorker.WORK_NAME, ExistingWorkPolicy.KEEP, req)
    _state.value = PipelineState.Running("任务已加入后台队列")
  }

  private val collectedOutputs = mutableMapOf<String, String>()

  private fun handleProgress(p: PipelineProgress) {
    when (p) {
      is PipelineProgress.DownloadingModels -> _state.value = PipelineState.Running("下载模型 ${p.percent}% (${p.label})")
      PipelineProgress.Ingesting -> _state.value = PipelineState.Running("正在扫描相册")
      is PipelineProgress.IngestDone -> _state.value = PipelineState.Running("已收集 ${p.assetCount} 个素材，切出 ${p.eventCount} 个事件")
      is PipelineProgress.Perceiving -> _state.value = PipelineState.Running("视觉感知 ${p.current}/${p.total}")
      is PipelineProgress.EventStart -> _state.value = PipelineState.Running("处理事件 ${p.index}/${p.total} (${p.eventId})")
      is PipelineProgress.EventStage -> _state.value = PipelineState.Running("${p.eventId}: ${p.stage}")
      is PipelineProgress.EventDone -> {
        collectedOutputs[p.eventId] = p.outputPath
        _state.value = PipelineState.Running("${p.eventId} 已生成")
      }
      is PipelineProgress.EventFailed -> _state.value = PipelineState.Running("${p.eventId} 失败：${p.message}")
      PipelineProgress.AllDone -> {
        val all = collectedOutputs.toList()
          .ifEmpty { listExistingCandidates() }
          .map { (id, path) -> EventResult(id, path) }
        _state.value = PipelineState.Done(all)
      }
      is PipelineProgress.Failed -> _state.value = PipelineState.Error(p.message)
    }
  }

  private fun listExistingCandidates(): List<Pair<String, String>> {
    val dir = File(appContext.filesDir, "candidates")
    return dir.listFiles()
      ?.filter { it.isFile && it.name.endsWith(".mp4") }
      ?.map { it.nameWithoutExtension to it.absolutePath }
      ?.sortedBy { it.first }
      .orEmpty()
  }
}
