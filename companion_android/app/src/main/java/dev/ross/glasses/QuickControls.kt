package dev.ross.glasses

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.util.Log

/** A small display snapshot for system tiles; actual capture eligibility stays in the service. */
object QuickControls {
  const val PHOTO = "dev.ross.glasses.PHOTO"
  const val VIDEO = "dev.ross.glasses.VIDEO"
  const val AUDIO = "dev.ross.glasses.AUDIO"

  internal data class State(
    val ready: Boolean,
    val kind: String,
    val active: Boolean,
    val photoReady: Boolean = false,
    val videoReady: Boolean = false,
  ) {
    fun canStart(action: String): Boolean = ready && !active && when (action) {
      PHOTO -> photoReady
      VIDEO -> videoReady
      AUDIO -> true
      else -> false
    }
    fun isStopping(captureKind: String): Boolean = active && captureKind.isNotEmpty() && kind == captureKind
    fun unavailableReason(action: String): String = when {
      active -> "Finish the current capture first."
      !ready -> "Open Glasses Lab and check your connection or current activity."
      action == PHOTO && !photoReady -> "Open Settings in Glasses Lab to check camera defaults."
      action == VIDEO && !videoReady -> "Open Settings in Glasses Lab to check recording defaults."
      else -> "Open Glasses Lab to use this control."
    }
  }
  internal fun read(context: Context): State {
    val preferences = context.getSharedPreferences("quick_controls", Context.MODE_PRIVATE)
    return State(preferences.getBoolean("ready", false), preferences.getString("kind", "").orEmpty(),
      preferences.getBoolean("active", false), preferences.getBoolean("photo_ready", false),
      preferences.getBoolean("video_ready", false))
  }

  /** Call on service state transitions, including startup/shutdown, rather than every audio frame. */
  @Synchronized fun publish(context: Context, ready: Boolean, kind: String?, active: Boolean,
    photoReady: Boolean = false, videoReady: Boolean = false) {
    val next = State(ready, kind?.takeIf { active && it in listOf("AUDIO", "VIDEO") }.orEmpty(), active,
      photoReady, videoReady)
    if (read(context) == next) return
    context.getSharedPreferences("quick_controls", Context.MODE_PRIVATE).edit()
      .putBoolean("ready", next.ready).putString("kind", next.kind).putBoolean("active", next.active)
      .putBoolean("photo_ready", next.photoReady).putBoolean("video_ready", next.videoReady).apply()
    listOf(PhotoTileService::class.java, VideoTileService::class.java, AudioTileService::class.java).forEach { type ->
      runCatching { TileService.requestListeningState(context, ComponentName(context, type)) }
        .onFailure { Log.w("GlassesLab", "Quick control display could not refresh", it) }
    }
  }
}
