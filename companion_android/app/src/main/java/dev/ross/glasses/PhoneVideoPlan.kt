package dev.ross.glasses

import com.mentra.bluetoothsdk.SegmentedVideoOptions

/** A finite byte grant for a continuous recording, even when its time limit is disabled. */
data class PhoneVideoPlan(val maxMinutes: Int, val maxSessionBytes: Long) {
  init {
    require(maxMinutes in DURATIONS) { "Choose a supported phone recording duration." }
    require(maxSessionBytes >= SEGMENT_BYTES) { "Not enough phone space for a video segment." }
  }

  fun options() = SegmentedVideoOptions(maxSessionBytes = maxSessionBytes,
    segmentMaxBytes = SEGMENT_BYTES, maxBacklogBytes = BACKLOG_BYTES)

  companion object {
    const val SEGMENT_BYTES = 64L * 1024 * 1024
    const val BACKLOG_BYTES = 256L * 1024 * 1024
    val DURATIONS = VideoRecordingProfile.DURATIONS

    /** Leave room for transfer/publication copies in addition to the retained recording. */
    fun create(maxMinutes: Int, availableBytes: Long): PhoneVideoPlan {
      val usable = (availableBytes.coerceAtLeast(0) - 2 * SEGMENT_BYTES).coerceAtLeast(0)
      return PhoneVideoPlan(maxMinutes, usable / SEGMENT_BYTES * SEGMENT_BYTES)
    }

    fun durationDescription(minutes: Int) = if (minutes == 0)
      "No time limit; recording stops at its storage allowance."
    else "Maximum $minutes minutes; storage may end recording earlier."

    fun stopDescription(reason: String) = when (reason) {
      "max_duration" -> "The selected recording length was reached."
      "session_byte_limit" -> "The recording's phone storage allowance was reached."
      "backlog_full" -> "The phone could not keep up. Completed video parts are retained on the glasses for sync."
      "storage_low" -> "The glasses need more free space. Saved video parts are retained."
      "battery_low", "low_battery" -> "Recording stopped because the glasses battery is low."
      "user_stop", "stopped" -> "Recording stopped."
      else -> reason.replace('_', ' ')
    }
  }
}
