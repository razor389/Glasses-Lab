package dev.ross.glasses

import org.junit.Assert.*
import org.junit.Test

class MediaPlaybackOwnersTest {
  @Test fun stoppingOneActivityCannotClearAnotherActivityPlayback() {
    val playback = MediaPlaybackOwners()
    val first = Any()
    val second = Any()
    playback.setActive(first, true)
    playback.setActive(second, true)
    playback.setActive(first, false)
    playback.setActive(first, false) // onPause, onStop and release can all clear the same owner.
    assertTrue(playback.isActive)
    playback.setActive(second, false)
    assertFalse(playback.isActive)
  }

  @Test fun repeatedStartsAndEqualButDifferentOwnersCannotLeakOrStealOwnership() {
    data class EqualOwner(val value: Int)
    val playback = MediaPlaybackOwners()
    val owner = EqualOwner(1)
    playback.setActive(owner, true)
    playback.setActive(owner, true)
    playback.setActive(EqualOwner(1), false)
    assertTrue(playback.isActive)
    playback.setActive(owner, false)
    assertFalse(playback.isActive)
  }
}
