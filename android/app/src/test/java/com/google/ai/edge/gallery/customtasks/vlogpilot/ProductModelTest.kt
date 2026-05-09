/*
 * Copyright 2026 The pc-pilot v3 authors
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.DirectorBrief
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.EventMemory
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.FaceBox
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Pace
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.Perception
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.QuickAction
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRequest
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.ShotRole
import com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.VlmTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductModelTest {

  @Test fun templateCatalogSelectsFoodTemplateFromMemory() {
    val template = TemplateCatalog.selectForMemory(
      EventMemory(
        eventId = "evt-food",
        storylineSummary = "朋友在餐厅一起吃火锅，美食和笑声很多",
        emotionalArc = "热闹",
      ),
    )

    assertEquals("food_record", template.id)
    assertEquals(5, template.slots.size)
    assertTrue(template.targetDurationSec in 10f..18f)
  }

  @Test fun rhythmPlannerUsesTemplateSlotsAndKeepsDirectorIntent() {
    val template = TemplateCatalog.byId("daily_moment")
    val brief = DirectorBrief(
      eventId = "evt",
      title = "城市里的小瞬间",
      targetDurationSec = 20f,
      tone = "warm",
      narrativeArc = listOf("开场", "行动", "收尾"),
      shotBlueprint = listOf(
        ShotRequest(
          position = 2,
          role = ShotRole.PORTRAIT,
          moodTarget = "靠近主角",
          visualRequirements = "穿白衬衫的人在画面右侧",
          durationSec = 6f,
          captionText = "刚好遇见",
        ),
      ),
    )

    val planned = RhythmPlanner.enforce(brief, template, Pace.BALANCED)

    assertEquals(template.slots.map { it.position }, planned.shotBlueprint.map { it.position })
    assertEquals(template.slots.map { it.role }, planned.shotBlueprint.map { it.role })
    assertTrue(planned.targetDurationSec in 14f..22f)
    val portrait = planned.shotBlueprint.first { it.position == 2 }
    assertEquals("靠近主角", portrait.moodTarget)
    assertTrue(portrait.visualRequirements.contains("穿白衬衫"))
    assertEquals("刚好遇见", portrait.captionText)
  }

  @Test fun aiEditRouterPrefersCheapestSufficientScope() {
    assertEquals(
      AiEditScope.RENDER_ONLY,
      AiEditRouter.route(listOf(QuickAction.REMOVE_CAPTIONS)),
    )
    assertEquals(
      AiEditScope.TEMPLATE_LEVEL,
      AiEditRouter.route(listOf(QuickAction.FASTER_OVERALL)),
    )
    assertEquals(
      AiEditScope.SHOT_LEVEL,
      AiEditRouter.route(listOf(QuickAction.REMOVE_CAPTIONS), targetShotOrders = listOf(3)),
    )
    assertEquals(
      AiEditScope.STORY_LEVEL,
      AiEditRouter.route(emptyList(), text = "主题不对，重新理解这个故事"),
    )
  }

  @Test fun characterPlannerFindsEventLevelSubjects() {
    val plan = CharacterPlanner.build(
      memory = EventMemory(
        eventId = "evt",
        storylineSummary = "一家人吃饭",
        charactersObserved = listOf("妈妈", "孩子"),
      ),
      perceptions = mapOf(
        "a" to Perception(
          assetId = "a",
          faces = listOf(FaceBox(0.1f, 0.1f, 0.2f, 0.2f)),
          vlmTags = VlmTags(subjects = listOf("妈妈", "孩子")),
        ),
        "b" to Perception(
          assetId = "b",
          faces = listOf(FaceBox(0.2f, 0.1f, 0.2f, 0.2f)),
          vlmTags = VlmTags(subjects = listOf("孩子")),
        ),
      ),
    )

    assertTrue("subjects=${plan.primarySubjects}", "孩子" in plan.primarySubjects)
    assertTrue("subjects=${plan.primarySubjects}", "妈妈" in plan.primarySubjects)
  }
}
