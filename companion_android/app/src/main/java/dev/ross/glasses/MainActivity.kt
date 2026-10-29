package dev.ross.glasses

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import com.mentra.bluetoothsdk.GlassesRuntimeState
import kotlinx.coroutines.*

/** Everyday capture controls; connection setup and diagnostics stay out of the way. */
class MainActivity : CompanionActivity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private lateinit var status: TextView
  private lateinit var details: TextView
  private lateinit var message: TextView
  private lateinit var eventLog: TextView
  private lateinit var scanButton: Button
  private lateinit var reconnectButton: Button
  private lateinit var disconnectButton: Button
  private lateinit var refreshButton: Button
  private lateinit var photoButton: Button
  private lateinit var videoButton: Button
  private lateinit var audioButton: Button
  private lateinit var stopButton: Button
  private lateinit var pauseButton: Button
  private lateinit var libraryButton: Button
  private lateinit var devices: LinearLayout
  private lateinit var photo: ImageView
  private lateinit var recordingTitle: TextView
  private lateinit var recordingTime: TextView
  private lateinit var recordingStatus: TextView
  private lateinit var captureDefaultsStatus: TextView
  private lateinit var audioStatus: TextView
  private lateinit var syncStatus: TextView
  private lateinit var recentStatus: TextView
  private lateinit var toneButton: Button
  private lateinit var retryButton: Button
  private lateinit var connectionControls: LabUi.Disclosure
  private val mediaVolumeButtons = mutableListOf<Button>()
  private val callVolumeButtons = mutableListOf<Button>()
  private val observer: () -> Unit = { render() }
  private var deviceSignature = ""
  private var imagePath: String? = null
  private var pendingAction: (() -> Unit)? = null
  private var screenBound = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val page = LabUi.page(this, "Glasses Lab")
    val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    top.addView(LabUi.text(this, "Capture from your glasses", 14, LabUi.muted), LinearLayout.LayoutParams(0, -2, 1f))
    top.addView(LabUi.button(this, "Settings") { startActivity(Intent(this, RecordingSettingsActivity::class.java)) },
      LinearLayout.LayoutParams(-2, LabUi.dp(this, 48)))
    page.addView(top)

    val connection = LabUi.card(this, page)
    status = LabUi.text(this, "Connecting…", 18).apply { typeface = Typeface.DEFAULT_BOLD }
    details = LabUi.text(this, "Checking your glasses", 13, LabUi.muted)
    connection.addView(status)
    connection.addView(details)
    reconnectButton = LabUi.button(this, "Connect glasses", primary = true) {
      withBluetooth {
        if (controller.savedDeviceName != null) controller.reconnect()
        else { connectionControls.expand(true); controller.scan() }
      }
    }
    connection.addView(reconnectButton)
    message = LabUi.text(this, "", 13, LabUi.muted)
    connection.addView(message)

    page.addView(LabUi.text(this, "Capture", 20).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, LabUi.dp(this@MainActivity, 24), 0, LabUi.dp(this@MainActivity, 4)) })
    photoButton = captureButton("Photo", "Take a photo", LabUi.accent) { controller.takePhoto() }
    videoButton = captureButton("Video", "Record video with sound", LabUi.video) { controller.startVideo() }
    audioButton = captureButton("Audio", "Record audio from the glasses", LabUi.audio) { controller.startAudio() }
    LabUi.row(this, page, photoButton, videoButton, audioButton)
    page.addView(LabUi.text(this, "Video with sound · Audio from the glasses mic", 12, LabUi.muted))
    captureDefaultsStatus = LabUi.text(this, "Loading capture defaults…", 13, LabUi.muted)
    page.addView(captureDefaultsStatus)

    val recording = LabUi.card(this, page)
    recordingTitle = LabUi.text(this, "Ready to capture", 17).apply { typeface = Typeface.DEFAULT_BOLD }
    recordingTime = LabUi.text(this, "", 34).apply { typeface = Typeface.MONOSPACE; visibility = View.GONE }
    recordingStatus = LabUi.text(this, "Video uses your recording defaults. Audio runs until you stop or reach your storage allowance.", 14, LabUi.muted)
    recording.addView(recordingTitle)
    recording.addView(recordingTime)
    recording.addView(recordingStatus)
    stopButton = LabUi.button(this, "Stop recording", primary = true, tint = LabUi.danger) { controller.stopRecordingOrTransfer() }
    pauseButton = LabUi.button(this, "Pause audio") { controller.pauseOrResumeAudio() }
    LabUi.row(this, recording, pauseButton, stopButton)

    val library = LabUi.card(this, page)
    library.addView(LabUi.text(this, "Your library", 20).apply { typeface = Typeface.DEFAULT_BOLD })
    recentStatus = LabUi.text(this, "Photos, video, and audio in one place", 14, LabUi.muted)
    library.addView(recentStatus)
    photo = ImageView(this).apply {
      contentDescription = "Latest photo. Open the library to view or share."
      scaleType = ImageView.ScaleType.CENTER_CROP
      visibility = View.GONE
      background = LabUi.shape(this@MainActivity, LabUi.surfaceRaised)
      clipToOutline = true
      setOnClickListener { startActivity(Intent(this@MainActivity, LibraryActivity::class.java)) }
    }
    library.addView(photo, LinearLayout.LayoutParams(-1, LabUi.dp(this, 156)).apply { topMargin = LabUi.dp(this@MainActivity, 8) })
    libraryButton = LabUi.button(this, "Open library") { startActivity(Intent(this, LibraryActivity::class.java)) }
    library.addView(libraryButton)
    syncStatus = LabUi.text(this, "", 13, LabUi.muted)
    library.addView(syncStatus)
    retryButton = LabUi.button(this, "Retry transfer") { withBluetooth { controller.recordings.retryAutomaticTransfers() } }
    library.addView(retryButton)

    connectionControls = LabUi.disclosure(this, page, "Connection")
    connectionControls.body.addView(LabUi.text(this, "Choose your glasses or manage the current connection.", 14, LabUi.muted))
    scanButton = LabUi.button(this, "Find glasses") { withBluetooth { controller.scan() } }
    connectionControls.body.addView(scanButton)
    devices = LabUi.column(this)
    connectionControls.body.addView(devices)
    refreshButton = LabUi.button(this, "Refresh status") { controller.refresh() }
    disconnectButton = LabUi.button(this, "Disconnect") { controller.disconnect() }
    LabUi.row(this, connectionControls.body, refreshButton, disconnectButton)
    connectionControls.body.addView(LabUi.button(this, "Bluetooth settings") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })

    val sound = LabUi.disclosure(this, page, "Audio & calls").body
    audioStatus = LabUi.text(this, "", 14, LabUi.muted)
    sound.addView(audioStatus)
    sound.addView(LabUi.text(this, "Calls use the Phone app and the glasses' call gesture. Enable Calls and Media audio in Android Bluetooth settings.", 13, LabUi.muted))
    fun volumeRow(label: String, buttons: MutableList<Button>, adjust: (Int) -> Unit) {
      sound.addView(LabUi.text(this, label, 14))
      val controls = listOf("−" to AudioManager.ADJUST_LOWER, "+" to AudioManager.ADJUST_RAISE).map { (symbol, direction) ->
        LabUi.button(this, symbol) { adjust(direction) }.apply { contentDescription = "$label ${if (direction == AudioManager.ADJUST_LOWER) "down" else "up"}"; buttons.add(this) }
      }
      LabUi.row(this, sound, *controls.toTypedArray())
    }
    volumeRow("Media volume", mediaVolumeButtons) { controller.adjustMediaVolume(it) }
    volumeRow("Call volume", callVolumeButtons) { controller.adjustCallVolume(it) }
    toneButton = LabUi.button(this, "Play a quiet test tone") { controller.testAudio() }
    sound.addView(toneButton)
    sound.addView(LabUi.button(this, "Refresh audio connections") { withBluetooth { controller.refreshAudio() } })

    val diagnostics = LabUi.disclosure(this, page, "Diagnostics").body
    eventLog = LabUi.text(this, "", 12, LabUi.muted).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
    diagnostics.addView(eventLog)
    page.addView(LabUi.text(this, "Glasses Lab · Your phone, your recordings", 12, LabUi.muted))
    if (savedInstanceState?.getBoolean("connection_expanded") == true) connectionControls.expand(true)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putBoolean("connection_expanded", connectionControls.expanded)
    super.onSaveInstanceState(outState)
  }

  private fun captureButton(title: String, description: String, tint: Int, action: () -> Unit) =
    LabUi.button(this, title, primary = true, tint = tint, action = action).apply {
      contentDescription = description
      textSize = 17f
      minHeight = LabUi.dp(this@MainActivity, 88)
      minimumHeight = minHeight
    }

  override fun onCompanionReady() {
    screenBound = true
    controller.observe(observer)
    controller.refreshAudio()
    render()
    consumeShortcut()
  }
  override fun onCompanionUnavailable() { screenBound = false; controller.unobserve(observer) }
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (screenBound) consumeShortcut()
  }
  private fun consumeShortcut() {
    val command = intent.action
    if (command in listOf(QuickControls.PHOTO, QuickControls.VIDEO, QuickControls.AUDIO)) {
      intent.action = Intent.ACTION_MAIN
      controller.quickAction(command!!)
    }
  }
  override fun onDestroy() { scope.cancel(); super.onDestroy() }

  private fun render() {
    if (isFinishing || isDestroyed) return
    val state = controller.glasses
    val recording = controller.recordings
    status.text = when {
      state.ready -> "Connected & ready"
      state.connected -> "Waiting for glasses…"
      controller.scanning -> "Finding glasses…"
      state.connection.isBusy -> "Connecting…"
      else -> "Glasses disconnected"
    }
    status.setTextColor(if (state.ready) LabUi.accent else LabUi.ink)
    details.text = if (state is GlassesRuntimeState.Connected) listOfNotNull(
      state.device.bluetoothName, state.battery.level?.let { "$it% battery" }).joinToString(" · ")
    else controller.savedDeviceName ?: "Connect your glasses to start capturing"
    val idle = !state.connected && !state.connection.isBusy && !controller.scanning
    LabUi.enabled(scanButton, idle)
    reconnectButton.visibility = if (state.ready || state.connection.isBusy || state.connected || controller.scanning) View.GONE else View.VISIBLE
    reconnectButton.text = if (controller.savedDeviceName != null) "Reconnect glasses" else "Connect glasses"
    LabUi.enabled(reconnectButton, idle)
    LabUi.enabled(disconnectButton, (controller.wantsConnection || state.connected || state.connection.isBusy || controller.scanning) && !recording.busy)
    LabUi.enabled(refreshButton, state.ready)
    LabUi.enabled(photoButton, controller.canTakePhoto)
    LabUi.enabled(videoButton, controller.canStartVideo)
    LabUi.enabled(audioButton, controller.canRecord)
    val defaults = controller.defaults
    captureDefaultsStatus.text = "Photos · ${defaults.photo.summary}" +
      (if (defaults.photoApplied) "" else "\n${defaults.photoStatus}") +
      "\nVideo · ${defaults.video.summary}" +
      (if (defaults.video.offloadToPhone) "\n${recording.phoneReadinessMessage}" else "") +
      (if (defaults.videoApplied) "" else "\n${defaults.videoStatus}")
    val active = !recording.session.idle
    val audioCapture = recording.session.kind == RecordingSession.Kind.AUDIO
    recordingTitle.text = when {
      controller.audio.callActive -> "Call in progress"
      controller.capturing -> "Receiving photo…"
      active && recording.paused -> if (recording.session.phase == RecordingSession.Phase.WAITING) "Waiting for glasses" else "Audio paused"
      active -> if (audioCapture) "Recording audio" else if (recording.recordingToPhone) "Recording video to phone" else "Recording video"
      recording.downloading -> "Saving to phone"
      else -> "Ready to capture"
    }
    recordingTime.visibility = if (active) View.VISIBLE else View.GONE
    recordingTime.text = LabUi.duration(if (audioCapture) recording.capturedSeconds else recording.elapsedSeconds)
    recordingStatus.text = when {
      controller.audio.callActive -> "Recording is paused or stopped while you take the call."
      controller.capturing -> "Keep the glasses connected while the photo arrives."
      active || recording.downloading -> recording.message + if (audioCapture && active) "\n${LabUi.duration(recording.elapsedSeconds)} elapsed · timer shows audio saved" else ""
      else -> recording.message.takeUnless { it == "Video includes sound. Audio-only uses the glasses microphone." }
        ?: if (defaults.video.offloadToPhone) recording.phoneReadinessMessage
          else "${PhoneVideoPlan.durationDescription(defaults.video.maxMinutes)} Video saves on the glasses. Audio runs until you stop or reach your storage allowance."
    }
    stopButton.visibility = if (active || recording.downloading) View.VISIBLE else View.GONE
    stopButton.text = if (active) "Stop recording" else "Cancel transfer"
    LabUi.enabled(stopButton, if (active) recording.canStop else recording.downloading)
    pauseButton.visibility = if (audioCapture) View.VISIBLE else View.GONE
    pauseButton.text = if (recording.paused) "Resume audio" else "Pause audio"
    LabUi.enabled(pauseButton, recording.canStop && (!recording.paused || (state.ready && !controller.audio.callActive)))
    LabUi.enabled(libraryButton, !controller.capturing)
    recentStatus.text = if (recording.audioSessions.isNotEmpty()) "Latest audio · ${LabUi.date(recording.audioSessions.first().createdAt)}" else "Photos, video, and audio in one place"
    syncStatus.text = recording.mediaWifiMessage + "\n" + recording.syncMessage
    syncStatus.visibility = if (recording.syncMessage.isBlank()) View.GONE else View.VISIBLE
    retryButton.text = if (recording.mediaWifiReady) "Sync now" else "Connect media Wi-Fi"
    retryButton.visibility = if (state.ready && !recording.downloading) View.VISIBLE else View.GONE
    LabUi.enabled(retryButton, controller.canCapture || (recording.recordingToPhone && !recording.downloading))
    val sound = controller.audio
    audioStatus.text = "Media audio: ${if (sound.mediaConnected) "connected" else "not connected"} · ${sound.mediaVolume}/${sound.mediaMax}\n" +
      "Calls: ${if (sound.callsConnected) "connected" else "not connected"} · ${sound.callVolume}/${sound.callMax}"
    mediaVolumeButtons.forEach { LabUi.enabled(it, sound.mediaConnected) }
    callVolumeButtons.forEach { LabUi.enabled(it, sound.callsConnected) }
    LabUi.enabled(toneButton, sound.mediaConnected && !sound.callActive && !recording.busy && !controller.testingAudio)
    message.text = controller.message
    message.visibility = if (state.ready && controller.message == "Connected. Ready for controls.") View.GONE else View.VISIBLE
    val version = (state as? GlassesRuntimeState.Connected)?.device?.appVersion
    eventLog.text = (version?.let { "ASG $it\n\n" } ?: "") + controller.events.ifBlank { "No recent events." }
    val signature = controller.devices.joinToString { "${it.id}:${it.rssi}" } + state.connection.value
    if (signature != deviceSignature) {
      deviceSignature = signature
      devices.removeAllViews()
      if (!state.connected && !state.connection.isBusy) controller.devices.forEach { device ->
        devices.addView(LabUi.button(this, device.name) { withBluetooth { controller.connect(device) } })
      }
    }
    val file = controller.photoFile
    if (file != null && file.absolutePath != imagePath) {
      imagePath = file.absolutePath
      scope.launch {
        val bitmap = withContext(Dispatchers.IO) {
          runCatching {
            val publisher = (application as LabApplication).media
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            publisher.openInput(file).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
            publisher.openInput(file).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
          }.getOrNull()
        }
        if (bitmap != null && imagePath == file.absolutePath) {
          photo.setImageBitmap(bitmap)
          photo.visibility = View.VISIBLE
        }
      }
    }
  }

  private fun withBluetooth(action: () -> Unit) {
    val required = CompanionPermissions.required()
    val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isNotEmpty()) {
      pendingAction = action
      val requested = missing.toMutableList()
      if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
      requestPermissions(requested.toTypedArray(), 1)
      return
    }
    val adapter = getSystemService(BluetoothManager::class.java)?.adapter
    if (adapter?.isEnabled != true) {
      pendingAction = action
      startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2)
      return
    }
    action()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == 1) {
      val callback = pendingAction
      pendingAction = null
      val essentialDenied = permissions.indices.any { permissions[it] != Manifest.permission.POST_NOTIFICATIONS && grantResults.getOrNull(it) != PackageManager.PERMISSION_GRANTED }
      if (essentialDenied) controller.reportError("Allow Nearby devices and Location to scan. You can change this in Android app settings.")
      else callback?.let { withBluetooth(it) }
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == 2) {
      val callback = pendingAction
      pendingAction = null
      if (resultCode == RESULT_OK) callback?.let { withBluetooth(it) }
      else controller.reportError("Turn on Bluetooth to connect to your glasses.")
    }
  }

}

