package dev.ross.glasses

import org.junit.Assert.*
import org.junit.Test

class MediaConnectionStateTest {
  private var time = 1000L
  private val state = MediaConnectionState<String> { time }

  @Test fun backgroundCannotStartAnApprovalFlow() {
    try { state.begin(false); fail("Background request must wait for the user") }
    catch (_: MediaConnectionNeedsApproval) {}
    assertFalse(state.connecting)
    assertNull(state.held)
  }

  @Test fun anApprovedSessionRemainsUsableAcrossIdlePasses() {
    val generation = state.begin(true)
    assertTrue(state.accept(generation, "same-network-and-token", 180))
    repeat(5) {
      time += 60_000
      assertEquals("same-network-and-token", state.current)
      assertTrue(state.renew(generation, 180))
    }
    assertFalse(state.connecting)
  }

  @Test fun sleepBeyondLeaseCannotSilentlyReviveTheSession() {
    val generation = state.begin(true)
    assertTrue(state.accept(generation, "approved", 180))
    time += 180_000
    assertNull(state.current)
    assertFalse(state.renew(generation, 180))
    assertEquals("approved", state.invalidate()) // Caller still has the resource to close.
    try { state.begin(false); fail("Expired approval must not trigger a background prompt") }
    catch (_: MediaConnectionNeedsApproval) {}
  }

  @Test fun lateNetworkAvailabilityCannotUndoDisconnect() {
    val old = state.begin(true)
    state.invalidate()
    assertFalse(state.accept(old, "late-network", 180))
    assertNull(state.current)
  }

  @Test fun aStaleRenewalCannotExtendAReplacementSession() {
    val old = state.begin(true)
    state.accept(old, "old", 180)
    state.invalidate()
    val replacement = state.begin(true)
    state.accept(replacement, "new", 1)
    assertFalse(state.renew(old, 180))
    time += 1000
    assertNull(state.current)
  }

  @Test fun closeRejectsLateCallbacksAndFutureAttempts() {
    val generation = state.begin(true)
    state.close()
    assertFalse(state.accept(generation, "late", 180))
    try { state.begin(true); fail("Closed service cannot reopen Wi-Fi") }
    catch (_: IllegalStateException) {}
  }

  @Test fun anUnboundedServerLeaseIsRejected() {
    val generation = state.begin(true)
    try { state.accept(generation, "network", 181); fail("Lease must stay bounded") }
    catch (_: IllegalArgumentException) {}
    assertNull(state.current)
  }
}
