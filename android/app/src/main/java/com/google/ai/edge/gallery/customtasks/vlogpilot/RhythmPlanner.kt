/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Deterministic rhythm layer for the hybrid recall/editor product. The
 * Director still writes visual intent, but slot order and durations come from
 * a product template so generated videos feel predictable and editable.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.DirectorBrief
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Pace
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRequest
import kotlin.math.roundToInt

internal object RhythmPlanner {

  fun enforce(
    plan: DirectorBrief,
    template: VlogTemplate,
    pace: Pace,
  ): DirectorBrief {
    val targetDuration = targetDuration(plan.targetDurationSec, template.targetDurationSec, pace)
    val templateTotal = template.targetDurationSec.coerceAtLeast(1f)
    val scale = targetDuration / templateTotal

    val byPosition = plan.shotBlueprint.associateBy { it.position }
    val byRole = plan.shotBlueprint
      .groupBy { it.role }
      .mapValues { (_, requests) -> requests.toMutableList() }

    val slots = template.slots.map { slot ->
      val raw = byPosition[slot.position] ?: byRole[slot.role]?.takeIf { it.isNotEmpty() }?.removeAt(0)
      val duration = (slot.durationSec * scale).roundToTenths().coerceIn(0.8f, 5.2f)
      ShotRequest(
        position = slot.position,
        role = slot.role,
        moodTarget = raw?.moodTarget?.takeIf { it.isNotBlank() } ?: slot.moodTarget,
        visualRequirements = mergeVisualRequirements(slot.visualRequirements, raw?.visualRequirements),
        durationSec = duration,
        personConstraint = raw?.personConstraint?.takeIf { it.isNotBlank() },
        captionText = raw?.captionText?.takeIf { it.isNotBlank() } ?: slot.captionHint,
        kenBurnsHint = raw?.kenBurnsHint?.takeIf { it.isNotBlank() } ?: slot.kenBurnsHint,
        transitionInHint = raw?.transitionInHint?.takeIf { it.isNotBlank() } ?: slot.transitionInHint,
        speedHint = raw?.speedHint ?: 1.0f,
        kenBurnsIntensity = raw?.kenBurnsIntensity ?: 1.08f,
        cutReason = raw?.cutReason.orEmpty(),
      )
    }

    return plan.copy(
      targetDurationSec = targetDuration,
      shotBlueprint = slots,
    )
  }

  private fun targetDuration(raw: Float, templateTarget: Float, pace: Pace): Float {
    val preferred = raw.takeIf { it > 0f } ?: templateTarget
    return when (pace) {
      Pace.SNAPPY -> preferred.coerceIn(11.5f, 18f)
      Pace.BALANCED -> preferred.coerceIn(14f, 22f)
      Pace.LINGERING -> preferred.coerceIn(18f, 28f)
    }.roundToTenths()
  }

  private fun mergeVisualRequirements(slotVisual: String, rawVisual: String?): String {
    val raw = rawVisual?.takeIf { it.isNotBlank() } ?: return slotVisual
    if (raw.contains(slotVisual) || slotVisual.contains(raw)) return raw
    return "$slotVisual；$raw"
  }

  private fun Float.roundToTenths(): Float =
    (this * 10f).roundToInt() / 10f
}
