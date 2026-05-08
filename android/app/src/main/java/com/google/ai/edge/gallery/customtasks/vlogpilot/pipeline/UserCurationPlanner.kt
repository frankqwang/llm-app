/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Pure function: turn a UserCurationRequest into a synthetic Event so the
 * pipeline can consume it without branching. Skips EventSegmenter (we already
 * know which assets), EventSelector / Scout (already chosen by the user).
 *
 * Asset ordering: sorted by takenEpochMs ascending so the resulting Event's
 * timeline aligns with chronological storytelling. Director's pNorm-based
 * shot positioning then Just Works.
 *
 * Asset filtering: requested assetIds that resolved to a real Asset stay;
 * unresolved ids are dropped silently. UI is responsible for the upstream
 * sanity check (selected >= 3); if the runtime universe shrinks (asset
 * deleted between submission and processing), we still try with what's left.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.pipeline

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Asset
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Event
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.UserBrief
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.UserCurationRequest
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.CurationRequestStore

object UserCurationPlanner {

  /**
   * Builds a single synthetic Event for the user-curated story. Returns null
   * when the request can't be honored (e.g. all selected assets have gone
   * missing from MediaStore, or fewer than 3 are usable for a vlog).
   *
   * @param availableAssets the full list of assets reachable from MediaStore
   *                        (PhotoIngest.loadRecent output), used to resolve
   *                        the user's asset ids.
   * @param userBrief       optional pre-parsed brief (may be null when first
   *                        building the event before IntentParserAgent runs).
   */
  fun plan(
    request: UserCurationRequest,
    availableAssets: List<Asset>,
    userBrief: UserBrief? = null,
  ): Event? {
    val byId = availableAssets.associateBy { it.id }
    // Preserve the user's selection order intent (chronologically) but only keep
    // those that still exist. Sorted by takenEpochMs so the synthetic event spans
    // a coherent time window for downstream pNorm slot calculations.
    val resolved = request.selectedAssetIds
      .mapNotNull { byId[it] }
      .sortedBy { it.takenEpochMs }
    if (resolved.size < 3) return null

    val eventId = CurationRequestStore.eventIdFor(request.requestId)
    return Event(
      eventId = eventId,
      assetIds = resolved.map { it.id },
      startEpochMs = resolved.first().takenEpochMs,
      endEpochMs = resolved.last().takenEpochMs,
      placeHint = "",
      userCuration = request,
      userBrief = userBrief,
    )
  }
}
