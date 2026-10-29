package dev.ross.glasses

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RecordingStoragePolicyTest {
  private val gib = RecordingStoragePolicy.GIB
  @Test fun unlimitedBudgetStillPreservesFreeSpace() {
    assertEquals(8 * gib, RecordingStoragePolicy().remaining(9000 * gib, 9 * gib))
  }
  @Test fun tighterOfBudgetAndActualFreeSpaceWins() {
    val policy = RecordingStoragePolicy(10 * gib)
    assertEquals(gib, policy.remaining(9 * gib, 100 * gib))
    assertEquals(gib, policy.remaining(gib, 2 * gib))
    assertEquals(0L, policy.remaining(11 * gib, 100 * gib))
  }
  @Test fun writesAndOtherAppsAreAccountedForBetweenScans() {
    var clock = 0L
    var free = 2 * gib
    val guard = RecordingStorageGuard({ RecordingStoragePolicy() }, { 0 }, { free }, { clock })
    guard.requireCapacity(gib)
    try { guard.requireCapacity(1); fail("Expected allowance exhaustion") } catch (_: IOException) {}
    clock = 2000
    free = gib - 1
    try { guard.requireCapacity(1); fail("Expected external disk exhaustion") } catch (_: IOException) {}
  }
  @Test fun settingsChangesApplyDuringRecording() {
    var policy = RecordingStoragePolicy()
    val guard = RecordingStorageGuard({ policy }, { gib }, { 100 * gib })
    guard.requireCapacity(2)
    policy = RecordingStoragePolicy(gib)
    try { guard.requireCapacity(2); fail("Expected new quota") } catch (_: IOException) {}
  }
}
