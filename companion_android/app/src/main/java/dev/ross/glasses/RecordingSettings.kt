package dev.ross.glasses

import android.content.Context

class RecordingSettings(context: Context, private val repository: RecordingRepository) {
  private val preferences = context.getSharedPreferences("recording_storage", Context.MODE_PRIVATE)
  private val capturePreferences = context.getSharedPreferences("capture_defaults", Context.MODE_PRIVATE)
  private var photoProfile = SavedProfile(PhotoCaptureProfile.decode(runCatching { capturePreferences.getString("photo", null) }.getOrNull()) ?: PhotoCaptureProfile(),
    runCatching { capturePreferences.getLong("photo_revision", 0) }.getOrDefault(0).coerceAtLeast(0))
  private var videoProfile = loadVideoDefaults()

  /** The canonical profile commits before legacy keys are removed from their separate file. */
  private fun loadVideoDefaults(): SavedProfile<VideoRecordingProfile> = synchronized(MIGRATION_LOCK) {
    val encoded = runCatching { capturePreferences.getString("video", null) }.getOrNull()
    val decoded = VideoRecordingProfile.decode(encoded)
    val revision = runCatching { capturePreferences.getLong("video_revision", 0) }.getOrDefault(0).coerceAtLeast(0)
    if (decoded != null && encoded?.startsWith("2,") == true) {
      clearLegacyPhoneVideo()
      return@synchronized SavedProfile(decoded, revision)
    }
    val hasLegacyPhoneVideo = preferences.contains("phone_video_enabled") || preferences.contains("phone_video_minutes")
    val profile = decoded ?: VideoRecordingProfile()
    if (decoded == null && !hasLegacyPhoneVideo) return@synchronized SavedProfile(profile, revision)
    val offload = runCatching { preferences.getBoolean("phone_video_enabled", false) }.getOrDefault(false)
    val minutes = if (offload) runCatching { preferences.getInt("phone_video_minutes", 60) }.getOrDefault(60)
      .takeIf { it in VideoRecordingProfile.DURATIONS } ?: 60 else profile.maxMinutes
    val migrated = SavedProfile(profile.copy(maxMinutes = minutes, offloadToPhone = offload), Math.addExact(revision, 1))
    if (!capturePreferences.edit().putString("video", migrated.profile.encode())
        .putLong("video_revision", migrated.revision).commit()) {
      // A failed commit can already replace the in-memory map. Restore the prior authority
      // and retain both legacy keys so retry cannot silently change mode or duration.
      capturePreferences.edit().putString("video", encoded).putLong("video_revision", revision).commit()
      error("Could not migrate recording defaults. Existing settings were retained; reopen to retry.")
    }
    clearLegacyPhoneVideo()
    migrated
  }

  private fun clearLegacyPhoneVideo() {
    if (preferences.contains("phone_video_enabled") || preferences.contains("phone_video_minutes")) {
      // SharedPreferences cannot transact across files. Once v2 is durable it always wins;
      // an interrupted cleanup is harmless and retried on the next construction.
      preferences.edit().remove("phone_video_enabled").remove("phone_video_minutes").commit()
    }
  }
  fun photoDefaults() = photoProfile
  fun videoDefaults() = videoProfile
  fun savePhotoDefaults(profile: PhotoCaptureProfile): SavedProfile<PhotoCaptureProfile> {
    val next = SavedProfile(profile, Math.addExact(photoProfile.revision, 1))
    if (!capturePreferences.edit().putString("photo", profile.encode()).putLong("photo_revision", next.revision).commit()) {
      capturePreferences.edit().putString("photo", photoProfile.profile.encode()).putLong("photo_revision", photoProfile.revision).commit()
      error("Could not save camera defaults.")
    }
    photoProfile = next
    return next
  }
  fun saveVideoDefaults(profile: VideoRecordingProfile): SavedProfile<VideoRecordingProfile> {
    val next = SavedProfile(profile, Math.addExact(videoProfile.revision, 1))
    if (!capturePreferences.edit().putString("video", profile.encode()).putLong("video_revision", next.revision).commit()) {
      capturePreferences.edit().putString("video", videoProfile.profile.encode()).putLong("video_revision", videoProfile.revision).commit()
      error("Could not save recording defaults.")
    }
    videoProfile = next
    clearLegacyPhoneVideo()
    return next
  }
  fun policy() = RecordingStoragePolicy(
    preferences.getLong("budget", 0).takeIf { it > 0 },
    preferences.getLong("reserve", RecordingStoragePolicy.GIB),
  )
  fun save(policy: RecordingStoragePolicy) {
    check(preferences.edit().putLong("budget", policy.budgetBytes ?: 0)
      .putLong("reserve", policy.reserveBytes).commit()) { "Could not save storage settings." }
  }
  fun guard() = repository.storageGuard
  fun phoneVideoEnabled() = videoProfile.profile.offloadToPhone
  fun phoneVideoMinutes() = videoProfile.profile.maxMinutes
  fun keepMediaWifiReady() = preferences.getBoolean("keep_media_wifi_ready", true)
  fun setKeepMediaWifiReady(enabled: Boolean) {
    check(preferences.edit().putBoolean("keep_media_wifi_ready", enabled).commit()) { "Could not save media connection setting." }
  }

  companion object {
    private val MIGRATION_LOCK = Any()
  }
}
