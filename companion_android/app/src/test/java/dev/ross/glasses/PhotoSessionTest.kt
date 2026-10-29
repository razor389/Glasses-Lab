package dev.ross.glasses

import org.junit.Assert.*
import org.junit.Test

class PhotoSessionTest {
  @Test fun disconnectedGlassesCannotCapture() {
    val session = PhotoSession()
    assertThrows(IllegalStateException::class.java) { session.begin("photo-1") }
  }

  @Test fun secondCaptureCannotReplaceAnActiveRequest() {
    val session = PhotoSession().apply { setReady(true); begin("photo-1") }
    assertThrows(IllegalStateException::class.java) { session.begin("photo-2") }
    assertEquals("photo-1", session.activeId)
  }

  @Test fun disconnectRejectsLateUploads() {
    val session = PhotoSession().apply { setReady(true); begin("photo-1") }
    session.setReady(false)
    assertFalse(session.accepts("photo-1"))
    assertNull(session.activeId)
  }

  @Test fun oldCompletionCannotClearNewRequestAfterReconnect() {
    val session = PhotoSession().apply { setReady(true); begin("photo-1") }
    session.setReady(false)
    session.setReady(true)
    session.begin("photo-2")
    assertFalse(session.finish("photo-1"))
    assertTrue(session.accepts("photo-2"))
    assertFalse(session.accepts("photo-1"))
    assertTrue(session.finish("photo-2"))
  }
}
