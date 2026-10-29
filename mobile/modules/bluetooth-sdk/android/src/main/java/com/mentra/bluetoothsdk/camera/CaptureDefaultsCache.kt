package com.mentra.bluetoothsdk

/** Cache writes deliberately use set, never apply: these values are for the next readiness replay. */
internal object CaptureDefaultsCache {
    /** One canonical object prevents reconnect from mixing old dimensions, duration, and mode. */
    @Synchronized
    fun recordingProfile(profile: UnifiedRecordingProfile) {
        DeviceStore.set(ObservableStore.BLUETOOTH_CATEGORY, "recording_profile", profile.values())
    }

    @Synchronized
    fun recordingProfile(): UnifiedRecordingProfile? {
        val fields = stringMapValue(DeviceStore.get(ObservableStore.BLUETOOTH_CATEGORY, "recording_profile")) ?: return null
        return UnifiedRecordingProfile.fromMap(fields)
    }

    @Synchronized
    fun photoSize(size: PhotoSize) {
        DeviceStore.set(ObservableStore.BLUETOOTH_CATEGORY, "button_photo_size", size.value)
    }

    /** Stage both button-photo defaults for the next readiness replay. */
    @Synchronized
    fun photoDefaults(size: PhotoSize, enableFlash: Boolean) {
        photoSize(size)
        DeviceStore.set(ObservableStore.BLUETOOTH_CATEGORY, "button_photo_flash", enableFlash)
    }

    @Synchronized
    fun video(defaults: VideoRecordingDefaults, maxRecordingTimeMinutes: Int) {
        videoDimensions(defaults)
        videoDuration(maxRecordingTimeMinutes)
    }

    @Synchronized
    fun videoDimensions(defaults: VideoRecordingDefaults) {
        DeviceStore.set(
            ObservableStore.BLUETOOTH_CATEGORY,
            "button_video_settings",
            mapOf("width" to defaults.width, "height" to defaults.height, "fps" to defaults.fps),
        )
        // Readiness prefers the canonical object, but older internal callers use these keys.
        DeviceStore.set(ObservableStore.BLUETOOTH_CATEGORY, "button_video_width", defaults.width)
        DeviceStore.set(ObservableStore.BLUETOOTH_CATEGORY, "button_video_height", defaults.height)
        DeviceStore.set(ObservableStore.BLUETOOTH_CATEGORY, "button_video_fps", defaults.fps)
    }

    @Synchronized
    fun videoDuration(minutes: Int) {
        DeviceStore.set(ObservableStore.BLUETOOTH_CATEGORY, "button_max_recording_time", minutes)
    }
}
