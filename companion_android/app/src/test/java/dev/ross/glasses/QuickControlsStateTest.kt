package dev.ross.glasses

import org.junit.Assert.*
import org.junit.Test

class QuickControlsStateTest {
  @Test fun photoPendingDoesNotBlockVideoOrAudio() {
    val state = QuickControls.State(true, "", false, photoReady = false, videoReady = true)
    assertFalse(state.canStart(QuickControls.PHOTO))
    assertTrue(state.canStart(QuickControls.VIDEO))
    assertTrue(state.canStart(QuickControls.AUDIO))
  }

  @Test fun videoPendingDoesNotBlockPhotoOrAudio() {
    val state = QuickControls.State(true, "", false, photoReady = true, videoReady = false)
    assertTrue(state.canStart(QuickControls.PHOTO))
    assertFalse(state.canStart(QuickControls.VIDEO))
    assertTrue(state.canStart(QuickControls.AUDIO))
  }

  @Test fun stopRemainsAvailableWhenDisconnectedAndDefaultsPending() {
    val state = QuickControls.State(false, "VIDEO", true)
    assertTrue(state.isStopping("VIDEO"))
    assertFalse(state.isStopping("AUDIO"))
    assertFalse(state.isStopping(""))
    assertFalse(state.canStart(QuickControls.PHOTO))
    assertFalse(state.canStart(QuickControls.VIDEO))
    assertFalse(state.canStart(QuickControls.AUDIO))
  }

  @Test fun oldSnapshotCannotClaimPhotoOrVideoReady() {
    val state = QuickControls.State(true, "", false)
    assertFalse(state.canStart(QuickControls.PHOTO))
    assertFalse(state.canStart(QuickControls.VIDEO))
    assertTrue(state.canStart(QuickControls.AUDIO))
    assertFalse(state.canStart("unknown"))
  }
}
