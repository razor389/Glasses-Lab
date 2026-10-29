package dev.ross.glasses

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mentra.bluetoothsdk.PhotoSize
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated preferences/database only: never binds the companion or contacts glasses. */
@RunWith(AndroidJUnit4::class)
class CaptureDefaultsDeviceTest {
  private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val token = UUID.randomUUID().toString()
  private val database = "test-capture-defaults-$token.sqlite"
  private val directory get() = File(base.filesDir, "test-capture-defaults-$token")
  private lateinit var context: IsolatedPreferencesContext
  private lateinit var repository: RecordingRepository

  @Before fun setup() {
    context = IsolatedPreferencesContext(base, token)
    repository = RecordingRepository(context, database, directory)
  }

  @After fun cleanup() {
    repository.close()
    base.deleteDatabase(database)
    context.clearTestPreferences()
    check(directory.canonicalFile.parentFile == base.filesDir.canonicalFile)
    directory.deleteRecursively()
  }

  @Test fun independentProfilesAndRevisionsSurviveReconstruction() {
    val settings = RecordingSettings(context, repository)
    val policy = RecordingStoragePolicy(4L * RecordingStoragePolicy.GIB, RecordingStoragePolicy.GIB)
    settings.save(policy)
    settings.setKeepMediaWifiReady(false)
    val photo = settings.savePhotoDefaults(PhotoCaptureProfile(PhotoSize.MAX))
    val video = settings.saveVideoDefaults(VideoRecordingProfile(1920, 1080, 15, 20))
    val updatedPhoto = settings.savePhotoDefaults(PhotoCaptureProfile(PhotoSize.LOW))

    val restored = RecordingSettings(context, repository)
    assertEquals(1L, photo.revision)
    assertEquals(2L, updatedPhoto.revision)
    assertEquals(1L, video.revision)
    assertEquals(updatedPhoto, restored.photoDefaults())
    assertEquals(video, restored.videoDefaults())
    assertEquals(policy, restored.policy())
    assertFalse(restored.keepMediaWifiReady())
  }

  @Test fun failedPhotoSaveKeepsPreviousProfileAndDoesNotChangeVideo() {
    val settings = RecordingSettings(context, repository)
    val photo = settings.savePhotoDefaults(PhotoCaptureProfile(PhotoSize.MEDIUM))
    val video = settings.saveVideoDefaults(VideoRecordingProfile(1920, 1080, 5, 10))
    context.failNextCaptureCommit = true
    try {
      settings.savePhotoDefaults(PhotoCaptureProfile(PhotoSize.MAX))
      fail("Expected local persistence failure")
    } catch (expected: IllegalStateException) {
      assertTrue(expected.message.orEmpty().contains("Could not save"))
    }
    assertEquals(photo, settings.photoDefaults())
    val restored = RecordingSettings(context, repository)
    assertEquals(photo, restored.photoDefaults())
    assertEquals(video, restored.videoDefaults())
  }

  @Test fun failedVideoSaveKeepsPreviousProfileAndDoesNotChangePhoto() {
    val settings = RecordingSettings(context, repository)
    val photo = settings.savePhotoDefaults(PhotoCaptureProfile(PhotoSize.MAX))
    val video = settings.saveVideoDefaults(VideoRecordingProfile(1280, 720, 15, 3))
    context.failNextCaptureCommit = true
    try {
      settings.saveVideoDefaults(VideoRecordingProfile(1920, 1080, 30, 20))
      fail("Expected local persistence failure")
    } catch (expected: IllegalStateException) {
      assertTrue(expected.message.orEmpty().contains("Could not save"))
    }
    assertEquals(video, settings.videoDefaults())
    val restored = RecordingSettings(context, repository)
    assertEquals(video, restored.videoDefaults())
    assertEquals(photo, restored.photoDefaults())
  }

  @Test fun missingAndMalformedProfilesRecoverToSupportedInitialValues() {
    val fresh = RecordingSettings(context, repository)
    assertEquals(PhotoCaptureProfile(), fresh.photoDefaults().profile)
    assertEquals(VideoRecordingProfile(), fresh.videoDefaults().profile)
    context.getSharedPreferences("capture_defaults", Context.MODE_PRIVATE).edit()
      .putString("photo", "1,unknown")
      .putString("video", "1,3840,2160,60,999")
      .putLong("photo_revision", -4).putLong("video_revision", -2).commit()
    val restored = RecordingSettings(context, repository)
    assertEquals(SavedProfile(PhotoCaptureProfile(), 0L), restored.photoDefaults())
    assertEquals(SavedProfile(VideoRecordingProfile(), 0L), restored.videoDefaults())
  }

  @Test fun wrongPreferenceTypesRecoverWithoutPreventingSettingsStartup() {
    context.getSharedPreferences("capture_defaults", Context.MODE_PRIVATE).edit()
      .putInt("photo", 7).putBoolean("video", true)
      .putString("photo_revision", "broken").putBoolean("video_revision", false).commit()
    val restored = RecordingSettings(context, repository)
    assertEquals(SavedProfile(PhotoCaptureProfile(), 0L), restored.photoDefaults())
    assertEquals(SavedProfile(VideoRecordingProfile(), 0L), restored.videoDefaults())
    // A subsequent valid save replaces corrupt typed values and survives reconstruction.
    val savedPhoto = restored.savePhotoDefaults(PhotoCaptureProfile(PhotoSize.MAX))
    val savedVideo = restored.saveVideoDefaults(VideoRecordingProfile(1920, 1080, 15, 10))
    val rebuilt = RecordingSettings(context, repository)
    assertEquals(savedPhoto, rebuilt.photoDefaults())
    assertEquals(savedVideo, rebuilt.videoDefaults())
  }

  @Test fun legacyPhoneModeMigratesQualityAndDurationOnceWithoutChangingStorage() {
    val capture = context.getSharedPreferences("capture_defaults", Context.MODE_PRIVATE)
    capture.edit().putString("video", "1,1920,1080,15,5").putLong("video_revision", 7).commit()
    val storage = context.getSharedPreferences("recording_storage", Context.MODE_PRIVATE)
    storage.edit().putBoolean("phone_video_enabled", true).putInt("phone_video_minutes", 60)
      .putLong("budget", 8 * RecordingStoragePolicy.GIB).putBoolean("keep_media_wifi_ready", false).commit()

    val migrated = RecordingSettings(context, repository)
    val expected = SavedProfile(VideoRecordingProfile(1920, 1080, 15, 60, true), 8L)
    assertEquals(expected, migrated.videoDefaults())
    assertFalse(storage.contains("phone_video_enabled"))
    assertFalse(storage.contains("phone_video_minutes"))
    assertEquals(8 * RecordingStoragePolicy.GIB, migrated.policy().budgetBytes)
    assertFalse(migrated.keepMediaWifiReady())
    assertEquals(expected, RecordingSettings(context, repository).videoDefaults())
  }

  @Test fun disabledLegacyPreviewKeepsTheLocalDuration() {
    context.getSharedPreferences("capture_defaults", Context.MODE_PRIVATE).edit()
      .putString("video", "1,1280,720,5,3").putLong("video_revision", 2).commit()
    context.getSharedPreferences("recording_storage", Context.MODE_PRIVATE).edit()
      .putBoolean("phone_video_enabled", false).putInt("phone_video_minutes", 120).commit()
    assertEquals(SavedProfile(VideoRecordingProfile(1280, 720, 5, 3, false), 3L),
      RecordingSettings(context, repository).videoDefaults())
  }

  @Test fun canonicalProfileWinsWhenLegacyCleanupWasInterrupted() {
    val settings = RecordingSettings(context, repository)
    val saved = settings.saveVideoDefaults(VideoRecordingProfile(1920, 1080, 30, 0, true))
    val storage = context.getSharedPreferences("recording_storage", Context.MODE_PRIVATE)
    storage.edit().putBoolean("phone_video_enabled", false).putInt("phone_video_minutes", 5).commit()
    assertEquals(saved, RecordingSettings(context, repository).videoDefaults())
    assertFalse(storage.contains("phone_video_enabled"))
    assertFalse(storage.contains("phone_video_minutes"))
  }

  @Test fun failedMigrationRetainsLegacyAuthorityAndRetriesWithOneRevisionIncrement() {
    val capture = context.getSharedPreferences("capture_defaults", Context.MODE_PRIVATE)
    capture.edit().putString("video", "1,1280,720,30,5").putLong("video_revision", 9).commit()
    val storage = context.getSharedPreferences("recording_storage", Context.MODE_PRIVATE)
    storage.edit().putBoolean("phone_video_enabled", true).putInt("phone_video_minutes", 60).commit()
    context.failNextCaptureCommit = true
    assertThrows(IllegalStateException::class.java) { RecordingSettings(context, repository) }
    assertEquals("1,1280,720,30,5", capture.getString("video", null))
    assertEquals(9L, capture.getLong("video_revision", -1))
    assertTrue(storage.getBoolean("phone_video_enabled", false))
    assertEquals(60, storage.getInt("phone_video_minutes", -1))
    assertEquals(SavedProfile(VideoRecordingProfile(maxMinutes = 60, offloadToPhone = true), 10L),
      RecordingSettings(context, repository).videoDefaults())
  }

  @Test fun migrationNeverRewritesAnActiveRecordingSnapshot() {
    val active = context.getSharedPreferences("recordings", Context.MODE_PRIVATE)
    active.edit().putString("active_video", "already-recording")
      .putString("active_video_profile", "1,1280,720,15,20")
      .putInt("active_phone_video_minutes", 5).commit()
    val before = active.all.toMap()
    context.getSharedPreferences("recording_storage", Context.MODE_PRIVATE).edit()
      .putBoolean("phone_video_enabled", true).putInt("phone_video_minutes", 60).commit()
    val settings = RecordingSettings(context, repository)
    assertTrue(settings.videoDefaults().profile.offloadToPhone)
    assertEquals(60, settings.videoDefaults().profile.maxMinutes)
    assertEquals(before, active.all)
  }

  private class IsolatedPreferencesContext(base: Context, private val token: String) : ContextWrapper(base) {
    private val names = mutableSetOf<String>()
    var failNextCaptureCommit = false

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
      val testName = "test-$token-$name"
      names.add(testName)
      val actual = super.getSharedPreferences(testName, mode)
      if (name != "capture_defaults") return actual
      return object : SharedPreferences by actual {
        override fun edit(): SharedPreferences.Editor {
          val editor = actual.edit()
          return object : SharedPreferences.Editor by editor {
            // A failed Android commit can already change the in-memory map. Exercise the
            // rollback after that side effect, not only a failure before any values change.
            override fun commit(): Boolean {
              val fail = failNextCaptureCommit
              failNextCaptureCommit = false
              val committed = editor.commit()
              return committed && !fail
            }
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { editor.putLong(key, value); return this }
          }
        }
      }
    }

    fun clearTestPreferences() { names.forEach { baseContext.deleteSharedPreferences(it) } }
  }
}
