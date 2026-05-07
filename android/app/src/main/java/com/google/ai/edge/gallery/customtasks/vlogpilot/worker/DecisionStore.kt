/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Persists each agent's output to filesDir/decisions/<eventId>/ as JSON so a
 * future viewer (PC-style index_v2.html equivalent) can render the AI's
 * decision chain. Mirrors what pc-pilot writes under workspace/agent_logs/.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.worker

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.customtasks.vlogpilot.perception.PerceptionCache
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.AudienceBrief
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Critique
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.DirectorBrief
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.EventMemory
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Timeline
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
)

object DecisionStore {

  private const val TAG = "DecisionStore"
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

  /** Read every event's persisted decisions into a list, sorted by eventId. Any
   *  partially-completed event (e.g. browse done but not director) just has the
   *  unwritten fields as null — the UI renders progressively. */
  fun loadAll(context: Context): List<EventDecisions> {
    val root = File(context.filesDir, "decisions")
    if (!root.isDirectory) return emptyList()
    val candidatesDir = File(context.filesDir, "candidates")
    return root.listFiles()?.filter { it.isDirectory }?.map { dir ->
      val eid = dir.name
      val mp4 = File(candidatesDir, "$eid.mp4").takeIf { it.isFile }?.absolutePath
      val inputs = readJson<EventInputManifest>(dir, "event_inputs.json")
      val inputAssets = inputs?.assets.orEmpty()
      val inputPerceptions = inputAssets.mapNotNull { asset ->
        PerceptionCache.get(context, asset.id)?.let { asset.id to it }
      }.toMap()
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
}
