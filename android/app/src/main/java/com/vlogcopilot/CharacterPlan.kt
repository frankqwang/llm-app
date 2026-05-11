/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Single-event character consistency. This is intentionally not a cross-album
 * identity system; it only turns the current event's memory/perception into a
 * small set of subject keywords that Recall can boost.
 */
package com.vlogcopilot

import com.vlogcopilot.schemas.EventMemory
import com.vlogcopilot.schemas.Perception

internal data class CharacterPlan(
  val primarySubjects: List<String>,
  val description: String,
)

internal object CharacterPlanner {

  fun build(
    memory: EventMemory,
    perceptions: Map<String, Perception>,
  ): CharacterPlan {
    val counts = linkedMapOf<String, Float>()

    memory.charactersObserved
      .flatMap(::splitSubjectText)
      .forEach { counts[it] = (counts[it] ?: 0f) + 1.2f }

    perceptions.values.forEach { perception ->
      val faceWeight = when {
        perception.faces.size >= 2 -> 0.7f
        perception.faces.size == 1 -> 0.5f
        else -> 0.2f
      }
      perception.vlmTags.subjects
        .flatMap(::splitSubjectText)
        .forEach { counts[it] = (counts[it] ?: 0f) + faceWeight }
    }

    val subjects = counts.entries
      .filter { it.key.length >= 2 || it.key.any { ch -> ch.code > 127 } }
      .sortedByDescending { it.value }
      .map { it.key }
      .distinct()
      .take(3)

    return CharacterPlan(
      primarySubjects = subjects,
      description = subjects.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "无稳定人物线索",
    )
  }

  private fun splitSubjectText(text: String): List<String> =
    text
      .split(Regex("[,，、/;；|\\s]+"))
      .map { it.trim().trim('「', '」', '“', '”', '"', '\'') }
      .filter { it.isNotBlank() && it.length <= 16 && !it.isGenericSubject() }

  private fun String.isGenericSubject(): Boolean {
    val normalized = lowercase()
    return normalized in setOf(
      "人",
      "人物",
      "路人",
      "朋友们",
      "people",
      "person",
      "subject",
      "someone",
    )
  }
}
