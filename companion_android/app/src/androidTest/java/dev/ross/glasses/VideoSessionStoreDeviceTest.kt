package dev.ross.glasses

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VideoSessionStoreDeviceTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val name = "video-session-test-${UUID.randomUUID()}.sqlite"
  private var store = VideoSessionStore(context, name)
  @After fun cleanup() { store.close(); context.deleteDatabase(name) }

  private fun remote(index: Int) = RemoteMedia("VID_session_$index/base.mp4", "live", 100,
    "g$index", 1, "video/mp4", "video-session", "VID_session_$index", VideoSegmentMetadata("video-session", index))
  private fun publication(file: RemoteMedia) = PublishedMedia(
    File(File(context.filesDir, "recordings"), file.localName).canonicalPath,
    Uri.parse("content://media/external/video/media/${file.videoSegment!!.index + 1}"),
    100, "a".repeat(64), MediaKind.VIDEO, "part.mp4", 123,
  )

  @Test fun receiptReplayAndReopenPreserveOrderedMembership() {
    store.begin("video-session", 100)
    listOf(2, 0, 1, 1).forEach { index -> val file = remote(index); store.register(file, publication(file)) }
    store.finish("video-session", "Length limit reached")
    store.close()
    store = VideoSessionStore(context, name)
    assertEquals(listOf(0, 1, 2), store.segments("video-session").map { it.index })
    assertEquals(3, store.segmentCount("video-session"))
    assertTrue(store.hasAllSegments("video-session", 3))
    assertFalse(store.hasAllSegments("video-session", 2))
    assertEquals(300, store.list().single().bytes)
    assertEquals("FINISHED", store.list().single().state)
    assertEquals("Length limit reached", store.list().single().reason)
    assertTrue(store.containsSource(publication(remote(0)).file))
  }

  @Test fun conflictingPartCannotReplaceSavedMembership() {
    val file = remote(0)
    store.register(file, publication(file))
    assertThrows(IllegalStateException::class.java) {
      store.register(file.copy(generation = "replacement"), publication(file.copy(generation = "replacement")))
    }
    assertEquals(publication(file).sourcePath, store.segments("video-session").single().sourcePath)
  }

  @Test fun restartNeverRestartsSessionsAndCanPreserveOneReconciledRequest() {
    store.begin("video-session")
    store.begin("other-session")
    store.recover("video-session")
    assertEquals("RECORDING", store.list().first { it.id == "video-session" }.state)
    assertEquals("INTERRUPTED", store.list().first { it.id == "other-session" }.state)
    store.recover()
    assertTrue(store.list().all { it.state == "INTERRUPTED" })
  }

  @Test fun sparsePartsNeverCountAsACompleteRecording() {
    assertTrue(store.hasAllSegments("video-session", 0))
    listOf(0, 2).forEach { index -> val file = remote(index); store.register(file, publication(file)) }
    assertFalse(store.hasAllSegments("video-session", 2))
    assertFalse(store.hasAllSegments("video-session", 3))
    val middle = remote(1)
    store.register(middle, publication(middle))
    assertTrue(store.hasAllSegments("video-session", 3))
  }
}
