/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Pull the first valid JSON object out of an LLM response. Handles:
 *  - thinking-mode wrappers (<think>…</think>, <reasoning>…</reasoning>)
 *  - markdown ```json … ``` fences
 *  - prefix / suffix prose around the JSON
 *  - trailing commas (lenient parse)
 *
 * Returns the JSON string, or null if nothing parsable was found. Callers feed
 * the result to kotlinx.serialization.Json { ignoreUnknownKeys = true }.
 */
package com.vlogcopilot.agents

object JsonExtractor {

  fun firstObject(raw: String): String? {
    if (raw.isBlank()) return null
    val cleaned = stripThinking(raw).let(::stripCodeFence)
    val start = cleaned.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escape = false
    for (i in start until cleaned.length) {
      val c = cleaned[i]
      if (inString) {
        when {
          escape -> escape = false
          c == '\\' -> escape = true
          c == '"' -> inString = false
        }
      } else {
        when (c) {
          '"' -> inString = true
          '{' -> depth++
          '}' -> {
            depth--
            if (depth == 0) return stripTrailingCommas(cleaned.substring(start, i + 1))
          }
        }
      }
    }
    return null
  }

  fun firstArray(raw: String): String? {
    val cleaned = stripThinking(raw).let(::stripCodeFence)
    val start = cleaned.indexOf('[')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escape = false
    for (i in start until cleaned.length) {
      val c = cleaned[i]
      if (inString) {
        when {
          escape -> escape = false
          c == '\\' -> escape = true
          c == '"' -> inString = false
        }
      } else {
        when (c) {
          '"' -> inString = true
          '[' -> depth++
          ']' -> {
            depth--
            if (depth == 0) return stripTrailingCommas(cleaned.substring(start, i + 1))
          }
        }
      }
    }
    return null
  }

  private fun stripThinking(s: String): String {
    var out = s
    for (tag in listOf("think", "reasoning", "thought")) {
      val regex = Regex("<$tag>.*?</$tag>", RegexOption.DOT_MATCHES_ALL)
      out = regex.replace(out, "")
    }
    return out
  }

  private fun stripCodeFence(s: String): String {
    if (!s.contains("```")) return s
    val lines = s.split("\n")
    // Two-pass strategy: if the response is "prose ```json\n{...}\n``` more prose",
    // we want only the fenced content. If there's no fence, fall through to original.
    val fenced = StringBuilder()
    var inFence = false
    for (line in lines) {
      val t = line.trimStart()
      if (t.startsWith("```")) {
        inFence = !inFence
        continue
      }
      if (inFence) fenced.append(line).append('\n')
    }
    val fencedStr = fenced.toString()
    // Prefer the fenced block when it actually contains JSON-shape; otherwise
    // (e.g. fence wraps something else) drop the fence markers and return the rest.
    return if (fencedStr.contains('{') || fencedStr.contains('[')) {
      fencedStr
    } else {
      s.replace(Regex("```[a-zA-Z]*\\s*"), "").replace("```", "")
    }
  }

  private fun stripTrailingCommas(s: String): String =
    s.replace(Regex(",\\s*([}\\]])"), "$1")
}
