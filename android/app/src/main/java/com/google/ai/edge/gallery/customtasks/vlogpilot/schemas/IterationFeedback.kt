/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Schema for the user's feedback on a generated vlog. The same data type covers
 * both the simple "tap a chip and send" path (Milestone A) and the richer
 * natural-language + tap-to-target path (Milestone C). IntentParserAgent
 * populates parsedScope / parsedRevisions / parsedGlobal / parsedRenderPatch
 * from userText + targetedShotOrders + quickActions; the orchestrator's
 * IterationPlanner then routes by scope.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.schemas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Caption rendering policy. The default lets the director-chosen captions render
 *  as before; NONE strips them at render time without touching the timeline. */
@Serializable
enum class CaptionPolicy {
  @SerialName("default") DEFAULT,
  @SerialName("none") NONE,
  @SerialName("zh_only") ZH_ONLY,
  @SerialName("bilingual") BILINGUAL,
}

/** Where the user's feedback applies in the pipeline. The cheaper scopes are
 *  preferred — a shot-level patch should not trigger a full GLOBAL re-run. */
@Serializable
enum class FeedbackScope {
  @SerialName("render_only") RENDER_ONLY,   // Re-render only, no agent calls.
  @SerialName("shot_level")  SHOT_LEVEL,    // applyRevisions on the current Timeline.
  @SerialName("global")      GLOBAL,        // Patch DirectorBrief + rebuild Timeline.
  @SerialName("mixed")       MIXED,         // GLOBAL then SHOT_LEVEL on top.
}

/** Pre-baked one-tap feedback. The client maps each chip to a deterministic
 *  patch (RenderOnlyPatch / GlobalRevision / RevisedRequest) so the user can
 *  iterate even if the LLM-based parser is unavailable. */
@Serializable
enum class QuickAction {
  @SerialName("faster_overall")     FASTER_OVERALL,
  @SerialName("slower_overall")     SLOWER_OVERALL,
  @SerialName("remove_captions")    REMOVE_CAPTIONS,
  @SerialName("change_color_grade") CHANGE_COLOR_GRADE,
}

/** Patch the timeline ONLY at render-time. Does not touch shot ordering, asset
 *  picks, or duration — anything reachable just from the existing Timeline +
 *  these overrides. Cheapest scope (~30s end-to-end). */
@Serializable
data class RenderOnlyPatch(
  val newColorGrade: ColorGrade? = null,
  val captionPolicy: CaptionPolicy? = null,
  /** Re-pick a BGM by tone keyword. Null = keep the previous tone. */
  val newBgmTone: String? = null,
)

/** Whole-timeline structural changes. Triggers a Director patch + Editor rebuild. */
@Serializable
data class GlobalRevision(
  val newTargetDurationSec: Float? = null,
  val newPace: Pace? = null,
  val newTone: String? = null,
  val newColorGrade: ColorGrade? = null,
  val captionPolicy: CaptionPolicy? = null,
)

/** A single iteration's full record. Persisted as feedback_parsed.json. */
@Serializable
data class IterationFeedback(
  val iterationId: String,                          // "iter_002"
  val baseTimelineVersion: Int,                     // The version this feedback is built on.
  val userText: String = "",
  /** Storyboard cells the user explicitly tapped in the IterationSheet. Empty
   *  list means "no specific shot targeted" — feedback applies globally. */
  val targetedShotOrders: List<Int> = emptyList(),
  val quickActions: List<QuickAction> = emptyList(),
  val parsedScope: FeedbackScope = FeedbackScope.RENDER_ONLY,
  /** Reuses the same RevisedRequest schema the Critic already emits — once
   *  parsed, the orchestrator can hand these directly to applyRevisions. */
  val parsedRevisions: List<RevisedRequest> = emptyList(),
  val parsedGlobal: GlobalRevision? = null,
  val parsedRenderPatch: RenderOnlyPatch? = null,
  val createdAtMs: Long = 0L,
)

/** One row in IterationHistory. The mp4Path may be the active candidate file or
 *  an archived one (candidates/archive/<eventId>__<timestamp>.mp4). */
@Serializable
data class IterationSummary(
  val version: Int,
  val mp4Path: String,
  val createdAtMs: Long,
  /** The user's original text for this iteration (empty for v1 — v1 is the
   *  initial generation, not a feedback step). */
  val feedbackText: String = "",
  /** Human-readable diff like "缩短 #3 / 整片调暖". Used in the version chip
   *  tooltip and 上一版 arrow's hint. */
  val changeSummary: String = "",
)

/** All iterations of a single event. Persisted as decisions/<eventId>/_history.json. */
@Serializable
data class IterationHistory(
  val eventId: String,
  val iterations: List<IterationSummary> = emptyList(),
  val currentVersion: Int = 1,
)
