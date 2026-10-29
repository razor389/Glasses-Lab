package com.mentra.bluetoothsdk.camera

import com.mentra.bluetoothsdk.CaptureDefaultsCache
import com.mentra.bluetoothsdk.DeviceStore
import com.mentra.bluetoothsdk.ObservableStore
import com.mentra.bluetoothsdk.PhotoSize
import com.mentra.bluetoothsdk.VideoRecordingDefaults
import com.mentra.bluetoothsdk.UnifiedRecordingProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CaptureDefaultsCacheTest {
    private val category = ObservableStore.BLUETOOTH_CATEGORY
    private val keys = listOf("button_video_settings", "button_video_width", "button_video_height", "button_video_fps",
        "button_max_recording_time", "button_photo_size", "button_photo_flash", "button_photo_mfnr", "camera_fov", "recording_profile")
    private lateinit var before: Map<String, Any?>

    @Before
    fun snapshotCache() { before = keys.associateWith { DeviceStore.get(category, it) } }

    @After
    fun restoreCache() {
        before.forEach { (key, value) ->
            if (value == null) DeviceStore.store.remove(category, key) else DeviceStore.set(category, key, value)
        }
    }

    @Test
    fun `startup hydration replaces canonical and legacy video replay values together`() {
        DeviceStore.set(category, "button_video_settings", mapOf("width" to 1280, "height" to 720, "fps" to 30))
        DeviceStore.set(category, "button_max_recording_time", 10)
        CaptureDefaultsCache.video(VideoRecordingDefaults(1920, 1080, 15), 3)

        assertThat(DeviceStore.get(category, "button_video_settings")).isEqualTo(mapOf("width" to 1920, "height" to 1080, "fps" to 15))
        assertThat(DeviceStore.get(category, "button_video_width")).isEqualTo(1920)
        assertThat(DeviceStore.get(category, "button_video_height")).isEqualTo(1080)
        assertThat(DeviceStore.get(category, "button_video_fps")).isEqualTo(15)
        assertThat(DeviceStore.get(category, "button_max_recording_time")).isEqualTo(3)
    }

    @Test
    fun `photo and video initialization preserve independent settings and scan tuning`() {
        DeviceStore.set(category, "button_photo_mfnr", false)
        val fov = mapOf("fov" to 82, "roi_position" to 1)
        DeviceStore.set(category, "camera_fov", fov)
        CaptureDefaultsCache.video(VideoRecordingDefaults(1280, 720, 5), 20)
        CaptureDefaultsCache.photoSize(PhotoSize.LOW)
        CaptureDefaultsCache.videoDimensions(VideoRecordingDefaults(1920, 1080, 30))

        assertThat(DeviceStore.get(category, "button_photo_size")).isEqualTo("low")
        assertThat(DeviceStore.get(category, "button_photo_mfnr")).isEqualTo(false)
        assertThat(DeviceStore.get(category, "camera_fov")).isEqualTo(fov)
        assertThat(DeviceStore.get(category, "button_max_recording_time")).isEqualTo(20)
    }

    @Test
    fun `unified reconnect profile remains one snapshot despite stale legacy cache writes`() {
        val profile = UnifiedRecordingProfile("saved-revision", 1280, 720, 5, 120, "phone", enableFlash = false)
        CaptureDefaultsCache.recordingProfile(profile)
        CaptureDefaultsCache.video(VideoRecordingDefaults(1920, 1080, 30), 3)
        assertThat(CaptureDefaultsCache.recordingProfile()).isEqualTo(profile)
        assertThat(DeviceStore.get(category, "recording_profile")).isEqualTo(profile.values())
    }

    @Test fun photoDefaultsRetainDisabledFlashAcrossSizeOnlyUpdates() {
        CaptureDefaultsCache.photoDefaults(PhotoSize.HIGH, false)
        CaptureDefaultsCache.photoSize(PhotoSize.LOW)
        assertThat(DeviceStore.get(category, "button_photo_size")).isEqualTo("low")
        assertThat(DeviceStore.get(category, "button_photo_flash")).isEqualTo(false)
        CaptureDefaultsCache.photoDefaults(PhotoSize.MAX, true)
        assertThat(DeviceStore.get(category, "button_photo_flash")).isEqualTo(true)
    }
}
