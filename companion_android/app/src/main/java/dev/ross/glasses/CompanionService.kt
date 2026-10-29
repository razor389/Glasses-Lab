package dev.ross.glasses

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*

/** Owns the connection and active work independently of every Activity binding. */
class CompanionService : Service() {
  lateinit var controller: GlassesController
    private set
  private var started = false
  private var wakeLock: PowerManager.WakeLock? = null
  private var renewedAt = 0L
  private var lastNotification = ""
  private var lastNotificationAt = 0L
  private val handler = Handler(Looper.getMainLooper())
  private val tick = object : Runnable {
    override fun run() { controller.backgroundTick(); update(); handler.postDelayed(this, 5000) }
  }
  inner class LocalBinder : Binder() { val owner get() = this@CompanionService }

  override fun onCreate() {
    super.onCreate()
    getSystemService(NotificationManager::class.java).createNotificationChannel(
      NotificationChannel(CHANNEL, "Glasses connection and recordings", NotificationManager.IMPORTANCE_LOW))
    controller = GlassesController(this, ::keepRunning, ::update)
    QuickControls.publish(this, false, null, false)
    handler.post(tick)
  }
  override fun onBind(intent: Intent?) = LocalBinder()
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    try { promote() } catch (error: Exception) {
      controller.reportError("Background connection could not start: ${error.message}. Open Glasses Lab and check permissions.")
      stopSelf()
      return START_NOT_STICKY
    }
    when (val command = intent?.action) {
      STOP -> if (controller.recordings.busy) controller.stopRecordingOrTransfer() else controller.disconnect()
      PAUSE -> controller.pauseOrResumeAudio()
      QuickControls.PHOTO, QuickControls.VIDEO, QuickControls.AUDIO -> controller.quickAction(command)
      else -> controller.restoreConnection()
    }
    return START_STICKY
  }

  private fun keepRunning() {
    if (!started) {
      startForegroundService(Intent(this, CompanionService::class.java).setAction(KEEP))
      promote() // Fail before capture starts if foreground prerequisites are missing.
    }
    update()
  }
  private fun promote() {
    val notification = notification("Connecting to your glasses…")
    if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    else startForeground(ID, notification)
    started = true
  }

  private fun update() {
    if (!::controller.isInitialized) return
    val recordings = controller.recordings
    QuickControls.publish(this, controller.glasses.ready && recordings.recovered &&
      !controller.capturing && (!recordings.session.idle || (!recordings.downloading && !recordings.publishing)) &&
      !controller.audio.callActive && !controller.testingAudio,
      recordings.session.kind?.name, !recordings.session.idle, controller.canTakePhoto, controller.canStartVideo)
    val now = SystemClock.elapsedRealtime()
    if (recordings.needsWakeLock || controller.capturing || controller.defaults.applying) {
      if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassesLab:activeCapture").apply { setReferenceCounted(false) }
      if (wakeLock?.isHeld != true || now - renewedAt >= 60_000) {
        wakeLock!!.acquire(120_000)
        renewedAt = now
      }
    } else releaseWakeLock()
    if (!started) return
    val label = when {
      recordings.publishing && recordings.session.idle -> "Saving captures to phone media…"
      !recordings.session.idle || recordings.downloading -> recordings.message +
        if (recordings.session.kind == RecordingSession.Kind.AUDIO) " · ${recordings.capturedSeconds / 60} min saved" else ""
      controller.capturing -> controller.message
      controller.defaults.applying -> "Applying capture defaults to glasses…"
      controller.glasses.ready && (!controller.defaults.photoApplied || !controller.defaults.videoApplied) -> "Glasses connected · check capture defaults in Settings"
      controller.glasses.ready -> "Glasses connected · ready"
      else -> controller.message
    }
    val notificationState = "$label|${controller.canTakePhoto}|${controller.canStartVideo}|${controller.canRecord}"
    if (notificationState != lastNotification && now - lastNotificationAt >= 1000) {
      getSystemService(NotificationManager::class.java).notify(ID, notification(label))
      lastNotification = notificationState
      lastNotificationAt = now
    }
    if (!controller.wantsConnection && !recordings.busy && !controller.capturing && !controller.defaults.applying) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      started = false
    }
  }

  private fun notification(label: String): Notification {
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val stop = PendingIntent.getService(this, 1, Intent(this, CompanionService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
    val active = ::controller.isInitialized && !controller.recordings.session.idle
    val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_glasses)
      .setContentTitle("Glasses Lab").setContentText(label).setStyle(Notification.BigTextStyle().bigText(label))
      .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
    fun command(title: String, action: String, id: Int) {
      val pending = PendingIntent.getForegroundService(this, id, Intent(this, CompanionService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
      builder.addAction(Notification.Action.Builder(null, title, pending).build())
    }
    if (active || (::controller.isInitialized && (controller.recordings.downloading || controller.recordings.publishing))) {
      if (controller.recordings.session.kind == RecordingSession.Kind.AUDIO) {
        command(if (controller.recordings.paused) "Resume" else "Pause", PAUSE, 5)
      }
      builder.addAction(Notification.Action.Builder(null, if (active) "Stop recording" else "Stop transfer", stop).build())
    } else {
      if (::controller.isInitialized) {
        if (controller.canTakePhoto) command("Photo", QuickControls.PHOTO, 2)
        if (controller.canStartVideo) command("Video", QuickControls.VIDEO, 3)
        if (controller.canRecord) command("Audio", QuickControls.AUDIO, 4)
        if (!controller.defaults.photoApplied || !controller.defaults.videoApplied) {
          val settings = PendingIntent.getActivity(this, 6, Intent(this, RecordingSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
          builder.addAction(Notification.Action.Builder(null, "Settings", settings).build())
        }
      }
    }
    return builder.build()
  }
  private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    controller.close()
    QuickControls.publish(this, false, null, false)
    releaseWakeLock()
    super.onDestroy()
  }
  companion object {
    const val RECONNECT = "dev.ross.glasses.RECONNECT"
    private const val KEEP = "dev.ross.glasses.KEEP"
    private const val STOP = "dev.ross.glasses.STOP"
    private const val PAUSE = "dev.ross.glasses.PAUSE"
    private const val CHANNEL = "glasses-companion"
    private const val ID = 41
  }
}
