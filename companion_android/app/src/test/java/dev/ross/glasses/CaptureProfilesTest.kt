package dev.ross.glasses

import com.mentra.bluetoothsdk.*
import org.junit.Assert.*
import org.junit.Test

class CaptureProfilesTest {
  @Test fun validVideoProfilesRoundTripAndMapEveryRequestField() {
    for (height in listOf(720, 1080)) for (fps in listOf(5, 15, 30))
      for (minutes in VideoRecordingProfile.DURATIONS) for (offload in listOf(false, true)) for (flash in listOf(false, true)) {
      val p = VideoRecordingProfile(if (height == 720) 1280 else 1920, height, fps, minutes, offload, enableFlash = flash)
      assertEquals(p, VideoRecordingProfile.decode(p.encode()))
      val sdk = p.sdkProfile("revision-7")
      assertEquals(p.width, sdk.width); assertEquals(height, sdk.height)
      assertEquals(fps, sdk.fps); assertEquals(minutes, sdk.maxRecordingTimeMinutes)
      assertEquals(if (offload) "phone" else "local", sdk.mode)
      assertEquals("revision-7", sdk.revision)
      assertEquals(flash, sdk.enableFlash)
      assertEquals(PhoneVideoPlan.SEGMENT_BYTES, sdk.segmentMaxBytes)
      assertEquals(PhoneVideoPlan.BACKLOG_BYTES, sdk.maxBacklogBytes)
      val request = p.request("test", "revision-7")
      assertTrue(request.useRecordingProfile)
      assertEquals("revision-7", request.profileRevision)
      // Both start surfaces use the acknowledged profile rather than per-request overrides.
      assertEquals(0, request.width); assertEquals(0, request.height)
      assertEquals(0, request.fps); assertEquals(0, request.maxRecordingTimeMinutes)
      assertNull(request.segmented); assertNull(request.enableFlash)
      assertTrue(request.sound); assertTrue(request.save)
    }
  }
  @Test fun invalidAndLegacyActiveVideoProfilesStayUnknown() {
    for (encoded in listOf(null, "", "2,1280,720,30,5", "1,1920,720,30,5", "1,1280,720,60,5", "1,1280,720,30,0",
      "2,1280,720,30,60,cloud", "2,1280,720,30,-1,local", "3,1280,720,30,60,phone")) {
      assertNull(VideoRecordingProfile.decode(encoded))
    }
  }
  @Test fun legacyProfileDecodesWithoutInventingAnOffloadMode() {
    assertEquals(VideoRecordingProfile(1920, 1080, 15, 20, false), VideoRecordingProfile.decode("1,1920,1080,15,20"))
  }
  @Test fun unlimitedLengthHasReadableModeAndNoZeroMinuteSummary() {
    for (offload in listOf(false, true)) {
      val profile = VideoRecordingProfile(maxMinutes = 0, offloadToPhone = offload)
      assertTrue(profile.summary.contains("no time limit"))
      assertFalse(profile.summary.contains("0 minutes"))
      assertTrue(profile.summary.contains(if (offload) "offload to phone" else "save on glasses"))
    }
  }
  @Test fun allPhotoChoicesKeepArchivalRoutingAndLocalQuality() {
    for (size in PhotoSize.values()) for (flash in listOf(false, true)) {
      val p = PhotoCaptureProfile(size, enableFlash = flash)
      assertEquals(p, PhotoCaptureProfile.decode(p.encode()))
      val request = p.request("test")
      assertEquals(flash, request.enableFlash)
      assertEquals(size, request.size); assertEquals(PhotoQualityProfile.LOCAL, request.qualityProfile)
      assertTrue(request.save); assertTrue(request.sound); assertEquals("", request.webhookUrl)
    }
    assertNull(PhotoCaptureProfile.decode("1,unrecognized"))
  }

  @Test fun omittedFlashRetainsLegacyDefaultsWithoutBumpingStorageVersions() {
    assertTrue(VideoRecordingProfile.decode("2,1280,720,30,120,phone")!!.enableFlash)
    assertTrue(PhotoCaptureProfile.decode("1,high")!!.enableFlash)
    assertEquals("2,1280,720,30,5,local,false", VideoRecordingProfile(enableFlash = false).encode())
    assertEquals("1,high,false", PhotoCaptureProfile(enableFlash = false).encode())
    for (invalid in listOf("FALSE", "0", "", "null")) {
      assertNull(VideoRecordingProfile.decode("2,1280,720,30,5,local,$invalid"))
      assertNull(PhotoCaptureProfile.decode("1,high,$invalid"))
    }
  }
}
