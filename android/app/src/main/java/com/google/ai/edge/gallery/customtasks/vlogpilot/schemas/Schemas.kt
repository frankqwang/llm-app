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
  val embedding: List<Float> = emptyList(),               // 192-dim face embedding
  val personId: String? = null,                           // assigned by clustering in step1
  val quality: Float = 0f,                                // sharpness * face_size
)

@Serializable
data class YoloObj(val cls: String, val conf: Float, val x: Float, val y: Float, val w: Float, val h: Float)

@Serializable
data class Perception(
  val assetId: String,
  val isJunk: Boolean = false,
  val junkReason: String = "",
  val sharpness: Float = 0f,
  val brightness: Float = 0f,
  val faces: List<FaceBox> = emptyList(),
  val yoloObjs: List<YoloObj> = emptyList(),
  val sceneClass: String = "unknown",       // YOLO majority / heuristic
  val clipEmbedding: List<Float> = emptyList(), // 512-d MobileCLIP image
  val nsfwScore: Float = 0f,
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
data class AudienceBrief(
  val eventId: String,
  val emotionalPayoff: String,
  val hookStrategy: String,
  val povVoice: String,
  val pacingGuidance: String,
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
data class Critique(
  val iteration: Int,
  val issues: List<String>,
  val revisedRequests: List<RevisedRequest> = emptyList(),
)

@Serializable
data class RevisedRequest(val shotOrder: Int, val newRequest: ShotRequest)

@Serializable
data class Timeline(
  val eventId: String,
  val directorBriefRef: String,
  val shots: List<ShotSpec>,
  val critiqueHistory: List<Critique> = emptyList(),
  val final: Boolean = false,
)
