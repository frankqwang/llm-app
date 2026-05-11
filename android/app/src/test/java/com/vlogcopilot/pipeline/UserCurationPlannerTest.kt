/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Tests for UserCurationPlanner + CurationRequestStore.computeRequestId.
 * These are pure-logic tests that don't need a real Context, so they live
 * happily in the JVM unit-test source set.
 */
package com.vlogcopilot.pipeline

import com.vlogcopilot.schemas.Asset
import com.vlogcopilot.schemas.MediaType
import com.vlogcopilot.schemas.UserCurationRequest
import com.vlogcopilot.worker.CurationRequestStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserCurationPlannerTest {

  private fun asset(id: String, takenEpochMs: Long, mediaType: MediaType = MediaType.IMAGE): Asset =
    Asset(
      id = id,
      contentUri = "content://media/external/images/media/$id",
      displayName = "$id.jpg",
      mediaType = mediaType,
      takenEpochMs = takenEpochMs,
    )

  // ---- UserCurationPlanner.plan ----

  @Test fun plan_buildsEventFromReachableAssets() {
    val available = listOf(
      asset("a", 1000),
      asset("b", 2000),
      asset("c", 3000),
      asset("d", 4000),
    )
    val request = UserCurationRequest(
      requestId = "abc123",
      selectedAssetIds = listOf("c", "a", "d"),  // out-of-order on purpose
      intentText = "做条短视频",
      createdAtMs = 0L,
    )
    val event = UserCurationPlanner.plan(request, available)
    assertNotNull(event)
    assertEquals("user_abc123", event!!.eventId)
    // Sorted by takenEpochMs ascending so the synthetic event spans a coherent window.
    assertEquals(listOf("a", "c", "d"), event.assetIds)
    assertEquals(1000L, event.startEpochMs)
    assertEquals(4000L, event.endEpochMs)
    assertEquals(request, event.userCuration)
  }

  @Test fun plan_dropsAssetsThatHaveGoneMissing() {
    val available = listOf(
      asset("a", 1000),
      asset("b", 2000),
      asset("c", 3000),
    )
    val request = UserCurationRequest(
      requestId = "missing1",
      selectedAssetIds = listOf("a", "ghost", "b", "c"),
      intentText = "",
    )
    val event = UserCurationPlanner.plan(request, available)
    assertNotNull(event)
    assertEquals(listOf("a", "b", "c"), event!!.assetIds)
  }

  @Test fun plan_returnsNullWhenFewerThanThreeUsable() {
    val available = listOf(asset("a", 1000), asset("b", 2000))
    val request = UserCurationRequest(
      requestId = "tiny",
      selectedAssetIds = listOf("a", "b", "ghost"),
      intentText = "",
    )
    assertNull(UserCurationPlanner.plan(request, available))
  }

  @Test fun plan_returnsNullWhenAllAssetsMissing() {
    val available = listOf(asset("a", 1000))
    val request = UserCurationRequest(
      requestId = "ghost",
      selectedAssetIds = listOf("missing1", "missing2", "missing3"),
      intentText = "",
    )
    assertNull(UserCurationPlanner.plan(request, available))
  }

  // ---- CurationRequestStore.computeRequestId ----

  @Test fun requestId_isDeterministicForSameInput() {
    val a = CurationRequestStore.computeRequestId(listOf("a", "b", "c"), "abc")
    val b = CurationRequestStore.computeRequestId(listOf("a", "b", "c"), "abc")
    assertEquals(a, b)
  }

  @Test fun requestId_isStableUnderListReordering() {
    val a = CurationRequestStore.computeRequestId(listOf("c", "a", "b"), "abc")
    val b = CurationRequestStore.computeRequestId(listOf("a", "b", "c"), "abc")
    assertEquals(a, b)
  }

  @Test fun requestId_differsWhenIntentChanges() {
    val a = CurationRequestStore.computeRequestId(listOf("a", "b"), "30秒怀旧")
    val b = CurationRequestStore.computeRequestId(listOf("a", "b"), "30秒欢快")
    assertNotEquals(a, b)
  }

  @Test fun requestId_differsWhenAssetsChange() {
    val a = CurationRequestStore.computeRequestId(listOf("a", "b"), "x")
    val b = CurationRequestStore.computeRequestId(listOf("a", "c"), "x")
    assertNotEquals(a, b)
  }

  @Test fun requestId_isAlphanumericTwelveChars() {
    val id = CurationRequestStore.computeRequestId(listOf("a", "b"), "x")
    assertEquals(12, id.length)
    assertTrue("requestId should be hex: $id", id.all { it.isDigit() || it in 'a'..'f' })
  }

  @Test fun eventIdFor_prefixesWithUser() {
    assertEquals("user_abc123", CurationRequestStore.eventIdFor("abc123"))
  }
}
