package dev.ross.glasses

import com.mentra.bluetoothsdk.VideoRecordingDefaults
import com.mentra.bluetoothsdk.VideoRecordingRequest
import com.mentra.bluetoothsdk.UnifiedRecordingProfile

data class SavedProfile<T>(val profile: T, val revision: Long)

data class VideoRecordingProfile(val width: Int = 1280, val height: Int = 720,
  val fps: Int = 30, val maxMinutes: Int = 5, val offloadToPhone: Boolean = false, val enableFlash: Boolean = true) {
  init {
    require((width == 1280 && height == 720) || (width == 1920 && height == 1080)) { "Choose 720p or 1080p." }
    require(fps in listOf(5, 15, 30)) { "Choose 5, 15, or 30 FPS." }
    require(maxMinutes in DURATIONS) { "Choose a supported video length." }
  }
  val mode get() = if (offloadToPhone) "phone" else "local"
  val summary get() = "${height}p · $fps FPS · ${if (maxMinutes == 0) "no time limit" else "up to $maxMinutes minutes"} · " +
    if (offloadToPhone) "offload to phone" else "save on glasses"
  fun sdkDefaults() = VideoRecordingDefaults(width, height, fps)
  fun sdkProfile(revision: String) = UnifiedRecordingProfile(
    revision = revision,
    width = width,
    height = height,
    fps = fps,
    maxRecordingTimeMinutes = maxMinutes,
    mode = mode,
    segmentMaxBytes = PhoneVideoPlan.SEGMENT_BYTES,
    maxBacklogBytes = PhoneVideoPlan.BACKLOG_BYTES,
    enableFlash = enableFlash,
  )
  fun request(id: String, revision: String) = VideoRecordingRequest(id, save = true, sound = true,
    useRecordingProfile = true, profileRevision = revision)
  fun encode() = "2,$width,$height,$fps,$maxMinutes,$mode,$enableFlash"
  companion object {
    val DURATIONS = listOf(3, 5, 10, 15, 20, 30, 60, 120, 0)
    private val LEGACY_DURATIONS = listOf(3, 5, 10, 15, 20)
    fun decode(value: String?): VideoRecordingProfile? = runCatching {
      val parts = requireNotNull(value).split(',')
      val minutes = parts.getOrNull(4)?.toInt()
        ?: error("Missing recording duration")

      val offload = when {
        parts.size == 5 && parts[0] == "1" -> {
          require(minutes in LEGACY_DURATIONS)
          false
        }
        parts.size in 6..7 && parts[0] == "2" -> when (parts[5]) {
          "local" -> false
          "phone" -> true
          else -> error("Unknown recording mode")
        }
        else -> error("Unknown recording profile")
      }

      VideoRecordingProfile(
        width = parts[1].toInt(),
        height = parts[2].toInt(),
        fps = parts[3].toInt(),
        maxMinutes = minutes,
        offloadToPhone = offload,
        enableFlash = parts.getOrNull(6)?.toBooleanStrict() ?: true,
      )
    }.getOrNull()
  }
}
