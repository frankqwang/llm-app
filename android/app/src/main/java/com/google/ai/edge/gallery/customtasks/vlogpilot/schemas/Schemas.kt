/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Licensed under the Apache License, Version 2.0
 *
 * Kotlin equivalents of pc-pilot/schemas.py — the data contract that flows
 * between every pipeline step. Mirrors the Pydantic models 1:1 so the same
 * agent prompts and JSON parsers can be reused server-side and on-device.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.schemas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------- step0 ingest ----------------

@Serializable
enum class MediaType {
  @SerialName("image") IMAGE,
  @SerialName("video") VIDEO,
  @SerialName("live_photo") LIVE_PHOTO,
}

@Serializable
data class Asset(
  val id: String,                 // stable hash of contentUri
  val contentUri: String,         // content://media/external/images/media/123
  val displayName: String,
  val mediaType: MediaType,
  val takenEpochMs: Long,         // EXIF DateTimeOriginal, fallback DATE_TAKEN
  val widthPx: Int = 0,
  val heightPx: Int = 0,
  val durationMs: Long = 0,       // 0 for still images
  val sizeBytes: Long = 0,
  val livePhotoVideoUri: String? = null, // for LIVE_PHOTO, points to the paired mp4
  val orientation: Int = 0,       // 0/90/180/270
  val latitude: Double? = null,
  val longitude: Double? = null,
)

// ---------------- step1 perceive ----------------

@Serializable
data class FaceBox(
  val x: Float, val y: Float, val w: Float, val h: Float, // normalized 0..1
  val quality: Float = 0f,                                // sharpness * face_size
)

/**
 * VLM-produced semantic tags for an asset. Replaces the YOLO sceneClass +
 * MobileCLIP embedding combo that we never could ship as TFLite (no public
 * sub-50MB int8 sources). Gemma 4 E2B-IT produces this from a single
 * thumbnail in ~3-5s on Dimensity 9400 GPU; result is JSON-cached on disk
 * so repeated runs over the same album are free.
 */
@Serializable
data class VlmTags(
  val scene: String = "",                  // "户外烧烤聚会" / "晨间咖啡店内景"
  val subjects: List<String> = emptyList(), // ["三个朋友", "烤架"]
  val action: String = "",                 // "举杯" / "凝视窗外"
  val mood: String = "",                   // "热闹放松" / "宁静沉思"
  val timeFeel: String = "",               // "傍晚" / "清晨"
  val salient: String = "",                // <=40字 这张图最值得选的细节
  val narrativeRoleHint: String = "",      // opening/establishing/portrait/action/climax/transition/closing 之一，或空
)

@Serializable
data class VideoInsight(
  val frameTimestampsSec: List<Float> = emptyList(), // 1-based frame index -> timestamp in source video
  val summary: String = "",
  val actionArc: String = "",
  val bestMomentIndex: Int = 0,                      // 1-based index in the VLM frame sheet
  val bestMomentSec: Float = 0f,
  /** Inclusive 1-based window of frame indices flagged as the action peak.
   *  Used by Recall.expandWindows to seed multiple trim candidates spanning
   *  the model-identified peak. Both default to bestMomentIndex for legacy
   *  videoInsight JSONs that pre-date this field. */
  val bestMomentWindowStart: Int = 0,
  val bestMomentWindowEnd: Int = 0,
  val badMomentIndices: List<Int> = emptyList(),     // 1-based indices to avoid when trimming
)

@Serializable
data class Perception(
  val assetId: String,
  val isJunk: Boolean = false,
  val junkReason: String = "",
  val sharpness: Float = 0f,
  val brightness: Float = 0f,
  val faces: List<FaceBox> = emptyList(),
  val nsfwScore: Float = 0f,
  /** VLM-produced tags. Empty (scene.isBlank()) = needs annotation pass. */
  val vlmTags: VlmTags = VlmTags(),
  val videoInsight: VideoInsight = VideoInsight(),
  // video-only
  val sceneCuts: List<Float> = emptyList(), // seconds where scene changes
  val fps: Float = 0f,
)

// ---------------- step2 segment ----------------

@Serializable
data class Event(
  val eventId: String,
  val assetIds: List<String>,
  val startEpochMs: Long,
  val endEpochMs: Long,
  val placeHint: String = "",
  /** Non-null when this Event was constructed from a user curation request
   *  rather than auto-segmented. EventSelector / EventScout skip these — they
   *  don't compete with auto events for the maxEvents slot. */
  val userCuration: UserCurationRequest? = null,
  /** IntentParserAgent.parseInitial output. Populated lazily by the
   *  orchestrator after Gemma is ready (intent parsing is a model call). */
  val userBrief: UserBrief? = null,
)

// ---------------- step3 montage ----------------

@Serializable
data class KeyMoment(val imageIndex: Int, val assetId: String, val why: String)

@Serializable
data class SubGroup(val indices: List<Int>, val label: String)

@Serializable
data class EventMemory(
  val eventId: String,
  val storylineSummary: String,
  val keyMoments: List<KeyMoment> = emptyList(),
  val emotionalArc: String = "",
  val charactersObserved: List<String> = emptyList(),
  val visualStyleSignals: String = "",
  val notableSubgroups: List<SubGroup> = emptyList(),
)

// ---------------- step3b audience ----------------

@Serializable
enum class Pace {
  @SerialName("snappy") SNAPPY,
  @SerialName("balanced") BALANCED,
  @SerialName("lingering") LINGERING,
}

@Serializable
data class AudienceBrief(
  val eventId: String,
  val emotionalPayoff: String,
  val hookStrategy: String,
  val povVoice: String,
  val pacingGuidance: String,
  /** Discrete pacing handle the Director can map to target_duration_sec. */
  val pace: Pace = Pace.BALANCED,
  val avoidList: List<String> = emptyList(),
)

// ---------------- step4 director ----------------

@Serializable
enum class ShotRole {
  @SerialName("opening") OPENING,
  @SerialName("establishing") ESTABLISHING,
  @SerialName("portrait") PORTRAIT,
  @SerialName("action") ACTION,
  @SerialName("climax") CLIMAX,
  @SerialName("transition") TRANSITION,
  @SerialName("closing") CLOSING,
}

@Serializable
data class ShotRequest(
  val position: Int,
  val role: ShotRole,
  val moodTarget: String,
  val visualRequirements: String,
  val durationSec: Float,
  val personConstraint: String? = null,
  val captionText: String = "",
  val kenBurnsHint: String = "",        // "in" / "out" / "pan_left" / ""
  val transitionInHint: String = "",    // "fade" / "fadewhite" / "smoothleft" / "cut" / ""
)

@Serializable
data class DirectorBrief(
  val eventId: String,
  val title: String,
  val tagline: String? = null,
  val targetDurationSec: Float,
  val tone: String,
  /** Director-chosen color grade enum. NEUTRAL when the model didn't pick one;
   *  ColorGradeFromTone falls back to keyword inference on `tone` in that case. */
  val colorGrade: ColorGrade = ColorGrade.NEUTRAL,
  val narrativeArc: List<String>,
  val shotBlueprint: List<ShotRequest>,
)

// ---------------- step5/6 timeline ----------------

@Serializable
enum class ColorGrade {
  @SerialName("neutral") NEUTRAL,
  @SerialName("warm") WARM,
  @SerialName("cool") COOL,
  @SerialName("vibrant") VIBRANT,
  @SerialName("muted") MUTED,
  @SerialName("cinematic_teal_orange") CINEMATIC_TEAL_ORANGE,
  @SerialName("vintage") VINTAGE,
}

@Serializable
enum class TransitionKind {
  @SerialName("cut") CUT,
  @SerialName("fade") FADE,
  @SerialName("crossfade") CROSSFADE,
  @SerialName("fadeblack") FADEBLACK,
  @SerialName("fadewhite") FADEWHITE,
  @SerialName("slideleft") SLIDELEFT,
  @SerialName("slideright") SLIDERIGHT,
  @SerialName("circleopen") CIRCLEOPEN,
  @SerialName("circleclose") CIRCLECLOSE,
  @SerialName("zoomin") ZOOMIN,
  @SerialName("smoothleft") SMOOTHLEFT,
  @SerialName("smoothright") SMOOTHRIGHT,
}

@Serializable
data class VideoTrim(val startSec: Float, val endSec: Float)

@Serializable
data class ShotSpec(
  val order: Int,
  val assetId: String,
  val mediaType: MediaType,
  val durationSec: Float,                 // overwritten via .copy() by VideoRenderer with the probed mp4 duration
  val kenBurns: String = "",
  val colorGrade: ColorGrade = ColorGrade.NEUTRAL,
  val caption: String = "",
  val transitionIn: TransitionKind = TransitionKind.CUT,
  val videoTrim: VideoTrim? = null,
  val rationale: String = "",
)

@Serializable
enum class CriticVerdict {
  @SerialName("accept") ACCEPT,         // ship as-is
  @SerialName("revise") REVISE,         // fix the listed shots and re-cut
  @SerialName("abort") ABORT,           // timeline is unsalvageable; downgrade
}

@Serializable
data class Critique(
  val iteration: Int,
  val issues: List<String>,
  val verdict: CriticVerdict = CriticVerdict.ACCEPT,
  val revisedRequests: List<RevisedRequest> = emptyList(),
)

/**
 * Critic revision. The model can either:
 *  - return a full `newRequest` (legacy v3 format), OR
 *  - return `patches` — a small map of just the fields it wants to change,
 *    applied on top of the existing ShotRequest at `shotOrder`. Patch mode
 *    is much cheaper for the LLM (no need to re-emit 10 fields) and
 *    correspondingly more reliable on Gemma 4 E2B.
 */
@Serializable
data class RevisedRequest(
  val shotOrder: Int,
  val newRequest: ShotRequest? = null,
  val patches: Map<String, String> = emptyMap(),
)

@Serializable
data class Timeline(
  val eventId: String,
  val directorBriefRef: String,
  val shots: List<ShotSpec>,
  val critiqueHistory: List<Critique> = emptyList(),
  val final: Boolean = false,
)
