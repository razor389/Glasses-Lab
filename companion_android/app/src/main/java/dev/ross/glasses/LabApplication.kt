package dev.ross.glasses

import android.app.Application
import android.app.Activity
import android.os.Bundle

/** Durable storage is process-scoped; the service owns the live connection. */
class LabApplication : Application() {
  private var startedActivities = 0
  val isUiVisible get() = startedActivities > 0
  val mediaPlayback = MediaPlaybackOwners()
  val isMediaPlaybackActive get() = mediaPlayback.isActive
  val media by lazy { PhoneMediaPublisher(this) }
  val recordings by lazy { RecordingRepository(this, media = media) }
  val videoSessions by lazy { VideoSessionStore(this) }

  override fun onCreate() {
    super.onCreate()
    registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
      override fun onActivityStarted(activity: Activity) { startedActivities++ }
      override fun onActivityStopped(activity: Activity) { startedActivities = (startedActivities - 1).coerceAtLeast(0) }
      override fun onActivityCreated(activity: Activity, state: Bundle?) {}
      override fun onActivityResumed(activity: Activity) {}
      override fun onActivityPaused(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
    })
  }
}
