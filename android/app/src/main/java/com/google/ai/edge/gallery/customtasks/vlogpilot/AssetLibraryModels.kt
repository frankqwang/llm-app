/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Domain-facing index for album assets. UI pages should consume this relation
 * map instead of re-deriving story/video usage during composition.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionManifest
import com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionStatus

internal data class AssetUsage(
  val annotated: Boolean = false,
  val selectedStoryIds: Set<String> = emptySet(),
  val storyIds: Set<String> = emptySet(),
  val shotOrdersByVideo: Map<String, List<Int>> = emptyMap(),
  val finishedVideoIds: Set<String> = emptySet(),
  val perception: Perception? = null,
)

internal fun buildAssetUsageIndex(
  decisions: List<EventDecisions>,
  manifest: EventSelectionManifest?,
  cachedPerceptions: Map<String, Perception> = emptyMap(),
): Map<String, AssetUsage> {
  val usage = mutableMapOf<String, AssetUsage>()
  fun update(assetId: String, block: (AssetUsage) -> AssetUsage) {
    usage[assetId] = block(usage[assetId] ?: AssetUsage())
  }

  manifest?.candidates.orEmpty().forEach { candidate ->
    val selected = candidate.status == EventSelectionStatus.SELECTED || candidate.status == EventSelectionStatus.COMPLETED
    candidate.assets.forEach { asset ->
      update(asset.id) { current ->
        current.copy(
          storyIds = current.storyIds + candidate.eventId,
          selectedStoryIds = if (selected) current.selectedStoryIds + candidate.eventId else current.selectedStoryIds,
        )
      }
    }
  }

  decisions.forEach { decision ->
    decision.inputAssets.forEach { asset ->
      val perception = decision.inputPerceptions[asset.id]
      update(asset.id) { current ->
        current.copy(
          annotated = current.annotated || perception?.let(::perceptionIsAnnotated) == true,
          storyIds = current.storyIds + decision.eventId,
          perception = choosePerception(current.perception, perception),
        )
      }
    }
    val timeline = decision.timelineFinal ?: decision.timelineV1
    timeline?.shots.orEmpty().groupBy { it.assetId }.forEach { (assetId, shots) ->
      update(assetId) { current ->
        current.copy(
          shotOrdersByVideo = current.shotOrdersByVideo + (decision.eventId to shots.map { it.order }.sorted()),
          finishedVideoIds = if (decision.mp4Path != null) current.finishedVideoIds + decision.eventId else current.finishedVideoIds,
        )
      }
    }
  }
  cachedPerceptions.forEach { (assetId, perception) ->
    update(assetId) { current ->
      current.copy(
        annotated = current.annotated || perceptionIsAnnotated(perception),
        perception = choosePerception(current.perception, perception),
      )
    }
  }
  return usage
}

private fun choosePerception(existing: Perception?, incoming: Perception?): Perception? =
  when {
    incoming == null -> existing
    existing == null -> incoming
    !perceptionIsAnnotated(existing) && perceptionIsAnnotated(incoming) -> incoming
    else -> existing
  }

private fun perceptionIsAnnotated(perception: Perception): Boolean =
  perception.vlmTags.scene.isNotBlank() ||
    perception.vlmTags.subjects.isNotEmpty() ||
    perception.vlmTags.action.isNotBlank() ||
    perception.vlmTags.mood.isNotBlank() ||
    perception.vlmTags.salient.isNotBlank() ||
    perception.vlmTags.visualDescription.isNotBlank() ||
    perception.vlmTags.composition.isNotBlank() ||
    perception.vlmTags.lighting.isNotBlank() ||
    perception.vlmTags.motionHint.isNotBlank() ||
    perception.videoInsight.summary.isNotBlank() ||
    perception.videoInsight.visualDescription.isNotBlank() ||
    perception.videoInsight.actionArc.isNotBlank()
