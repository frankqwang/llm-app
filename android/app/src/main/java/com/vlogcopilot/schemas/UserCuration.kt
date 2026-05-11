/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * User-curated story creation: the user picks the assets and writes (or
 * doesn't write) a one-line intent. We turn this into a synthetic Event so
 * the rest of the pipeline (perception, VLM, agents, render) doesn't need to
 * branch — same code path as auto-discovered events, just with a different
 * eventId prefix and an attached UserBrief.
 *
 * Empty intent text is allowed: the UI runs an "EmptyIntentDialog" guidance
 * step, but ultimately a blank `intentText` is fine. In that case UserBrief
 * is mostly null/empty and the agents fall back to their normal behavior.
 *
 * Note: CaptionPolicy enum lives in IterationFeedback.kt — it's shared with
 * the iteration feedback path.
 */
package com.vlogcopilot.schemas

import kotlinx.serialization.Serializable

/** What the user submitted in the CuratorScreen — the raw input. */
@Serializable
data class UserCurationRequest(
  /** sha1(sorted(selectedAssetIds) + intentText)[0..12] — stable hash so
   *  re-submitting the same request hits the cached output. */
  val requestId: String,
  val selectedAssetIds: List<String>,
  val intentText: String = "",
  val createdAtMs: Long = 0L,
)

/** IntentParserAgent.parseInitial output. Every field can be null/empty when
 *  the user didn't say anything specific — the agents will fill in defaults
 *  in those cases. `rawText` is the user's untouched original text, kept as
 *  a tail-of-prompt hint for downstream agents. */
@Serializable
data class UserBrief(
  val parsedHook: String = "",
  val parsedPayoff: String = "",
  val parsedTone: String = "",
  val parsedPace: Pace? = null,
  val parsedDurationSec: Float? = null,
  /** Subject keywords ("宝宝"/"狗"/"夕阳") the user said must appear. Recall
   *  uses these as a soft boost when scoring candidate assets. */
  val mustHaveSubjects: List<String> = emptyList(),
  val parsedAvoid: List<String> = emptyList(),
  val captionPolicy: CaptionPolicy = CaptionPolicy.DEFAULT,
  val parsedColorGrade: ColorGrade? = null,
  /** Untouched user text — agents append this to their prompts as a
   *  authoritative-but-fuzzy guidance. Empty means user wrote nothing. */
  val rawText: String = "",
)
