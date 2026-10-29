package dev.ross.glasses

import com.mentra.bluetoothsdk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureDefaultsSyncControllerTest {
  private class Transport : CaptureDefaultsTransport {
    var cachedPhoto = PhotoCaptureProfile()
    var cachedVideo = VideoRecordingProfile()
    var cachedRevision = "0"
    var capability = true
    var echoFlash = true
    var photoAckOverrides: Map<String, Any> = emptyMap()
    var videoFailures = 0
    var videoAckOverrides: Map<String, Any> = emptyMap()
    var photoGate: CompletableDeferred<Unit>? = null
    var videoGate: CompletableDeferred<Unit>? = null
    var photoCalls = 0
    var videoCalls = 0
    val videoRequests = mutableListOf<Pair<VideoRecordingProfile, String>>()
    override fun cache(photo: PhotoCaptureProfile, video: VideoRecordingProfile, revision: String) {
      cachedPhoto = photo; cachedVideo = video; cachedRevision = revision
    }
    private fun ack(setting: String, values: Map<String, Any>) = SettingsAckEvent(mapOf("setting" to setting, "status" to "applied") + values)
    override suspend fun photo(profile: PhotoCaptureProfile): SettingsAckEvent {
      photoCalls++
      photoGate?.await()
      // Reproduce the SDK's cache update after ACK.
      cachedPhoto = profile
      return ack("button_photo", mapOf("size" to profile.size.value, "localPhotoQualityVersion" to if (capability) 1 else 0) +
        (if (echoFlash) mapOf("flash" to profile.enableFlash) else emptyMap()) + photoAckOverrides)
    }
    override suspend fun video(profile: VideoRecordingProfile, revision: String): SettingsAckEvent {
      videoCalls++
      videoRequests += profile to revision
      videoGate?.await()
      if (videoFailures-- > 0) error("timeout")
      // Reproduce the SDK's post-ACK cache update, including stale in-flight requests.
      cachedVideo = profile; cachedRevision = revision
      return ack("recording_profile", (profile.sdkProfile(revision).values() - (if (echoFlash) emptySet() else setOf("flash"))) + videoAckOverrides)
    }
  }
  private fun TestScope.controller(transport: Transport) = CaptureDefaultsSyncController(
    SavedProfile(PhotoCaptureProfile(), 0), SavedProfile(VideoRecordingProfile(), 0),
    transport, this, {}, { testScheduler.currentTime })

  @Test fun bothProfilesConfirmAndDisconnectInvalidates() = runTest {
    val transport = Transport(); val sync = controller(transport)
    assertFalse(sync.photoApplied)
    sync.connection("a"); advanceUntilIdle()
    assertTrue(sync.photoApplied); assertTrue(sync.videoApplied)
    assertEquals(1, transport.videoCalls)
    assertEquals("0", transport.cachedRevision)
    sync.connection(null)
    assertFalse(sync.photoApplied); assertFalse(sync.videoApplied)
    assertFalse(sync.applying)
  }
  @Test fun unsupportedPhotoDoesNotBlockVideoOrRetryForever() = runTest {
    val transport = Transport().apply { capability = false }; val sync = controller(transport)
    sync.connection("a"); advanceUntilIdle()
    assertFalse(sync.photoApplied); assertTrue(sync.videoApplied)
    assertTrue(sync.photoStatus.contains("compatible")); assertEquals(1, transport.photoCalls)
  }
  @Test fun videoAckFailureDoesNotClaimAppliedAndRetriesAreBounded() = runTest {
    val transport = Transport().apply { videoFailures = 100 }; val sync = controller(transport)
    sync.connection("a"); runCurrent()
    assertTrue(sync.photoApplied); assertFalse(sync.videoApplied)
    advanceUntilIdle()
    assertTrue(sync.videoCanRetry); assertFalse(sync.applying)
    assertEquals(14_000L, testScheduler.currentTime)
    assertEquals(4, transport.videoCalls)
    transport.videoFailures = 0
    sync.retryVideo(); advanceUntilIdle()
    assertTrue(sync.videoApplied)
  }
  @Test fun mismatchedModeDurationOrRevisionCannotClaimApplied() = runTest {
    for (mismatch in listOf(
      mapOf("mode" to "phone"),
      mapOf("maxRecordingTimeMinutes" to 120),
      mapOf("revision" to "1"),
      mapOf("flash" to false),
    )) {
      val transport = Transport().apply { videoAckOverrides = mismatch }
      val sync = controller(transport)
      sync.connection("a"); advanceUntilIdle()
      assertTrue(sync.photoApplied)
      assertFalse("Mismatched acknowledgement $mismatch must not apply", sync.videoApplied)
      assertTrue(sync.videoCanRetry)
      assertEquals(4, transport.videoCalls)
      transport.videoAckOverrides = emptyMap()
      sync.retryVideo(); advanceUntilIdle()
      assertTrue(sync.videoApplied)
      sync.close()
    }
  }
  @Test fun newerPhotoWinsOverInFlightAckAndReconnectCache() = runTest {
    val gate = CompletableDeferred<Unit>()
    val transport = Transport().apply { photoGate = gate }; val sync = controller(transport)
    sync.connection("a"); runCurrent()
    val next = PhotoCaptureProfile(PhotoSize.LOW, enableFlash = false)
    sync.savePhoto(SavedProfile(next, 1)); runCurrent()
    assertFalse(sync.photoApplied)
    assertTrue(sync.applying)
    assertEquals(1, transport.photoCalls)
    gate.complete(Unit); advanceUntilIdle()
    assertTrue(sync.photoApplied); assertEquals(next, transport.cachedPhoto)
    assertEquals(2, transport.photoCalls)
    assertTrue(sync.videoApplied)
  }
  @Test fun newerVideoWinsOverInFlightAckAndReconnectCache() = runTest {
    val oldGate = CompletableDeferred<Unit>()
    val newGate = CompletableDeferred<Unit>()
    val transport = Transport().apply { videoGate = oldGate }
    val sync = controller(transport)
    sync.connection("a"); runCurrent()
    assertEquals(1, transport.videoCalls)
    val next = VideoRecordingProfile(1920, 1080, 15, 120, offloadToPhone = true, enableFlash = false)
    sync.saveVideo(SavedProfile(next, 1)); runCurrent()
    assertFalse(sync.videoApplied)
    assertEquals(next, transport.cachedVideo)
    assertEquals("1", transport.cachedRevision)
    transport.videoGate = newGate
    oldGate.complete(Unit); runCurrent()
    // The old successful ACK must neither apply the new profile nor leave old SDK defaults staged.
    assertFalse(sync.videoApplied)
    assertEquals(next, transport.cachedVideo)
    assertEquals("1", transport.cachedRevision)
    assertEquals(listOf(VideoRecordingProfile() to "0", next to "1"), transport.videoRequests)
    newGate.complete(Unit); advanceUntilIdle()
    assertTrue(sync.videoApplied)
    sync.connection(null); sync.connection("b"); advanceUntilIdle()
    assertTrue(sync.videoApplied)
    assertEquals(next to "1", transport.videoRequests.last())
    assertEquals(next, transport.cachedVideo)
    assertEquals("1", transport.cachedRevision)
  }
  @Test fun oldDeviceAckCannotConfirmNewSession() = runTest {
    val gate = CompletableDeferred<Unit>()
    val transport = Transport().apply { photoGate = gate }; val sync = controller(transport)
    sync.connection("a"); runCurrent(); sync.connection(null)
    gate.complete(Unit); advanceUntilIdle()
    assertFalse(sync.photoApplied)
    sync.connection("b"); advanceUntilIdle()
    assertTrue(sync.photoApplied); assertTrue(sync.videoApplied)
  }
  @Test fun offlineSaveOnlyUpdatesDesiredCache() = runTest {
    val transport = Transport(); val sync = controller(transport)
    val video = VideoRecordingProfile(1920, 1080, 15, 120, offloadToPhone = true)
    sync.saveVideo(SavedProfile(video, 1)); advanceUntilIdle()
    assertEquals(video, transport.cachedVideo); assertEquals(0, transport.photoCalls)
    assertEquals("1", transport.cachedRevision); assertEquals(0, transport.videoCalls)
    assertFalse(sync.videoApplied); assertFalse(sync.applying)
    sync.connection("a"); advanceUntilIdle(); assertTrue(sync.videoApplied)
  }

  @Test fun olderGlassesMustEchoFlashEvenWhenItDefaultsToTrue() = runTest {
    val transport = Transport().apply { echoFlash = false }
    val sync = controller(transport)
    sync.connection("a"); advanceUntilIdle()
    assertFalse(sync.photoApplied); assertFalse(sync.videoApplied)
    assertEquals(4, transport.photoCalls); assertEquals(4, transport.videoCalls)
    transport.echoFlash = true
    sync.retryPhoto(); sync.retryVideo(); advanceUntilIdle()
    assertTrue(sync.photoApplied); assertTrue(sync.videoApplied)
  }
  @Test fun mismatchedPhotoFlashCannotClaimApplied() = runTest {
    val transport = Transport().apply { photoAckOverrides = mapOf("flash" to true) }
    val sync = controller(transport)
    sync.savePhoto(SavedProfile(PhotoCaptureProfile(enableFlash = false), 1))
    sync.connection("a"); advanceUntilIdle()
    assertFalse(sync.photoApplied); assertTrue(sync.videoApplied)
    transport.photoAckOverrides = emptyMap()
    sync.retryPhoto(); advanceUntilIdle()
    assertTrue(sync.photoApplied)
    sync.connection(null); sync.connection("b"); advanceUntilIdle()
    assertFalse(transport.cachedPhoto.enableFlash)
    assertTrue(sync.photoApplied)
  }
}
