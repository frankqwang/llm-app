/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Persists each agent's output to filesDir/decisions/<eventId>/ as JSON so a
 * future viewer (PC-style index_v2.html equivalent) can render the AI's
 * decision chain. Mirrors what pc-pilot writes under workspace/agent_logs/.
 */
package com.vlogcopilot.worker

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.vlogcopilot.perception.PerceptionCache
import com.vlogcopilot.schemas.AudienceBrief
import com.vlogcopilot.schemas.Asset
import com.vlogcopilot.schemas.Critique
import com.vlogcopilot.schemas.DirectorBrief
import com.vlogcopilot.schemas.Event
import com.vlogcopilot.schemas.EventScout
import com.vlogcopilot.schemas.EventMemory
import com.vlogcopilot.schemas.Perception
import com.vlogcopilot.schemas.Timeline
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class EventInputManifest(
  val event: Event,
  val assets: List<Asset>,
)

data class EventDecisions(
  val eventId: String,
  val event: Event? = null,
  val inputAssets: List<Asset> = emptyList(),
  val inputPerceptions: Map<String, Perception> = emptyMap(),
  val memory: EventMemory? = null,
  val audience: AudienceBrief? = null,
  val director: DirectorBrief? = null,
  val timelineV1: Timeline? = null,
  val timelineFinal: Timeline? = null,
  val critique: Critique? = null,
  val mp4Path: String? = null,
  val perf: StagePerf? = null,
  /** Previous version's mp4 path, populated from IterationStore. Null when
   *  no iteration has happened yet (only the initial v1 exists) or when the
   *  archived file went missing. UI reads this for the 上一版 arrow. */
  val previousMp4Path: String? = null,
  /** Total number of versions for this event (1 = initial only, 2+ = iterated). */
  val versionCount: Int = 1,
)

object DecisionStore {

  private const val TAG = "DecisionStore"
  private const val EVENT_SELECTION_FILE = "_event_selection.json"
  private val json = Json { prettyPrint = true; encodeDefaults = true }

  fun dirFor(context: Context, eventId: String): File =
    File(context.filesDir, "decisions/$eventId").apply { mkdirs() }

  fun writeEventInputs(context: Context, event: Event, assets: List<Asset>) =
    write(context, event.eventId, "event_inputs.json", json.encodeToString(EventInputManifest(event, assets)))

  fun writeMemory(context: Context, eventId: String, m: EventMemory) =
    write(context, eventId, "event_memory.json", json.encodeToString(m))

  fun writeAudience(context: Context, eventId: String, b: AudienceBrief) =
    write(context, eventId, "audience.json", json.encodeToString(b))

  fun writeDirector(context: Context, eventId: String, d: DirectorBrief) =
    write(context, eventId, "director.json", json.encodeToString(d))

  fun writeTimelineV1(context: Context, eventId: String, t: Timeline) =
    write(context, eventId, "timeline_v1.json", json.encodeToString(t))

  fun writeTimelineFinal(context: Context, eventId: String, t: Timeline) =
    write(context, eventId, "timeline_final.json", json.encodeToString(t))

  fun writeCritique(context: Context, eventId: String, c: Critique) =
    write(context, eventId, "critique.json", json.encodeToString(c))

  /** Lists all events that have at least one decision artifact persisted. */
  fun listEvents(context: Context): List<String> {
    val root = File(context.filesDir, "decisions")
    if (!root.isDirectory) return emptyList()
    return root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()
  }

  fun writeEventSelection(context: Context, manifest: EventSelectionManifest) {
    try {
      val root = File(context.filesDir, "decisions").apply { mkdirs() }
      File(root, EVENT_SELECTION_FILE).writeText(json.encodeToString(manifest))
    } catch (t: Throwable) {
      Log.w(TAG, "failed to persist $EVENT_SELECTION_FILE: ${t.message}")
    }
  }

  fun loadEventSelection(context: Context): EventSelectionManifest? {
    val file = File(File(context.filesDir, "decisions"), EVENT_SELECTION_FILE)
    if (!file.isFile) return null
    return try {
      json.decodeFromString<EventSelectionManifest>(file.readText())
    } catch (t: Throwable) {
      Log.w(TAG, "failed to read $EVENT_SELECTION_FILE: ${t.message}")
      null
    }
  }

  fun writeEventScout(context: Context, scout: EventScout) {
    try {
      val root = File(context.filesDir, "event_scouts").apply { mkdirs() }
      File(root, "${scout.eventId}.json").writeText(json.encodeToString(scout))
    } catch (t: Throwable) {
      Log.w(TAG, "failed to persist event scout ${scout.eventId}: ${t.message}")
    }
  }

  fun loadEventScout(context: Context, eventId: String, expectedSignature: String? = null): EventScout? {
    val file = File(File(context.filesDir, "event_scouts"), "$eventId.json")
    if (!file.isFile) return null
    return try {
      val scout = json.decodeFromString<EventScout>(file.readText())
      if (expectedSignature != null && scout.assetSignature != expectedSignature) {
        file.delete()
        null
      } else {
        scout
      }
    } catch (t: Throwable) {
      Log.w(TAG, "failed to read event scout $eventId: ${t.message}")
      file.delete()
      null
    }
  }

  /** Loads a single event's decisions. Same composition as loadAll but skips
   *  the directory scan — used by the iteration path which already has an
   *  eventId in hand. Returns null if the event has no persisted artifacts. */
  fun loadEvent(context: Context, eventId: String): EventDecisions? {
    val dir = File(File(context.filesDir, "decisions"), eventId)
    if (!dir.isDirectory) return null
    val candidatesDir = File(context.filesDir, "candidates")
    val mp4 = File(candidatesDir, "$eventId.mp4").takeIf { it.isPlayableMp4Candidate() }?.absolutePath
    val inputs = readJson<EventInputManifest>(dir, "event_inputs.json")
    val inputAssets = inputs?.assets.orEmpty()
    val inputPerceptions = inputAssets.mapNotNull { asset ->
      (PerceptionCache.get(context, asset) ?: PerceptionCache.get(context, asset.id))?.let { asset.id to it }
    }.toMap()
    val history = IterationStore.loadHistory(context, eventId)
    val previousMp4 = IterationStore.previousVersionPath(context, eventId)
    return EventDecisions(
      eventId = eventId,
      event = inputs?.event,
      inputAssets = inputAssets,
      inputPerceptions = inputPerceptions,
      memory = readJson(dir, "event_memory.json"),
      audience = readJson(dir, "audience.json"),
      director = readJson(dir, "director.json"),
      timelineV1 = readJson(dir, "timeline_v1.json"),
      timelineFinal = readJson(dir, "timeline_final.json"),
      critique = readJson(dir, "critique.json"),
      mp4Path = mp4,
      perf = readJson(dir, "perf.json"),
      previousMp4Path = previousMp4,
      versionCount = history?.iterations?.size ?: if (mp4 != null) 1 else 0,
    )
  }

  /** Read every event's persisted decisions into a list, sorted by eventId. Any
   *  partially-completed event (e.g. browse done but not director) just has the
   *  unwritten fields as null — the UI renders progressively. */
  fun loadAll(context: Context): List<EventDecisions> {
    val root = File(context.filesDir, "decisions")
    if (!root.isDirectory) return emptyList()
    val candidatesDir = File(context.filesDir, "candidates")
    return root.listFiles()?.filter { it.isDirectory }?.map { dir ->
      val eid = dir.name
      val mp4 = File(candidatesDir, "$eid.mp4").takeIf { it.isPlayableMp4Candidate() }?.absolutePath
      val inputs = readJson<EventInputManifest>(dir, "event_inputs.json")
      val inputAssets = inputs?.assets.orEmpty()
      val inputPerceptions = inputAssets.mapNotNull { asset ->
        (PerceptionCache.get(context, asset) ?: PerceptionCache.get(context, asset.id))?.let { asset.id to it }
      }.toMap()
      val history = IterationStore.loadHistory(context, eid)
      EventDecisions(
        eventId = eid,
        event = inputs?.event,
        inputAssets = inputAssets,
        inputPerceptions = inputPerceptions,
        memory = readJson(dir, "event_memory.json"),
        audience = readJson(dir, "audience.json"),
        director = readJson(dir, "director.json"),
        timelineV1 = readJson(dir, "timeline_v1.json"),
        timelineFinal = readJson(dir, "timeline_final.json"),
        critique = readJson(dir, "critique.json"),
        mp4Path = mp4,
        perf = readJson(dir, "perf.json"),
        previousMp4Path = IterationStore.previousVersionPath(context, eid),
        versionCount = history?.iterations?.size ?: if (mp4 != null) 1 else 0,
      )
    }.orEmpty().sortedWith(
      compareByDescending<EventDecisions> { it.event?.endEpochMs ?: Long.MIN_VALUE }
        .thenByDescending { it.eventId },
    )
  }

  private inline fun <reified T> readJson(dir: File, name: String): T? {
    val f = File(dir, name)
    if (!f.isFile) return null
    return try { json.decodeFromString<T>(f.readText()) } catch (_: Throwable) { null }
  }

  private fun write(context: Context, eventId: String, fileName: String, content: String) {
    try {
      File(dirFor(context, eventId), fileName).writeText(content)
    } catch (t: Throwable) {
      Log.w(TAG, "failed to persist $eventId/$fileName: ${t.message}")
    }
  }

  private fun File.isPlayableMp4Candidate(): Boolean {
    if (!isFile || length() <= 1024L) return false
    return runCatching {
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(absolutePath)
        val durationMs =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: 0L
        val hasVideo =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        durationMs > 0L && hasVideo
      } finally {
        runCatching { retriever.release() }
      }
    }.getOrDefault(false)
  }
}
