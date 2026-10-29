package dev.ross.glasses

import com.mentra.bluetoothsdk.SettingsAckEvent
import com.mentra.bluetoothsdk.BluetoothSdkException
import kotlinx.coroutines.*

interface CaptureDefaultsTransport {
  fun cache(photo: PhotoCaptureProfile, video: VideoRecordingProfile, revision: String)
  suspend fun photo(profile: PhotoCaptureProfile): SettingsAckEvent
  suspend fun video(profile: VideoRecordingProfile, revision: String): SettingsAckEvent
}

/** Main-thread owned. One worker, independent confirmations, no deferred capture commands. */
class CaptureDefaultsSyncController(
  initialPhoto: SavedProfile<PhotoCaptureProfile>, initialVideo: SavedProfile<VideoRecordingProfile>,
  private val transport: CaptureDefaultsTransport, private val scope: CoroutineScope,
  private val changed: () -> Unit, private val now: () -> Long = System::currentTimeMillis,
) {
  private enum class Status { PENDING, APPLYING, APPLIED, FAILED, UNSUPPORTED }
  private class Domain {
    var status = Status.PENDING
    var attempts = 0
    var nextAt = 0L
    var error: String? = null
    fun reset() { status = Status.PENDING; attempts = 0; nextAt = 0; error = null }
  }
  private var savedPhoto = initialPhoto
  private var savedVideo = initialVideo
  private val photoDomain = Domain()
  private val videoDomain = Domain()
  private var device: String? = null
  private var generation = 0L
  private var worker: Job? = null
  private var inFlight: Any? = null
  private var closed = false
  val photo get() = savedPhoto.profile
  val video get() = savedVideo.profile
  val videoRevision get() = savedVideo.revision.toString()
  val photoApplied get() = device != null && photoDomain.status == Status.APPLIED
  val videoApplied get() = device != null && videoDomain.status == Status.APPLIED
  val applying get() = inFlight != null
  val photoCanRetry get() = device != null && photoDomain.status == Status.FAILED
  val videoCanRetry get() = device != null && videoDomain.status == Status.FAILED
  val photoStatus get() = describe(photoDomain)
  val videoStatus get() = describe(videoDomain)

  private fun describe(domain: Domain): String = when {
    device == null -> "Saved on phone. Connect glasses to apply."
    domain.status == Status.APPLIED -> "Applied to glasses."
    domain.status == Status.UNSUPPORTED -> "Saved on phone. Needs compatible glasses software."
    domain.status == Status.FAILED -> "Saved on phone. Could not finish applying: ${domain.error ?: "Retry applying."}"
    else -> "Applying defaults to glasses…"
  }
  fun savePhoto(value: SavedProfile<PhotoCaptureProfile>) {
    savedPhoto = value; photoDomain.reset(); restartIfSleeping(); changed()
  }
  fun saveVideo(value: SavedProfile<VideoRecordingProfile>) {
    savedVideo = value; videoDomain.reset(); restartIfSleeping(); changed()
  }
  fun retryPhoto() { photoDomain.reset(); restartIfSleeping(); changed() }
  fun retryVideo() { videoDomain.reset(); restartIfSleeping(); changed() }

  /** A same-link ASG restart first invalidates with null, then re-enters after readiness replay. */
  fun connection(value: String?) {
    if (closed || device == value) return
    generation++; device = value
    inFlight = null
    worker?.cancel(); worker = null
    photoDomain.reset(); videoDomain.reset()
    cacheLatest(); startWorker(); changed()
  }
  private fun cacheLatest() { if (!closed) transport.cache(photo, video, videoRevision) }
  private fun restartIfSleeping() {
    cacheLatest()
    if (!applying) { worker?.cancel(); worker = null }
    startWorker()
  }
  private fun startWorker() {
    if (closed || device == null || worker?.isActive == true) return
    worker = scope.launch(start = CoroutineStart.LAZY) { synchronize() }.also { it.start() }
  }
  private fun eligible(d: Domain) = d.status == Status.PENDING || (d.status == Status.FAILED && d.nextAt != Long.MAX_VALUE)
  private suspend fun synchronize() {
    val epoch = generation
    try {
      while (device != null && generation == epoch) {
        val due = listOf(photoDomain, videoDomain).filter(::eligible)
        if (due.isEmpty()) return
        val domain = due.firstOrNull { it.nextAt <= now() }
        if (domain == null) { delay((due.minOf { it.nextAt } - now()).coerceAtLeast(1)); continue }
        val isPhoto = domain === photoDomain
        val photoSnapshot = savedPhoto
        val videoSnapshot = savedVideo
        val attempt = Any()
        inFlight = attempt
        domain.status = Status.APPLYING; changed()
        try {
          if (isPhoto) {
            val ack = transport.photo(photoSnapshot.profile)
            validate(ack, "button_photo", mapOf("size" to photoSnapshot.profile.size.value,
              "flash" to photoSnapshot.profile.enableFlash))
            if ((ack.values["localPhotoQualityVersion"] as? Number)?.toDouble() != 1.0) throw UnsupportedPhotoDefaults()
          } else {
            val profile = videoSnapshot.profile
            validate(transport.video(profile, videoSnapshot.revision.toString()), "recording_profile", mapOf(
              "revision" to videoSnapshot.revision.toString(), "width" to profile.width,
              "height" to profile.height, "fps" to profile.fps, "maxRecordingTimeMinutes" to profile.maxMinutes,
              "mode" to if (profile.offloadToPhone) "phone" else "local",
              "segmentMaxBytes" to PhoneVideoPlan.SEGMENT_BYTES, "maxBacklogBytes" to PhoneVideoPlan.BACKLOG_BYTES, "flash" to profile.enableFlash,))
          }
          if (epoch == generation && (if (isPhoto) photoSnapshot == savedPhoto else videoSnapshot == savedVideo)) domain.status = Status.APPLIED
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
          if (epoch == generation && (if (isPhoto) photoSnapshot == savedPhoto else videoSnapshot == savedVideo)) {
            domain.error = error.message
            domain.status = if (error is UnsupportedPhotoDefaults) Status.UNSUPPORTED else Status.FAILED
            val delays = longArrayOf(1_000, 3_000, 10_000)
            val permanent = error is IllegalArgumentException || (error is BluetoothSdkException &&
              (error.code.contains("invalid", ignoreCase = true) || error.code.contains("unsupported", ignoreCase = true)))
            domain.nextAt = if (!permanent && domain.status == Status.FAILED && domain.attempts < delays.size) now() + delays[domain.attempts++] else Long.MAX_VALUE
          }
        } finally {
          if (inFlight === attempt) inFlight = null
          // SDK setters update cache after ACK; restage newest values even when that ACK is stale.
          cacheLatest(); changed()
        }
      }
    } finally { cacheLatest() }
  }
  private fun validate(ack: SettingsAckEvent, setting: String, expected: Map<String, Any>) {
    check(ack.setting == setting && ack.values["status"] == "applied") { "Unexpected settings acknowledgement." }
    for ((key, value) in expected) {
      val actual = ack.values[key]
      check(if (value is Number) actual is Number && actual.toDouble() == value.toDouble() else actual == value) { "Glasses did not confirm $key." }
    }
  }
  fun close() { closed = true; connectionForClose() }
  private fun connectionForClose() { generation++; device = null; inFlight = null; worker?.cancel(); worker = null; photoDomain.reset(); videoDomain.reset() }
  private class UnsupportedPhotoDefaults : Exception("Glasses software does not support shared photo quality.")
}
