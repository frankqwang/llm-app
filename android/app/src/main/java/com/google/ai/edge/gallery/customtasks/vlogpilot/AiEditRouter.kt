/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Product-level routing for AI-assisted edits. It mirrors IterationPlanner's
 * low-level scopes but gives UI and tests a stable entity to reason about.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.QuickAction

internal object AiEditRouter {

  fun fromQuickActions(
    projectId: String,
    baseVersion: Int,
    quickActions: List<QuickAction>,
    targetShotOrders: List<Int> = emptyList(),
    text: String = "",
  ): AiEditRequest {
    val scope = route(
      quickActions = quickActions,
      targetShotOrders = targetShotOrders,
      text = text,
    )
    return AiEditRequest(
      projectId = projectId,
      baseVersion = baseVersion,
      scope = scope,
      targetShotOrders = targetShotOrders.distinct().sorted(),
      text = text,
      quickActions = quickActions.distinct(),
    )
  }

  fun route(
    quickActions: List<QuickAction>,
    targetShotOrders: List<Int> = emptyList(),
    text: String = "",
  ): AiEditScope {
    val normalizedText = text.lowercase()
    if (listOf("重新理解", "换故事", "主题不对", "story", "重新选故事").any { it in normalizedText }) {
      return AiEditScope.STORY_LEVEL
    }
    if (targetShotOrders.isNotEmpty()) {
      return AiEditScope.SHOT_LEVEL
    }
    if (listOf("换模板", "节奏", "更快", "更慢", "template", "pacing").any { it in normalizedText }) {
      return AiEditScope.TEMPLATE_LEVEL
    }
    if (quickActions.any { it == QuickAction.FASTER_OVERALL || it == QuickAction.SLOWER_OVERALL }) {
      return AiEditScope.TEMPLATE_LEVEL
    }
    if (quickActions.any { it == QuickAction.REMOVE_CAPTIONS || it == QuickAction.CHANGE_COLOR_GRADE }) {
      return AiEditScope.RENDER_ONLY
    }
    if (normalizedText.isNotBlank()) {
      return AiEditScope.SHOT_LEVEL
    }
    return AiEditScope.RENDER_ONLY
  }
}
