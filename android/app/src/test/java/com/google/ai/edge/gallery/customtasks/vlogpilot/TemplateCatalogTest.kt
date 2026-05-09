/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Smoke tests for TemplateCatalog keyword routing. Two long-form templates
 * (travel_long, event_recap) were added on top of the original 5; these
 * tests ensure the keyword sets (a) actually win for their target events,
 * and (b) don't poach generic events that should stay on the shorter
 * 5-slot templates.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateCatalogTest {

  @Test fun `travel_long wins on multi-day or road-trip phrasing`() {
    assertEquals("travel_long", TemplateCatalog.selectFromText("十天的多日自驾旅行").id)
    assertEquals("travel_long", TemplateCatalog.selectFromText("沿途风景 行程很长").id)
    assertEquals("travel_long", TemplateCatalog.selectFromText("a long journey across multiple cities").id)
  }

  @Test fun `travel_memory still wins on a casual single trip`() {
    assertEquals("travel_memory", TemplateCatalog.selectFromText("周末去酒店泡温泉").id)
    assertEquals("travel_memory", TemplateCatalog.selectFromText("a beach trip with friends").id)
  }

  @Test fun `event_recap wins on weddings birthdays parties`() {
    assertEquals("event_recap", TemplateCatalog.selectFromText("好朋友的婚礼派对").id)
    assertEquals("event_recap", TemplateCatalog.selectFromText("生日庆祝聚会").id)
    assertEquals("event_recap", TemplateCatalog.selectFromText("anniversary celebration with family").id)
  }

  @Test fun `people_together stays for plain family or friends gatherings`() {
    assertEquals("people_together", TemplateCatalog.selectFromText("家人在一起的合照").id)
    assertEquals("people_together", TemplateCatalog.selectFromText("kid playing with friend").id)
  }

  @Test fun `defaults to daily_moment when no keyword fires`() {
    assertEquals("daily_moment", TemplateCatalog.selectFromText("一些零碎的画面").id)
    assertEquals("daily_moment", TemplateCatalog.selectFromText("").id)
  }

  @Test fun `slot count matches advertised structure`() {
    assertEquals(8, TemplateCatalog.byId("travel_long").slots.size)
    assertEquals(9, TemplateCatalog.byId("event_recap").slots.size)
  }
}
