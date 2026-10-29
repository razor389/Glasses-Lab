package dev.ross.glasses

import org.junit.Assert.*
import org.junit.Test

class RemoteMediaCatalogTest {
  private fun response(name: String = "VID_123/VID_123.mp4", size: Long = 4_000_000_000,
    location: String = "live", more: Boolean = false, cursor: String = "null") = """
    {"status":"success","data":{"api_version":1,"files":[
      {"name":"$name","location":"$location","size":$size,"generation":"g1",
       "modified":123,"mime_type":"video/mp4","role":"primary","capture_id":"VID_123"},
      {"name":"VID_123/imu.json","location":"live","size":20,"generation":"g2","mime_type":"application/json","role":"sidecar"}
    ],"has_more":$more,"next_cursor":$cursor}}
  """.trimIndent()

  @Test fun acceptsLongMediaWithoutOldOneGibLimitAndSkipsSidecars() {
    val files = RemoteMediaCatalog.page(response()).files
    assertEquals(1, files.size)
    assertEquals(4_000_000_000, files.single().bytes)
  }

  @Test fun completedVideoPartsCarryStableSessionMembershipAndCapability() {
    val body = response().replace("\"capture_id\":\"VID_123\"", "\"capture_id\":\"VID_123\",\"session_id\":\"video-123-abcdefgh\",\"segment_index\":2")
      .replace("\"api_version\":1", "\"api_version\":1,\"segmented_recording_version\":1")
    val page = RemoteMediaCatalog.page(body)
    assertEquals(1, page.segmentedRecordingVersion)
    assertEquals(VideoSegmentMetadata("video-123-abcdefgh", 2), page.files.single().videoSegment)
    assertEquals(0, RemoteMediaCatalog.page(response()).segmentedRecordingVersion)
    assertNull(RemoteMediaCatalog.page(response()).files.single().videoSegment)
  }

  @Test fun malformedPartMembershipIsNeverSilentlyDiscarded() {
    listOf(
      "\"session_id\":\"session\"",
      "\"segment_index\":0",
      "\"session_id\":\"\",\"segment_index\":0",
      "\"session_id\":\"session\",\"segment_index\":-1",
      "\"session_id\":\"session\",\"segment_index\":1.5",
      "\"session_id\":\"session\",\"segment_index\":\"0\"",
      "\"session_id\":42,\"segment_index\":0",
    ).forEach { fields ->
      assertThrows(IllegalArgumentException::class.java) {
        RemoteMediaCatalog.page(response().replace("\"capture_id\":\"VID_123\"", "\"capture_id\":\"VID_123\",$fields"))
      }
    }
  }

  @Test fun sessionIdentityIsOpaqueAndBoundedByUtf8Bytes() {
    val identity = "session/雪_with-dashes"
    val body = response().replace("\"capture_id\":\"VID_123\"",
      "\"capture_id\":\"VID_123\",\"session_id\":\"$identity\",\"segment_index\":0")
    assertEquals(identity, RemoteMediaCatalog.page(body).files.single().videoSegment!!.sessionId)
    assertThrows(IllegalArgumentException::class.java) { VideoSegmentMetadata("雪".repeat(43), 0) }
  }
  @Test fun localIdentitySurvivesMoveToTrashButChangesWithGeneration() {
    val live = RemoteMediaCatalog.page(response()).files.single()
    val trash = RemoteMediaCatalog.page(response(location = "trash")).files.single()
    assertEquals(live.localName, trash.localName)
    assertNotEquals(live.localName, live.copy(generation = "g2").localName)
    assertTrue(live.localName.matches(Regex("glasses-[0-9a-f]{64}\\.mp4")))
  }
  @Test fun rejectsTraversalUnknownLocationAndInvalidLength() {
    listOf(response(name = "VID_123/../secret.mp4"), response(location = "system"), response(size = -1)).forEach {
      try { RemoteMediaCatalog.page(it); fail("Invalid remote identity accepted") } catch (_: IllegalArgumentException) {}
    }
  }
  @Test fun requiresCursorForAnotherPage() {
    try { RemoteMediaCatalog.page(response(more = true)); fail("Missing cursor accepted") } catch (_: IllegalStateException) {}
    assertEquals("live:VID_123", RemoteMediaCatalog.page(response(more = true, cursor = "\"live:VID_123\"")).cursor)
  }
  @Test fun rejectsUnconfirmedProtocolResponse() {
    try { RemoteMediaCatalog.page(response().replace("\"success\"", "\"error\"")); fail("Error accepted") } catch (_: IllegalStateException) {}
  }

  @Test fun repeatedGlobalAuxiliaryTotalsAreNotMultipliedByPageCountOrFilteredEntries() {
    val body = response().replace("\"has_more\":", "\"retained_auxiliary_files\":7,\"retained_auxiliary_bytes\":5000000000,\"has_more\":")
    val page = RemoteMediaCatalog.page(body)
    assertTrue(page.auxiliaryTotalsReported)
    assertEquals(7, page.retainedAuxiliaries)
    assertEquals(5_000_000_000L, page.retainedAuxiliaryBytes)
    val total = AuxiliaryMediaTotals().include(page).include(page).include(page)
    assertEquals(7, total.files)
    assertEquals(5_000_000_000L, total.bytes)
  }

  @Test fun olderManifestFallsBackToOnlyItsFilteredPageEntries() {
    val page = RemoteMediaCatalog.page(response())
    assertFalse(page.auxiliaryTotalsReported)
    val total = AuxiliaryMediaTotals().include(page).include(page)
    assertEquals(2, total.files)
    assertEquals(40L, total.bytes)
  }

  @Test fun globalTotalsIncludeThumbnailsWithoutTreatingThemAsCapturedPhotos() {
    val body = response().replace("VID_123/imu.json", "VID_123/thumb.jpg")
      .replace("application/json", "image/jpeg").replace("\"sidecar\"", "\"thumbnail\"")
      .replace("\"has_more\":", "\"retained_auxiliary_files\":1,\"retained_auxiliary_bytes\":20,\"has_more\":")
    val page = RemoteMediaCatalog.page(body)
    assertEquals(1, page.files.size)
    assertFalse(page.files.single().photo)
    assertEquals(1, AuxiliaryMediaTotals().include(page).include(page).files)
    assertEquals(20L, AuxiliaryMediaTotals().include(page).bytes)
  }

  @Test fun rejectsNegativeAndIncompleteAuxiliaryTotals() {
    listOf(
      "\"retained_auxiliary_files\":-1,\"retained_auxiliary_bytes\":20,",
      "\"retained_auxiliary_files\":1,\"retained_auxiliary_bytes\":-20,",
      "\"retained_auxiliary_files\":1,",
    ).forEach { fields ->
      assertThrows(IllegalArgumentException::class.java) {
        RemoteMediaCatalog.page(response().replace("\"has_more\":", fields + "\"has_more\":"))
      }
    }
  }
}
