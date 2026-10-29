package dev.ross.glasses

import org.junit.Assert.*
import org.junit.Test

class PhoneVideoPlanTest {
  @Test fun bothModesShareEveryOfferedDurationIncludingLegacyChoices() {
    assertEquals(VideoRecordingProfile.DURATIONS, PhoneVideoPlan.DURATIONS)
    for (minutes in VideoRecordingProfile.DURATIONS) {
      val plan = PhoneVideoPlan.create(minutes, 10 * PhoneVideoPlan.SEGMENT_BYTES)
      assertEquals(minutes, plan.maxMinutes)
      assertEquals(minutes, VideoRecordingProfile(maxMinutes = minutes, offloadToPhone = false).maxMinutes)
      assertEquals(minutes, VideoRecordingProfile(maxMinutes = minutes, offloadToPhone = true).maxMinutes)
    }
  }
  @Test fun noTimerStillHasFiniteStorageGrantAndPublicationHeadroom() {
    val segment = PhoneVideoPlan.SEGMENT_BYTES
    val plan = PhoneVideoPlan.create(0, 10 * segment + 1234)
    assertEquals(8 * segment, plan.maxSessionBytes)
    assertEquals(0, plan.maxMinutes)
    assertEquals(4 * segment, plan.options().maxBacklogBytes)
  }

  @Test fun refusesToStartWithoutRoomForCaptureAndPublication() {
    for (bytes in listOf(-1L, 0L, 3 * PhoneVideoPlan.SEGMENT_BYTES - 1)) {
      assertThrows(IllegalArgumentException::class.java) { PhoneVideoPlan.create(60, bytes) }
    }
  }

  @Test fun honorsSmallerOfAllowanceAndPhysicalHeadroom() {
    val segment = PhoneVideoPlan.SEGMENT_BYTES
    val policy = RecordingStoragePolicy(20 * segment, segment)
    val plan = PhoneVideoPlan.create(120, policy.remaining(2 * segment, 7 * segment))
    assertEquals(4 * segment, plan.maxSessionBytes)
  }

  @Test fun largeBudgetsCannotOverflow() {
    val plan = PhoneVideoPlan.create(0, Long.MAX_VALUE)
    assertTrue(plan.maxSessionBytes > 0)
    assertTrue(plan.maxSessionBytes <= Long.MAX_VALUE - 2 * PhoneVideoPlan.SEGMENT_BYTES)
  }
}
