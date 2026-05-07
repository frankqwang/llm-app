/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Smoke tests for JsonExtractor. The function is hit on EVERY agent reply so
 * regressions break the whole pipeline silently. These tests cover the four
 * shapes Gemma 4 actually emits in practice: (1) clean JSON, (2) markdown
 * code fence, (3) <think>...</think> wrapper, (4) prose prefix/suffix.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JsonExtractorTest {

  @Test fun cleanObject() {
    val out = JsonExtractor.firstObject("""{"a":1,"b":[2,3]}""")
    assertEquals("""{"a":1,"b":[2,3]}""", out)
  }

  @Test fun nestedBraces() {
    val raw = """{"outer": {"inner": "x"}, "list": [{"k": "v"}]}"""
    assertEquals(raw, JsonExtractor.firstObject(raw))
  }

  @Test fun stripsCodeFence() {
    val raw = """
      ```json
      {"a": 1}
      ```
    """.trimIndent()
    val out = JsonExtractor.firstObject(raw)
    assertEquals("""{"a": 1}""", out)
  }

  @Test fun stripsThinkingTag() {
    val raw = "<think>let me see…</think>{\"a\":1}"
    assertEquals("""{"a":1}""", JsonExtractor.firstObject(raw))
  }

  @Test fun ignoresProsePrefixAndSuffix() {
    val raw = "Sure, here's the JSON: {\"a\":1} — let me know if you need anything else."
    assertEquals("""{"a":1}""", JsonExtractor.firstObject(raw))
  }

  @Test fun handlesBracesInsideStrings() {
    val raw = """{"caption": "hello {world}", "n": 1}"""
    assertEquals(raw, JsonExtractor.firstObject(raw))
  }

  @Test fun returnsNullOnGarbage() {
    assertNull(JsonExtractor.firstObject(""))
    assertNull(JsonExtractor.firstObject("no braces here"))
  }

  @Test fun returnsNullOnUnclosedObject() {
    // Unbalanced braces: extractor should not return a partial substring.
    assertNull(JsonExtractor.firstObject("{\"a\":1"))
  }

  @Test fun firstArray() {
    val raw = "Here is [1,2,3] and more"
    assertEquals("[1,2,3]", JsonExtractor.firstArray(raw))
  }

  @Test fun ignoreEscapedQuoteInString() {
    val raw = """{"text": "He said \"hi\"", "ok": true}"""
    assertNotNull(JsonExtractor.firstObject(raw))
  }
}
