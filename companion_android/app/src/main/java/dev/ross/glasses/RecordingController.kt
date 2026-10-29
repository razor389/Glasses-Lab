package dev.ross.glasses

import android.content.Context
import android.os.SystemClock
import com.mentra.bluetoothsdk.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates local audio files, glasses video commands, and recoverable video downloads. */
class RecordingController(
  private val context: Context,
  private val client: () -> MentraBluetoothSdk,
  private val glasses: () -> GlassesRuntimeState,
  private val changed: () -> Unit,
  private val log: (String) -> Unit,
  val repository: RecordingRepository,
  private val ensureRunning: () -> Unit,
  private val callActive: () -> Boolean,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val preferences = context.getSharedPreferences("recordings", Context.MODE_PRIVATE)
  val directory = repository.directory
  val settings = RecordingSettings(context, repository)
  val session = RecordingSession()
  val videoSessions = (context.applicationContext as LabApplication).videoSessions
  private var activePhoneVideoMinutes: Int? = if (preferences.contains("active_video"))
    preferences.getInt("active_phone_video_minutes", -1).takeIf { it in PhoneVideoPlan.DURATIONS } else null
  val recordingToPhone get() = session.kind == RecordingSession.Kind.VIDEO &&
    (activeVideoProfile?.offloadToPhone == true || activePhoneVideoMinutes != null)
  var activeVideoProfile: VideoRecordingProfile? = if (preferences.contains("active_video"))
    VideoRecordingProfile.decode(preferences.getString("active_video_profile", null)) else null
    private set
  val activeVideoLimitDescription: String get() = activePhoneVideoMinutes?.let(PhoneVideoPlan::durationDescription)
    ?: activeVideoProfile?.let { PhoneVideoPlan.durationDescription(it.maxMinutes) }
    ?: "The previous video's time limit is unknown."
  private val queued = preferences.getStringSet("videos", emptySet())!!.toMutableSet()
  private var audio: PcmRecording? = null
  private var micMode: MicMode? = null
  private var previousVad = true
  private var startJob: Job? = null
  private var stopJob: Job? = null
  private var ticker: Job? = null
  private var transferJob: Job? = null
  private var publicationJob: Job? = null
  private var publicationRequested = false
  private var nextSyncAt = SystemClock.elapsedRealtime() + 10_000
  private val media = (context.applicationContext as LabApplication).media
  private val mediaConnection = GlassesMediaConnection(context, scope, client, glasses, changed, log) {
    (context.applicationContext as LabApplication).isUiVisible
  }
  private var syncRequested = true
  private var approvalRequested = false
  private var transferCancelled = false
  private var phoneGrant = PhoneRecordingGrant.decode(preferences.getString("phone_recording_grant", null))
  private val grantMutex = Mutex()
  private var grantJob: Job? = null
  private var nextGrantAt = 0L
  private var nextStatusAt = 0L
  private var profileReady = false
  private var phoneGrantReadyUntil = 0L
  var phoneReadinessMessage = "Connect media Wi-Fi to offload recordings from the app or glasses button."
    private set
  val phoneRecordingReady get() = mediaWifiReady && SystemClock.elapsedRealtime() < phoneGrantReadyUntil
  val mediaWifiReady get() = mediaConnection.isReady
  val mediaWifiMessage get() = mediaConnection.message
  var mediaFiles: List<PublishedMedia> = emptyList()
    private set
  var publishing = false
    private set
  var syncMessage = "Completed captures save to phone media automatically."
    private set
  private var startedAt = 0L
  private var lastAudioAt = 0L
  private var completedAudioBytes = 0L
  private var interruptedByCall = false
  var recovered = false
    private set
  var audioSessions: List<SavedAudioSession> = emptyList()
    private set
  var libraryLimit = 100
    private set
  var message = "Video includes sound. Audio-only uses the glasses microphone."
    private set
  var elapsedSeconds = 0L
    private set
  var downloading = false
    private set
  val pendingVideos get() = queued.sortedDescending()
  private val needsPhoneVideoSync get() = recordingToPhone || queued.any {
    preferences.getBoolean("phone_video:$it", false) && preferences.getInt("video_segments:$it", -1) >= 0
  }
  fun pendingVideoDescription(id: String): String = if (preferences.getBoolean("phone_video:$id", false) &&
    preferences.getInt("video_segments:$id", -1) < 0)
    "Final segment count is unknown after interruption. Sync can save completed parts; completion needs review."
  else "Waiting for verified phone copies."
  val busy get() = !session.idle || startJob?.isActive == true || downloading || publishing || !recovered
  val paused get() = session.phase in listOf(RecordingSession.Phase.PAUSED, RecordingSession.Phase.WAITING)
  val needsWakeLock get() = downloading || publishing || (!session.idle && !paused)
  val capturedBytes get() = completedAudioBytes + (audio?.bytes?.get() ?: 0)
  val capturedSeconds get() = capturedBytes / WavWriter.BYTES_PER_SECOND
  val canStop get() = !session.idle && stopJob?.isActive != true &&
    (session.kind == RecordingSession.Kind.AUDIO || glasses().ready)
  val localFiles get() = directory.listFiles()?.filter { it.extension in listOf("mp4", "wav") }?.sortedByDescending { it.lastModified() }.orEmpty()

  init {
    preferences.getString("active_video", null)?.let {
      session.restoreVideo(it)
      message = "A previous video may still be recording. Reconnect to finish it."
    }
    scope.launch {
      try {
        val restoredId = session.id
        val errors = withContext(Dispatchers.IO) {
          videoSessions.recover(restoredId)
          media.reconcile() + repository.recover()
        }
        reconcileSavedVideoQueue()
        recovered = true
        refreshLibrary()
        publishCompletedMedia()
        if (errors.isNotEmpty()) message = "Some interrupted files need attention: ${errors.first()}"
      } catch (error: Exception) { message = "Recording recovery failed; originals retained: ${error.message}" }
      changed()
    }
  }

  fun startAudio() {
    requireReady()
    updateRecordingReadiness(settings.videoDefaults().profile, settings.videoDefaults().revision.toString(), false)
    val id = "audio-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
    session.begin(RecordingSession.Kind.AUDIO, id)
    completedAudioBytes = 0
    interruptedByCall = false
    startedAt = SystemClock.elapsedRealtime()
    beginAudio(id, create = true)
  }

  fun resumeAudio() {
    check(paused && glasses().ready && !callActive()) { "Reconnect and finish any call before resuming." }
    session.resume()
    interruptedByCall = false
    beginAudio(session.id!!, create = false)
  }

  private fun beginAudio(id: String, create: Boolean) {
    // Resume/reconnect may still observe the previous capture's disabled VAD state.
    // Keep the setting from the start of this session until its final cleanup.
    if (create) previousVad = (glasses() as? GlassesRuntimeState.Connected)?.voiceActivityDetectionEnabled ?: true
    message = "Starting the glasses microphone…"
    startJob = scope.launch {
      try {
        ensureRunning()
        val guard = settings.guard()
        withContext(Dispatchers.IO) {
          guard.checkCapacity(44 + 32000L)
          if (create) repository.create(id, (glasses() as? GlassesRuntimeState.Connected)?.device?.bluetoothName ?: "Glasses")
          else repository.resume(id)
        }
        if (session.id != id || session.phase != RecordingSession.Phase.STARTING) return@launch
        val lastSequence = withContext(Dispatchers.IO) { repository.segments(id).lastOrNull()?.sequence ?: 0L }
        audio = PcmRecording(scope, repository.folder(id), guard::requireCapacity,
          { repository.commit(id, it); scope.launch { publishCompletedMedia() } },
          { error -> scope.launch { if (session.id == id) stopAudio(error.message ?: "Audio write failed.") } },
          initialSequence = lastSequence)
        lastAudioAt = SystemClock.elapsedRealtime()
        check(preferences.edit().putBoolean("mic_cleanup_pending", true)
          .putBoolean("mic_cleanup_vad", previousVad).commit()) { "Could not save microphone recovery state." }
        client().setVoiceActivityDetectionEnabled(false)
        client().setMicState(true, useGlassesMic = true)
        startTicker()
        refreshLibrary()
      } catch (error: Exception) { stopAudio(error.message ?: "Audio could not start.") }
      changed()
    }
    changed()
  }

  fun pauseAudio() {
    check(session.kind == RecordingSession.Kind.AUDIO && !paused)
    finishAudio("Paused by you.", pause = true)
  }

  fun onSdkState(state: PhoneSdkRuntimeState) {
    micMode = state.currentMic
    if (session.kind == RecordingSession.Kind.AUDIO && session.phase == RecordingSession.Phase.RECORDING && micMode != null && micMode != MicMode.GLASSES) {
      finishAudio("Glasses microphone became unavailable. Resume when ready.", pause = true)
    }
  }

  fun onPcm(event: MicPcmEvent) {
    if (session.kind != RecordingSession.Kind.AUDIO || paused || session.phase == RecordingSession.Phase.STOPPING || micMode != MicMode.GLASSES) return
    try {
      audio?.offer(event)
      lastAudioAt = SystemClock.elapsedRealtime()
      session.id?.let { id ->
        if (session.phase == RecordingSession.Phase.STARTING) {
          session.started(id)
          scope.launch(Dispatchers.IO) { runCatching { repository.markRecording(id) } }
        }
      }
      message = "Recording audio from the glasses."
    } catch (error: Exception) { stopAudio(error.message ?: "Audio recording failed.") }
  }

  fun startVideo(profile: VideoRecordingProfile, revision: String) {
    requireReady()
    check(CompanionPermissions.granted(context)) { "Restore the app's connection permissions before recording." }
    check(!profile.offloadToPhone || mediaWifiReady) { "Connect media Wi-Fi before offloading a recording." }
    profileReady = true
    message = "Starting video with sound…"
    startJob = scope.launch {
      var commandSent = false
      var id: String? = null
      try {
        ensureRunning()
        id = if (profile.offloadToPhone) preparePhoneGrant(profile, revision).sessionId else newVideoId()
        // A physical press can win while the app is preparing the same reservation.
        if (!session.idle) { check(session.id == id) { "Another recording has started." }; return@launch }
        beginVideo(id, profile)
        if (profile.offloadToPhone) withContext(Dispatchers.IO) { videoSessions.begin(id) }
        check(!callActive() && glasses().ready) { "Recording cancelled because a call or disconnection interrupted setup." }
        commandSent = true
        client().startVideoRecording(profile.request(id, revision))
        if (session.id == id) {
          session.started(id)
          message = "Recording video ${if (recordingToPhone) "to phone" else "with sound"}. $activeVideoLimitDescription"
          if (recordingToPhone) { syncRequested = true; nextSyncAt = 0 }
        }
      } catch (error: Exception) {
        if (id != null && session.id == id) {
          val rejected = !commandSent || (error is BluetoothSdkException && error.code in setOf("battery_low", "service_unavailable", "missing_request_id", "unsupported_device", "invalid_segmented_settings", "profile_unavailable", "profile_unsupported", "phone_not_ready", "grant_expired"))
          // A ready reservation could have been consumed by the button during the failed app start.
          // Retire it only after the glasses confirm it was unused; otherwise recover actual status.
          val definitelyStopped = rejected && (!profile.offloadToPhone || runCatching { revokeUnusedPhoneGrant(id) }.getOrDefault(false))
          if (definitelyStopped) {
            session.finish(id)
            activeVideoProfile = null
            activePhoneVideoMinutes = null
            preferences.edit().remove("active_video").remove("active_video_profile").remove("active_phone_video_minutes").apply()
            if (profile.offloadToPhone) withContext(Dispatchers.IO) { videoSessions.finish(id, "Video did not start: ${error.message}") }
            finishWork()
          } else {
            session.uncertain(id)
            // An app/button race may already have started this exact reserved session.
            client().requestVideoRecordingStatus()
          }
          message = "Video start was not confirmed: ${error.message}." +
            if (session.id == id) " Use Stop to check the camera." else ""
          log(message)
        } else { message = error.message ?: "Video could not start."; log(message) }
      } finally { changed() }
    }
    changed()
  }

  private fun newVideoId() = "labvideo-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"

  /** Persist the active snapshot before sending a start or acknowledging an unsolicited start. */
  private fun beginVideo(id: String, profile: VideoRecordingProfile) {
    session.begin(RecordingSession.Kind.VIDEO, id)
    if (!preferences.edit().putString("active_video", id).putString("active_video_profile", profile.encode())
        .remove("active_phone_video_minutes").commit()) {
      session.finish(id)
      error("Could not save recording recovery state.")
    }
    activeVideoProfile = profile
    activePhoneVideoMinutes = null
    phoneGrantReadyUntil = 0
    startedAt = SystemClock.elapsedRealtime()
    startTicker()
  }

  private fun offload(connection: GlassesMediaConnection.Session) = MediaOffloadClient(context,
    connection.network, connection.hotspot.localIp, connection.token, media, settings)

  private fun savePhoneGrant(value: PhoneRecordingGrant?) {
    check(preferences.edit().apply {
      if (value == null) remove("phone_recording_grant") else putString("phone_recording_grant", value.encode())
    }.commit()) { "Could not save phone recording reservation." }
    phoneGrant = value
    phoneGrantReadyUntil = 0
  }

  private suspend fun revokeUnusedPhoneGrant(id: String): Boolean = grantMutex.withLock {
    val grant = phoneGrant?.takeIf { it.sessionId == id } ?: return@withLock false
    val connection = mediaConnection.acquire(allowPrompt = false)
    try { withContext(Dispatchers.IO) { offload(connection).recordingReady(grant, 0) } }
    catch (consumed: PhoneRecordingGrantConsumed) { return@withLock false }
    savePhoneGrant(null)
    true
  }

  private suspend fun preparePhoneGrant(profile: VideoRecordingProfile, revision: String): PhoneRecordingGrant = grantMutex.withLock {
    check(profileReady && glasses().ready && !callActive() && session.idle && CompanionPermissions.granted(context)) { "Recording is not ready." }
    check(queued.none { preferences.getBoolean("phone_video:$it", false) }) { "Finish saving the previous video before recording to phone again." }
    val connection = mediaConnection.acquire(allowPrompt = false)
    val transport = offload(connection)
    val policy = settings.policy()
    val plan = try {
      withContext(Dispatchers.IO) {
        media.reconcile()
        PhoneVideoPlan.create(profile.maxMinutes, policy.remaining(repository.usedStorageBytes(), directory.usableSpace))
      }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
      phoneGrant?.let { grant ->
        withContext(Dispatchers.IO) { transport.recordingReady(grant, 0) }
        savePhoneGrant(null)
      }
      throw error
    }
    val previous = phoneGrant
    if (previous != null && (previous.profile != profile || previous.revision != revision || previous.maxSessionBytes > plan.maxSessionBytes)) {
      try { withContext(Dispatchers.IO) { transport.recordingReady(previous, 0) } }
      catch (consumed: PhoneRecordingGrantConsumed) { client().requestVideoRecordingStatus(previous.sessionId); throw consumed }
      savePhoneGrant(null)
    }
    val grant = phoneGrant ?: PhoneRecordingGrant(UUID.randomUUID().toString(), newVideoId(), revision, profile, plan.maxSessionBytes)
      .also(::savePhoneGrant)
    try { withContext(Dispatchers.IO) { transport.recordingReady(grant, 90) } }
    catch (consumed: PhoneRecordingGrantConsumed) { client().requestVideoRecordingStatus(grant.sessionId); throw consumed }
    val desired = settings.videoDefaults()
    if (!profileReady || callActive() || !glasses().ready || !CompanionPermissions.granted(context) || settings.policy() != policy || desired.profile != profile || desired.revision.toString() != revision ||
      (!session.idle && session.id != grant.sessionId)) {
      withContext(Dispatchers.IO) { transport.recordingReady(grant, 0) }
      savePhoneGrant(null)
      error("Recording settings or phone availability changed during setup.")
    }
    phoneGrantReadyUntil = if (session.idle) SystemClock.elapsedRealtime() + 80_000 else 0
    phoneReadinessMessage = "Ready to offload from the app or glasses button."
    grant
  }

  fun onStorageSettingsChanged() {
    settings.guard().invalidate()
    phoneGrantReadyUntil = 0
    nextGrantAt = 0
    val desired = settings.videoDefaults()
    updateRecordingReadiness(desired.profile, desired.revision.toString(), profileReady)
  }

  /** Called by the service even with the screen off; never opens a new Wi-Fi approval prompt. */
  fun updateRecordingReadiness(profile: VideoRecordingProfile, revision: String, allowed: Boolean) {
    profileReady = allowed
    val now = SystemClock.elapsedRealtime()
    if (glasses().ready && now >= nextStatusAt) {
      nextStatusAt = now + 15_000
      runCatching { client().requestVideoRecordingStatus() }.onFailure { log("Video status check: ${it.message}") }
    }
    val eligible = allowed && profile.offloadToPhone && recovered && session.idle && !callActive() && mediaWifiReady
    if (!eligible) phoneGrantReadyUntil = 0
    if (grantJob?.isActive == true || startJob?.isActive == true || session.kind == RecordingSession.Kind.VIDEO) return
    if (eligible && now < nextGrantAt) return
    if (!eligible && (phoneGrant == null || !mediaWifiReady)) {
      if (profile.offloadToPhone && !mediaWifiReady) phoneReadinessMessage = "Connect media Wi-Fi to offload from the app or glasses button."
      return
    }
    nextGrantAt = now + 30_000
    grantJob = scope.launch {
      try {
        if (eligible) preparePhoneGrant(profile, revision)
        else grantMutex.withLock {
          val grant = phoneGrant ?: return@withLock
          val connection = mediaConnection.acquire(allowPrompt = false)
          withContext(Dispatchers.IO) { offload(connection).recordingReady(grant, 0) }
          savePhoneGrant(null)
        }
      } catch (consumed: PhoneRecordingGrantConsumed) {
        phoneGrantReadyUntil = 0
        phoneGrant?.let { client().requestVideoRecordingStatus(it.sessionId) }
      } catch (error: Exception) {
        phoneGrantReadyUntil = 0
        phoneReadinessMessage = "Offloading is waiting: ${error.message}"
      } finally { changed() }
    }
  }

  fun stop() {
    if (session.kind == RecordingSession.Kind.AUDIO) { stopAudio("Audio saved on this phone."); return }
    val id = session.id ?: return
    if (stopJob?.isActive == true) return
    stopJob = scope.launch {
      startJob?.join()
      if (session.id != id) return@launch
      if (!glasses().ready) {
        session.uncertain(id)
        message = "Reconnect to stop the video. $activeVideoLimitDescription"
        changed()
        return@launch
      }
      session.stopping()
      message = "Finishing video…"
      changed()
      try {
        val stopped = client().stopVideoRecording(id)
        finishVideo(id, stopped.segmentCount, stopped.stopReason)
      } catch (error: Exception) {
        if (error is BluetoothSdkException && error.code == "not_recording") {
          val unused = recordingToPhone && runCatching { revokeUnusedPhoneGrant(id) }.getOrDefault(false)
          finishVideo(id, if (unused) 0 else null, if (unused) "Recording did not start." else null)
        }
        else if (session.id == id) {
          session.uncertain(id)
          message = "Stop was not confirmed: ${error.message}. Reconnect and retry Stop."
          log(message)
        }
      } finally { changed() }
    }
  }

  private fun stopAudio(reason: String) = finishAudio(reason)

  private fun finishAudio(reason: String, pause: Boolean = false, waiting: Boolean = false) {
    val id = session.id ?: return
    if (session.kind != RecordingSession.Kind.AUDIO || session.phase == RecordingSession.Phase.STOPPING) return
    session.stopping()
    stopJob = scope.launch {
      startJob?.join()
      cleanupMicrophone()
      val recording = audio
      audio = null
      try {
        completedAudioBytes += recording?.finish() ?: 0
        val shouldWait = waiting && !interruptedByCall
        val finalReason = if (pause && interruptedByCall) "Paused for a call. Resume when the call is finished." else reason
        withContext(Dispatchers.IO) { repository.state(id, if (pause) { if (shouldWait) "WAITING" else "PAUSED" } else "FINISHED", finalReason) }
        message = "$finalReason ${if (completedAudioBytes > 0) "Received audio is saved on this phone." else "No audio samples received yet."}"
        if (pause) session.pause(id, shouldWait) else session.finish(id)
        log(message)
      } catch (error: Exception) {
        withContext(Dispatchers.IO) { runCatching { repository.state(id, "INTERRUPTED", reason) } }
        session.finish(id)
        message = "$reason Audio stopped: ${error.message}. Saved segments are retained; interrupted files will be checked next launch."
        log(message)
      } finally {
        if (session.idle) finishWork()
        refreshLibrary()
        changed()
        if (session.phase == RecordingSession.Phase.WAITING && glasses().ready && !callActive()) resumeAudio()
      }
    }
  }

  fun onVideoStatus(event: VideoRecordingStatusEvent) {
    val id = event.requestId
    val eventProfile = event.profile?.let {
      runCatching { VideoRecordingProfile(it.width, it.height, it.fps, it.maxRecordingTimeMinutes,
        it.mode == "phone", enableFlash = it.enableFlash) }.getOrNull()
    }
    val started = event.success && (event.status == "recording_started" || event.recording == true)
    val terminal = event.recording != true && (event.status == "recording_stopped" || (event.status == "error" && event.segmentCount != null))
    if (started && eventProfile != null && session.kind == RecordingSession.Kind.AUDIO) {
      stopAudio("Audio stopped because video was started from the glasses.")
      scope.launch { stopJob?.join(); if (session.idle) onVideoStatus(event) }
      return
    }
    if (started && eventProfile != null && session.kind == RecordingSession.Kind.VIDEO &&
      session.phase == RecordingSession.Phase.UNCERTAIN && session.id != id) {
      // The hardware button can win a local-mode race before the app start is acknowledged.
      session.id?.let { abandoned ->
        session.finish(abandoned)
        preferences.edit().putBoolean("video_done:$abandoned", true).commit()
      }
    }
    if (session.idle && id.isNotEmpty() && eventProfile != null && (started || terminal) &&
      !preferences.getBoolean("video_done:$id", false)) {
      try {
        beginVideo(id, eventProfile)
        if (eventProfile.offloadToPhone && started) scope.launch(Dispatchers.IO) {
          runCatching { videoSessions.begin(id) }.onFailure { log("Video session index: ${it.message}") }
        }
        event.durationMs?.let { startedAt = SystemClock.elapsedRealtime() - it.coerceAtLeast(0) }
        message = "Recording video ${if (eventProfile.offloadToPhone) "to phone" else "on glasses"}. $activeVideoLimitDescription"
        log("Adopted ${event.origin ?: "glasses"} video $id")
      } catch (error: Exception) {
        message = "Could not track glasses recording: ${error.message}. Stopping the camera."
        scope.launch { runCatching { client().stopVideoRecording(id) } }
        changed()
        return
      }
    }
    if (event.requestId != session.id || session.kind != RecordingSession.Kind.VIDEO) return
    if (started) {
      session.started(event.requestId)
      if (recordingToPhone) { syncRequested = true; nextSyncAt = 0 }
      if (callActive()) stop()
    }
    if (terminal && event.status == "recording_stopped" && event.success) finishVideo(event.requestId, event.segmentCount, event.stopReason)
    else if (terminal && event.segmentCount != null && event.status == "error")
      finishVideo(event.requestId, event.segmentCount, event.stopReason ?: "Recording interrupted; check saved segments.")
    changed()
  }

  private fun finishVideo(id: String, segmentCount: Int? = null, reason: String? = null) {
    val phoneVideo = recordingToPhone
    // A listener can finish the session before the command waiter returns its terminal metadata.
    if (segmentCount != null) preferences.edit().putInt("video_segments:$id", segmentCount).commit()
    if (!session.finish(id)) return
    if (phoneVideo || segmentCount != 0) queued.add(id)
    if (phoneVideo) {
      preferences.edit().putBoolean("phone_video:$id", true).commit()
      scope.launch(Dispatchers.IO) { videoSessions.finish(id, reason ?: "Recording ended.") }
    }
    activeVideoProfile = null
    activePhoneVideoMinutes = null
    if (phoneGrant?.sessionId == id) savePhoneGrant(null)
    nextGrantAt = 0
    preferences.edit().remove("active_video").remove("active_video_profile").remove("active_phone_video_minutes")
      .putBoolean("video_done:$id", true).putStringSet("videos", queued.toSet()).commit()
    message = if (phoneVideo) "Video ended. ${reason?.let(PhoneVideoPlan::stopDescription) ?: ""} Remaining segments will save to phone automatically."
      else if (segmentCount == 0) "Video did not produce a completed file. ${reason?.let(PhoneVideoPlan::stopDescription) ?: ""}"
      else "Video saved on the glasses. It will transfer to phone media automatically."
    scheduleAutomaticTransfers()
    finishWork()
    log("Video finished; queued for Wi-Fi download")
    changed()
  }

  fun onConnection(ready: Boolean) {
    mediaConnection.onGlassesConnectionChanged(ready)
    phoneGrantReadyUntil = 0
    nextGrantAt = 0
    nextStatusAt = 0
    if (!ready) profileReady = false
    if (ready) syncRequested = true
    if (ready && (session.kind != RecordingSession.Kind.AUDIO || paused)) cleanupMicrophone()
    if (!ready) {
      if (session.kind == RecordingSession.Kind.AUDIO && !paused) finishAudio("Waiting for the glasses; connection interrupted.", pause = true, waiting = true)
      if (session.kind == RecordingSession.Kind.VIDEO) session.id?.let {
        session.uncertain(it)
        message = "Connection lost. Reconnect to finish the video. $activeVideoLimitDescription"
      }
      transferJob?.cancel()
    } else if (session.kind == RecordingSession.Kind.VIDEO && session.phase == RecordingSession.Phase.UNCERTAIN) stop()
    else if (ready && session.phase == RecordingSession.Phase.WAITING && !callActive()) resumeAudio()
    changed()
  }

  fun onCallStarted() {
    updateRecordingReadiness(settings.videoDefaults().profile, settings.videoDefaults().revision.toString(), false)
    if (session.kind == RecordingSession.Kind.AUDIO) {
      interruptedByCall = true
      if (session.phase == RecordingSession.Phase.WAITING) {
        session.pause(session.id!!, false)
        message = "Paused for a call. Resume when the call is finished."
        val id = session.id!!
        scope.launch(Dispatchers.IO) { runCatching { repository.pauseWaitingForCall(id) } }
      }
    }
    if (downloading) { log("Cancelling video transfer for phone call"); cancelTransfer() }
    if (session.kind == RecordingSession.Kind.AUDIO && !paused) finishAudio("Paused for a call. Resume when the call is finished.", pause = true)
    else if (session.kind == RecordingSession.Kind.VIDEO) { log("Stopping video for phone call"); stop() }
  }

  fun cancelTransfer() {
    transferCancelled = true
    publicationRequested = false
    transferJob?.cancel(); publicationJob?.cancel()
    nextSyncAt = SystemClock.elapsedRealtime() + 5 * 60_000
  }

  fun scheduleAutomaticTransfers() {
    syncRequested = true
    nextSyncAt = SystemClock.elapsedRealtime() + 5000
  }

  fun retryAutomaticTransfers() {
    transferCancelled = false
    syncRequested = true
    approvalRequested = true
    nextSyncAt = 0
    publishCompletedMedia()
    maybeAutomaticTransfers()
  }

  fun maybeAutomaticTransfers() {
    val activeTransferAllowed = recordingToPhone && session.phase != RecordingSession.Phase.STARTING
    if (!glasses().ready || downloading || publishing || !recovered || (!session.idle && !activeTransferAllowed) ||
      callActive() || SystemClock.elapsedRealtime() < nextSyncAt) return
    if ((context.applicationContext as LabApplication).isMediaPlaybackActive) return
    if (!syncRequested && !mediaConnection.isReady) return
    // A background timer must never summon Android's Wi-Fi approval over playback
    // or on the lock screen. An explicit connection action is the only prompt entry.
    if (!mediaConnection.isReady && !approvalRequested) {
      val waiting = "Connect media Wi-Fi to save glasses captures. Originals are retained."
      if (syncMessage != waiting) { syncMessage = waiting; changed() }
      return
    }
    val allowPrompt = approvalRequested && (context.applicationContext as LabApplication).isUiVisible
    if (!mediaConnection.isReady && !allowPrompt) return
    approvalRequested = false
    nextSyncAt = SystemClock.elapsedRealtime() + if (needsPhoneVideoSync) 5000 else 5 * 60_000
    syncGlassesMedia(allowPrompt)
  }

  fun setKeepMediaWifiReady(enabled: Boolean) {
    settings.setKeepMediaWifiReady(enabled)
    if (!enabled && !downloading && !needsPhoneVideoSync && !settings.videoDefaults().profile.offloadToPhone)
      scope.launch { mediaConnection.release("Media Wi-Fi will connect when requested."); changed() }
    changed()
  }

  /** Publish only immutable completed files. Audio may continue into the next private segment. */
  fun publishCompletedMedia() {
    if (!recovered) return
    publicationRequested = true
    if (downloading || publicationJob?.isActive == true) return
    publishing = true
    publicationJob = scope.launch {
      try {
        ensureRunning()
        val job = currentCoroutineContext()
        do {
          // A segment committed while IO is saving the previous snapshot must get
          // another pass, including the final segment after recording stops.
          publicationRequested = false
          withContext(Dispatchers.IO) {
            media.recover({ kind, bytes -> publicationCapacity(kind, bytes) }, job::ensureActive)
            val files = File(context.filesDir, "photos").listFiles().orEmpty().filter { it.extension == "jpg" } +
              directory.walkTopDown().filter { it.isFile && it.extension in listOf("wav", "mp4") }.toList()
            for (file in files) {
              job.ensureActive()
              if (media.isSuppressed(file)) continue
              val kind = when (file.extension) { "jpg" -> MediaKind.PHOTO; "mp4" -> MediaKind.VIDEO; else -> MediaKind.AUDIO }
              // Indexed WAV segments were finalized before the writer made them visible.
              if (file.parentFile?.name?.startsWith("audio-") == true &&
                !repository.isCommittedSegment(file)) continue
              media.publish(file, kind, capacity = { publicationCapacity(kind, it) }, checkCancelled = job::ensureActive)
              media.removePrivateCopy(file, job::ensureActive)
              settings.guard().invalidate()
            }
            media.reconcile()
          }
        } while (publicationRequested)
        syncMessage = "Saved captures are available in phone media."
      } catch (error: CancellationException) {
        syncMessage = "Saving paused. Original files are retained."; throw error
      } catch (error: Exception) {
        syncMessage = "Waiting to save to phone media: ${error.message}"
        log(syncMessage)
      } finally { publishing = false; refreshLibrary(); changed() }
    }
    changed()
  }

  private fun publicationCapacity(kind: MediaKind, bytes: Long) {
    if (kind == MediaKind.PHOTO) check(context.filesDir.usableSpace - settings.policy().reserveBytes >= bytes) {
      "Phone space is low. Your photo is retained in Glasses Lab."
    } else settings.guard().requireCapacity(bytes)
  }

  private fun syncGlassesMedia(allowPrompt: Boolean) {
    transferCancelled = false
    downloading = true
    val activeTransferSession = session.id.takeIf { recordingToPhone }
    syncMessage = "Connecting to the glasses to save completed captures…"
    if (!recordingToPhone) message = syncMessage
    transferJob = scope.launch {
      var copied = 0
      var recoveryPending = 0
      var retainedAuxiliaries = AuxiliaryMediaTotals()
      try {
        ensureRunning()
        withContext(Dispatchers.IO) { media.reconcile() }
        syncMessage = if (mediaConnection.isReady) "Saving completed glasses captures…" else "Approve the media Wi-Fi connection if Android asks."
        if (!recordingToPhone) message = syncMessage
        changed()
        val connection = mediaConnection.acquire(allowPrompt)
        val offload = MediaOffloadClient(context, connection.network, connection.hotspot.localIp, connection.token, media, settings)
        var cursor: String? = null
        val visited = mutableSetOf<String>()
        do {
          currentCoroutineContext().ensureActive()
          val page = withContext(Dispatchers.IO) { offload.page(cursor) }
          recoveryPending = maxOf(recoveryPending, page.recoveryPending)
          retainedAuxiliaries = retainedAuxiliaries.include(page)
          for (remote in page.files) {
            currentCoroutineContext().ensureActive()
            check(!callActive()) { "Media transfer paused for a call." }
            if (activeTransferSession != null && remote.videoSegment?.sessionId != activeTransferSession) continue
            val matchingRequests = if (remote.videoSegment != null) emptyList() else
              queued.filter { it == remote.requestId || remote.captureId.endsWith("_$it") }
            if (matchingRequests.isNotEmpty()) {
              val target = File(File(context.filesDir, if (remote.photo) "photos" else "recordings"), remote.localName)
              check(preferences.edit().apply { matchingRequests.forEach { putString("video_copy:$it", target.path) } }.commit()) {
                "Could not save transfer recovery state."
              }
            }
            val publication = withContext(Dispatchers.IO) { offload.transfer(remote,
              beforeReclaim = { source, copy -> if (source.videoSegment != null) videoSessions.register(source, copy) }) { percent ->
              scope.launch {
                syncMessage = "Saving ${if (remote.photo) "photo" else "video"}: $percent%"
                if (!recordingToPhone) message = syncMessage
                changed()
              }
            } }
            if (publication != null) {
              copied++
              reconcileSavedVideoQueue()
              refreshLibrary()
            }
          }
          cursor = page.cursor
          if (cursor != null) check(visited.add(cursor)) { "Glasses repeated a media page; retry later." }
        } while (cursor != null)
        syncMessage = when {
          recoveryPending > 0 -> "$copied files saved. Some glasses files need recovery; originals retained."
          retainedAuxiliaries.files > 0 -> "$copied files saved. ${retainedAuxiliaries.files} auxiliary files " +
            "(${android.text.format.Formatter.formatShortFileSize(context, retainedAuxiliaries.bytes)}) remain on the glasses."
          copied > 0 -> "$copied files saved to phone media; verified glasses copies reclaimed."
          else -> "Phone media is up to date."
        }
        if (!recordingToPhone) message = syncMessage
        syncRequested = false
        log(syncMessage)
      } catch (error: CancellationException) {
        syncMessage = "Transfer paused. Unverified originals remain on the glasses."
        if (!recordingToPhone) message = syncMessage
        throw error
      } catch (error: Exception) {
        syncMessage = "Waiting to sync: ${error.message}"
        if (recordingToPhone && error is RecordingCapacityExceeded) {
          message = "Phone storage allowance reached. Stopping recording; saved segments are retained."
          stop()
        } else if (!recordingToPhone) message = syncMessage
        log(syncMessage)
      } finally {
        if (!settings.keepMediaWifiReady() && !needsPhoneVideoSync && !settings.videoDefaults().profile.offloadToPhone) withContext(NonCancellable) {
          withTimeoutOrNull(6000) { runCatching { mediaConnection.release("Media Wi-Fi closed after transfer.") } }
        }
        withContext(NonCancellable) { runCatching { reconcileSavedVideoQueue() } }
        downloading = false
        if (needsPhoneVideoSync && !transferCancelled) nextSyncAt = SystemClock.elapsedRealtime() + 3000
        if (publicationRequested) publishCompletedMedia()
        refreshLibrary(); changed()
      }
    }
    changed()
  }

  /** Phone persistence and glasses cleanup are independent after a verified public copy exists. */
  private suspend fun reconcileSavedVideoQueue() {
    val snapshot = queued.toList()
    val saved = withContext(Dispatchers.IO) {
      snapshot.filter { id ->
        if (preferences.getBoolean("phone_video:$id", false)) {
          val expected = preferences.getInt("video_segments:$id", -1)
          expected >= 0 && videoSessions.hasAllSegments(id, expected)
        } else preferences.getString("video_copy:$id", null)?.let { path ->
          val file = File(path)
          media.isSuppressed(file) || media.lookup(file)?.let(media::isAvailable) == true
        } == true
      }
    }
    if (saved.isNotEmpty()) {
      queued.removeAll(saved.toSet())
      check(preferences.edit().putStringSet("videos", queued.toSet())
        .apply { saved.forEach { remove("video_copy:$it"); remove("video_segments:$it"); remove("phone_video:$it") } }
        .commit()) { "Could not save transfer completion." }
    }
  }

  fun downloadVideo(id: String) {
    require(queued.contains(id))
    retryAutomaticTransfers()
  }

  private fun requireReady() { check(glasses().ready && !busy) { "Connect and stop the current recording or transfer first." } }
  private fun finishWork() { ticker?.cancel(); elapsedSeconds = 0 }
  fun refreshLibrary() {
    scope.launch {
      audioSessions = withContext(Dispatchers.IO) {
        (0 until libraryLimit step 100).flatMap { repository.list(offset = it) }
      }
      mediaFiles = withContext(Dispatchers.IO) { media.list() }
      changed()
    }
  }
  fun loadMore() { libraryLimit += 100; refreshLibrary() }
  fun close() {
    mediaConnection.close()
    if (session.kind == RecordingSession.Kind.AUDIO) {
      cleanupMicrophone()
    }
    scope.cancel() // File writers close in finally; startup repairs interrupted metadata.
  }
  private fun cleanupMicrophone() {
    if (!glasses().ready || !preferences.getBoolean("mic_cleanup_pending", false)) return
    runCatching {
      client().setMicState(false)
      client().setVoiceActivityDetectionEnabled(preferences.getBoolean("mic_cleanup_vad", true))
      preferences.edit().putBoolean("mic_cleanup_pending", false).apply()
    }.onFailure { log("Microphone cleanup will retry on reconnect: ${it.message}") }
  }
  private fun startTicker() {
    ticker?.cancel()
    ticker = scope.launch {
      while (!session.idle) {
        delay(1000)
        elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        if (session.kind == RecordingSession.Kind.AUDIO && !paused && session.phase != RecordingSession.Phase.STOPPING) {
          if (SystemClock.elapsedRealtime() - lastAudioAt > 8000) finishAudio("Microphone audio stopped. Resume when available.", pause = true)
        }
        changed()
      }
    }
  }
}
