package dev.ross.glasses

import android.content.Context
import android.util.Log
import com.mentra.bluetoothsdk.*
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*

/** Owns the phone session. Deliberately exposes no update, reset, or shell commands. */
class GlassesController(
  private val context: Context,
  private val ensureRunning: () -> Unit,
  private val serviceChanged: () -> Unit,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val preferences = context.getSharedPreferences("companion", Context.MODE_PRIVATE)
  private var sdk: MentraBluetoothSdk? = null
  private var scanSession: ScanSession? = null
  private var captureJob: Job? = null
  private var receiver: LocalPhotoReceiver? = null
  private var receivedRequestId: String? = null
  private val capture = PhotoSession()
  private val photoDirectory = File(context.filesDir, "photos")
  private val eventBuffer = ArrayDeque<String>()
  private var reconnectJob: Job? = null
  private var defaultsAwaitingReplay = false
  var wantsConnection = preferences.getBoolean("stay_connected", false)
    private set

  private val observers = mutableSetOf<() -> Unit>()
  val recordings = RecordingController(context, ::client, { glasses }, ::changed, ::record,
    (context.applicationContext as LabApplication).recordings, ensureRunning, ::audioCallActive)
  val defaults = CaptureDefaultsSyncController(recordings.settings.photoDefaults(), recordings.settings.videoDefaults(),
    object : CaptureDefaultsTransport {
      override fun cache(photo: PhotoCaptureProfile, video: VideoRecordingProfile, revision: String) {
        sdk?.cacheButtonPhotoSize(photo.size)
        sdk?.cacheRecordingProfile(video.sdkProfile(revision))
      }
      override suspend fun photo(profile: PhotoCaptureProfile) = client().setButtonPhotoSize(profile.size)
      override suspend fun video(profile: VideoRecordingProfile, revision: String) = client().setRecordingProfile(profile.sdkProfile(revision))
    }, scope, ::changed)
  val audio: BluetoothAudio = BluetoothAudio(context, { preferences.getString("address", null) }, { savedDeviceName }) {
    if (audioCallActive()) recordings.onCallStarted()
    changed()
  }
  var testingAudio = false
    private set
  fun observe(observer: () -> Unit) { observers.add(observer) }
  fun unobserve(observer: () -> Unit) { observers.remove(observer) }
  private fun audioCallActive(): Boolean = audio.callActive
  var glasses: GlassesRuntimeState = GlassesRuntimeState.Disconnected()
    private set
  var devices: List<Device> = emptyList()
    private set
  var scanning = false
    private set
  var message = "Scan for your glasses or reconnect to the saved device."
    private set
  var photoFile: File? = photoDirectory.listFiles()?.filter { it.extension == "jpg" }?.maxByOrNull { it.name }
    private set
  val events: String get() = eventBuffer.joinToString("\n")
  val capturing: Boolean get() = capture.activeId != null
  val canCapture: Boolean get() = glasses.ready && !capturing && !recordings.busy && !audio.callActive && !testingAudio
  val canRecord: Boolean get() = canCapture
  val canTakePhoto: Boolean get() = canCapture && defaults.photoApplied
  val canStartVideo: Boolean get() = canCapture && defaults.videoApplied
  val savedDeviceName: String? get() = preferences.getString("name", null)
  val savedDeviceAddress: String? get() = preferences.getString("address", null)

  private val listener = object : MentraBluetoothSdkCallback() {
    override fun onGlassesChanged(value: GlassesRuntimeState) {
      val previous = glasses
      glasses = value
      if (!value.ready) { defaultsAwaitingReplay = false; defaults.connection(null) }
      else if (!previous.ready && !defaultsAwaitingReplay) defaults.connection(savedDeviceAddress ?: savedDeviceName ?: "connected")
      if (capture.ready && !value.ready) {
        captureJob?.cancel()
        message = "Connection lost. Reconnect before taking another photo."
      }
      capture.setReady(value.ready)
      if (previous.ready != value.ready) recordings.onConnection(value.ready)
      if (value.ready && !previous.ready) {
        message = "Connected. Ready for controls."
        reconnectJob?.cancel()
      }
      if (!value.ready && wantsConnection) scheduleReconnect()
      if (previous.connection != value.connection || previous.ready != value.ready) {
        record(if (value.ready) "Glasses ready" else "Connection: ${value.connection.value}")
      }
      changed()
    }

    override fun onScanChanged(scan: BluetoothScanState) {
      scanning = scan.active
      devices = scan.devices.filter { it.model == DeviceModel.MENTRA_LIVE }
      changed()
    }

    override fun onDefaultDeviceChanged(device: Device?) {
      if (device?.address != savedDeviceAddress || device?.name != savedDeviceName) defaults.connection(null)
      if (device != null) {
        preferences.edit().putString("name", device.name).putString("address", device.address)
          .putString("id", device.id).apply()
      }
      audio.start()
      changed()
    }

    override fun onSdkStateChanged(sdk: PhoneSdkRuntimeState) = recordings.onSdkState(sdk)
    override fun onMicPcm(event: MicPcmEvent) = recordings.onPcm(event)
    override fun onVideoRecordingStatus(event: VideoRecordingStatusEvent) = recordings.onVideoStatus(event)

    override fun onPhotoStatus(event: PhotoStatusEvent) {
      if (capture.accepts(event.requestId)) {
        message = "Photo: ${event.status.replace('_', ' ')}"
        record(message)
      }
    }

    override fun onButtonPress(event: ButtonPressEvent) {
      record("Button ${event.buttonId}: ${event.pressType}")
      recordings.scheduleAutomaticTransfers()
    }
    override fun onError(error: BluetoothError) = reportError(error.message)
    override fun onVersionInfo(event: VersionInfoResult) { changed() }
    override fun onRawEvent(eventName: String, values: Map<String, Any>) {
      when (eventName) {
        "glasses_session_changed" -> { defaultsAwaitingReplay = true; defaults.connection(null) }
        "glasses_settings_replayed" -> {
          defaultsAwaitingReplay = false
          // Any automatic replay supersedes previous confirmation, even an unsolicited readiness.
          defaults.connection(null)
          if (glasses.ready) defaults.connection(savedDeviceAddress ?: savedDeviceName ?: "connected")
        }
      }
    }
  }

  private fun client(): MentraBluetoothSdk {
    sdk?.let { return it }
    return MentraBluetoothSdk.create(
      context,
      MentraBluetoothSdkConfig(analytics = BluetoothSdkAnalyticsConfig.disabled()),
      listener,
    ).also { instance ->
      sdk = instance
      instance.cacheButtonPhotoSize(defaults.photo.size)
      instance.cacheRecordingProfile(defaults.video.sdkProfile(defaults.videoRevision))
      savedDeviceName?.let { name ->
        instance.setDefaultDevice(Device(
          model = DeviceModel.MENTRA_LIVE,
          name = name,
          address = preferences.getString("address", null),
          id = preferences.getString("id", null) ?: name,
        ))
      }
    }
  }

  fun scan() = action {
    check(!glasses.connected && !glasses.connection.isBusy) { "Disconnect before scanning." }
    scanSession?.stop()
    wantConnection(false)
    message = "Looking for Mentra Live glasses nearby…"
    scanSession = client().scan(DeviceModel.MENTRA_LIVE, object : MentraBluetoothScanCallback() {
      override fun onComplete(devices: List<Device>) {
        message = if (devices.isEmpty()) "No glasses found. Check that they are on and available."
          else "Tap your glasses to connect."
        changed()
      }
    }, 12_000)
    changed()
  }

  fun connect(device: Device) = action {
    scanSession?.stop()
    wantConnection(true)
    try { ensureRunning() } catch (error: Exception) { wantConnection(false); throw error }
    message = "Connecting to ${device.name}… Accept Android's pairing prompt if one appears."
    client().connect(device)
    scheduleReconnect()
    changed()
  }

  fun reconnect() = action {
    scanSession?.stop()
    wantConnection(true)
    try { ensureRunning() } catch (error: Exception) { wantConnection(false); throw error }
    message = "Reconnecting to ${savedDeviceName ?: "your glasses"}…"
    client().connectDefault()
    scheduleReconnect()
    changed()
  }

  fun disconnect() = action {
    check(!recordings.busy) { "Stop recording or cancel the download before disconnecting." }
    defaults.connection(null)
    wantConnection(false)
    scanSession?.stop()
    capture.setReady(false)
    captureJob?.cancel()
    sdk?.disconnect()
    receiver?.close()
    receiver = null
    message = "Disconnected. Your photos are saved in this app."
    record("Disconnected by user")
  }

  private fun wantConnection(value: Boolean) {
    wantsConnection = value
    preferences.edit().putBoolean("stay_connected", value).apply()
    if (!value) { reconnectJob?.cancel(); reconnectJob = null }
  }

  fun restoreConnection() {
    if (wantsConnection && savedDeviceName != null && !glasses.ready) scheduleReconnect()
  }

  /** Restore the user's connection choice from a permitted foreground entry point. */
  fun onAppVisible() = action {
    if (wantsConnection && savedDeviceName != null) {
      ensureRunning()
      restoreConnection()
    }
  }

  private fun scheduleReconnect() {
    if (!wantsConnection || reconnectJob?.isActive == true) return
    reconnectJob = scope.launch {
      var backoff = 2000L
      while (wantsConnection && !glasses.ready) {
        delay(backoff)
        if (!wantsConnection || glasses.ready) break
        try {
          if (!glasses.connection.isBusy && !glasses.connected) client().connectDefault()
          withTimeoutOrNull(45_000) { while (!glasses.ready && wantsConnection) delay(1000) }
          if (wantsConnection && !glasses.ready) client().cancelConnectionAttempt()
        } catch (error: Exception) {
          if (error is CancellationException) throw error
          message = "Waiting to reconnect: ${error.message}"
          changed()
        }
        backoff = (backoff * 2).coerceAtMost(30_000)
      }
    }
  }

  fun refresh() {
    scope.launch {
      try {
        client().requestVersionInfo()
        record("Glasses status refreshed")
      } catch (error: Exception) { reportError(error.message ?: "Status request failed") }
    }
  }

  fun takePhoto() = action {
    check(canCapture) { "Finish the recording, call, or playback test before taking a photo." }
    check(defaults.photoApplied) { defaults.photoStatus }
    val reserve = recordings.settings.policy().reserveBytes
    check(context.filesDir.usableSpace - reserve > 8L * 1024 * 1024) { "Phone space is low. Free space before taking another photo." }
    ensureRunning()
    val profile = defaults.photo
    val requestId = "lab-${UUID.randomUUID()}"
    capture.begin(requestId)
    message = "Taking a photo…"
    changed()
    captureJob = scope.launch {
      try {
        client().requestPhoto(profile.request(requestId))
        message = "Photo captured. Saving to phone media automatically…"
        recordings.scheduleAutomaticTransfers()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        message = when {
          receivedRequestId == requestId -> "Photo saved. Glasses acknowledgement was not received."
          error is BluetoothSdkException && error.code == "request_timeout" ->
            "Photo was not confirmed. It may be saved on the glasses; checking completed media."
          else -> "Photo failed: ${error.message ?: "unknown error"}"
        }
        record(message)
      } finally {
        capture.finish(requestId)
        recordings.scheduleAutomaticTransfers()
        changed()
      }
    }
  }

  fun reportError(message: String) {
    this.message = message
    record(message)
  }

  fun startVideo() = action {
    check(canRecord) { "Connect and finish the current capture, playback, or call first." }
    check(defaults.videoApplied) { defaults.videoStatus }
    recordings.startVideo(defaults.video, defaults.videoRevision)
  }

  fun savePhotoDefaults(profile: PhotoCaptureProfile) {
    defaults.savePhoto(recordings.settings.savePhotoDefaults(profile))
  }
  fun saveVideoDefaults(profile: VideoRecordingProfile) {
    defaults.saveVideo(recordings.settings.saveVideoDefaults(profile))
  }
  fun retryPhotoDefaults() = defaults.retryPhoto()
  fun retryVideoDefaults() = defaults.retryVideo()

  fun startAudio() = action {
    check(canRecord) { "Connect and finish the current capture, playback, or call first." }
    recordings.startAudio()
  }

  fun stopRecordingOrTransfer() = action {
    if (!recordings.session.idle) recordings.stop() else recordings.cancelTransfer()
  }
  fun pauseOrResumeAudio() = action {
    if (recordings.paused) recordings.resumeAudio() else recordings.pauseAudio()
  }

  fun refreshAudio() = action { audio.start() }
  fun adjustMediaVolume(direction: Int) = action { audio.adjustMedia(direction) }
  fun adjustCallVolume(direction: Int) = action { audio.adjustCall(direction) }

  fun testAudio() = action {
    check(!recordings.busy && !capturing && !testingAudio) { "Finish recording before testing playback." }
    testingAudio = true
    changed()
    scope.launch {
      try { audio.playTestTone(); message = "Test tone routed to the glasses."; record(message) }
      catch (error: Exception) { reportError(error.message ?: "Audio playback failed") }
      finally { testingAudio = false; changed() }
    }
  }

  private fun action(block: () -> Unit) {
    try { block() } catch (error: Exception) { reportError(error.message ?: "Operation failed") }
  }

  private fun record(text: String) {
    Log.i("GlassesLab", text)
    val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    eventBuffer.addFirst("$time  $text")
    while (eventBuffer.size > 30) eventBuffer.removeLast()
    changed()
  }

  fun close() {
    defaults.close()
    reconnectJob?.cancel()
    scanSession?.stop()
    recordings.close()
    receiver?.close()
    audio.close()
    runCatching { sdk?.disconnect() }
    sdk?.close()
    scope.cancel()
    observers.clear()
  }
  private fun changed() { serviceChanged(); observers.toList().forEach { it() } }

  fun backgroundTick() {
    recordings.mediaFiles.firstOrNull { it.kind == MediaKind.PHOTO }?.file?.let { latest ->
      if (photoFile != latest) { photoFile = latest; changed() }
    }
    recordings.updateRecordingReadiness(defaults.video, defaults.videoRevision,
      wantsConnection && defaults.videoApplied && !capturing && !testingAudio && CompanionPermissions.granted(context))
    if (wantsConnection && !capturing && !testingAudio) recordings.maybeAutomaticTransfers()
  }

  /** User-triggered controls never defer capture until a later reconnection. */
  fun quickAction(command: String) {
    if ((command == QuickControls.AUDIO && recordings.session.kind == RecordingSession.Kind.AUDIO) ||
      (command == QuickControls.VIDEO && recordings.session.kind == RecordingSession.Kind.VIDEO)) {
      stopRecordingOrTransfer()
      return
    }
    if (!glasses.ready || !recordings.recovered) {
      reportError("Open Glasses Lab and connect before using capture shortcuts.")
      android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
      return
    }
    when (command) {
      QuickControls.PHOTO -> takePhoto()
      QuickControls.VIDEO -> if (recordings.session.kind == RecordingSession.Kind.VIDEO) stopRecordingOrTransfer() else startVideo()
      QuickControls.AUDIO -> if (recordings.session.kind == RecordingSession.Kind.AUDIO) stopRecordingOrTransfer() else startAudio()
    }
  }
}
