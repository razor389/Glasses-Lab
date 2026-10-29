package dev.ross.glasses

import android.app.AlertDialog
import android.content.Intent
import android.media.*
import android.net.Uri
import android.os.*
import android.widget.*
import androidx.core.content.FileProvider
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.*

/** Presents ordered storage segments as one seekable recording. */
class AudioSessionActivity : CompanionActivity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val repository get() = (application as LabApplication).recordings
  private val media get() = (application as LabApplication).media
  private val playbackOwner = Any()
  private var ownsPlayback = false
  private fun markPlaybackActive(active: Boolean) {
    ownsPlayback = active
    (application as LabApplication).mediaPlayback.setActive(playbackOwner, active)
  }
  private val id by lazy { intent.getStringExtra("session") ?: error("Missing recording identity.") }
  private var segments = emptyList<AudioSegment>()
  private var segmentUris = emptyMap<String, Uri>()
  private var loadJob: Job? = null
  private var player: MediaPlayer? = null
  private var focus: AudioFocusRequest? = null
  private var index = 0
  private var prepared = false
  private var visibleForPlayback = false
  private var boundForPlayback = false
  private var autoPlayPending = false
  private var exporting = false
  private var generation = 0L
  private val handler = Handler(Looper.getMainLooper())
  private val audio by lazy { getSystemService(AudioManager::class.java) }
  private lateinit var status: TextView
  private lateinit var seek: SeekBar
  private lateinit var play: Button
  private lateinit var share: Button
  private lateinit var delete: Button
  private val totalMs get() = segments.sumOf { it.durationMs }
  private val observer: () -> Unit = { refreshControls() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val page = LabUi.page(this, "Audio recording")
    page.addView(LabUi.button(this, "Back to recordings") { finish() })
    status = LabUi.text(this, "Loading recording…", 18); page.addView(status)
    seek = SeekBar(this).apply { max = 10000 }; page.addView(seek)
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onStartTrackingTouch(seekBar: SeekBar?) {}
      override fun onStopTrackingTouch(seekBar: SeekBar?) {}
      override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (fromUser && segments.isNotEmpty() && canPlay()) {
          val position = (totalMs * progress / 10000).coerceAtMost((totalMs - 1).coerceAtLeast(0))
          var accumulated = 0L
          val segmentIndex = segments.indexOfFirst { segment ->
            if (position < accumulated + segment.durationMs) true else { accumulated += segment.durationMs; false }
          }.takeIf { it >= 0 } ?: segments.lastIndex
          open(segmentIndex, (position - accumulated).coerceAtLeast(0).toInt(), player?.isPlaying == true)
        }
      }
    })
    play = LabUi.button(this, "Play / pause") {
      if (!canPlay()) return@button
      if (player?.isPlaying == true) pausePlayer()
      else if (prepared) startPreparedPlayer()
      else if (segments.isNotEmpty()) open(index, 0, true)
    }; page.addView(play)
    page.addView(LabUi.text(this, "Audio plays in segment order. Interruptions are listed in the exported session metadata; missing audio is not filled with pretend silence.", 15))
    share = LabUi.button(this, "Export recording bundle") { export() }; page.addView(share)
    delete = LabUi.button(this, "Delete recording") {
      AlertDialog.Builder(this).setMessage("Delete this recording and all of its audio segments from this phone?")
        .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
          scope.launch {
            try {
              check(!controller.recordings.busy && !exporting)
              releasePlayer()
              withContext(Dispatchers.IO) { repository.delete(id) }
              controller.recordings.refreshLibrary()
              finish()
            } catch (error: Exception) { status.text = "Deletion failed: ${error.message}" }
          }
        }.show()
    }; page.addView(delete)
    handler.post(object : Runnable {
      override fun run() {
        if (prepared) {
          val current = segments.take(index).sumOf { it.durationMs } + (player?.currentPosition ?: 0)
          seek.progress = if (totalMs > 0) (current * 10000 / totalMs).toInt() else 0
          status.text = "${current / 1000}s / ${totalMs / 1000}s captured · ${segments.size} parts"
        }
        handler.postDelayed(this, 500)
      }
    })
  }
  override fun onCompanionReady() {
    boundForPlayback = true
    controller.observe(observer)
    loadJob?.cancel()
    loadJob = scope.launch {
      try {
        val loaded = withContext(Dispatchers.IO) {
          val values = repository.segments(id)
          val sources = values.mapNotNull { segment ->
            val publication = media.lookup(segment.file)?.takeIf { media.isAvailable(it) }
            val uri = publication?.uri ?: segment.file.takeIf { it.isFile }?.let(Uri::fromFile)
            uri?.let { segment.file.absolutePath to it }
          }.toMap()
          values to sources
        }
        segments = loaded.first
        segmentUris = loaded.second
        status.text = "${totalMs / 1000}s captured · ${segments.size} completed parts"
      } catch (error: Exception) { if (error !is CancellationException) status.text = "Cannot open recording: ${error.message}" }
      refreshControls()
    }
  }
  override fun onCompanionUnavailable() {
    boundForPlayback = false
    autoPlayPending = false
    controller.unobserve(observer)
    pausePlayer()
  }
  private fun canPlay() = boundForPlayback && visibleForPlayback && !controller.recordings.busy && !controller.audio.callActive && !exporting
  private fun refreshControls() {
    val available = canPlay()
    play.isEnabled = available && segments.isNotEmpty()
    seek.isEnabled = play.isEnabled
    share.isEnabled = boundForPlayback && !controller.recordings.busy && !exporting && segments.isNotEmpty()
    delete.isEnabled = boundForPlayback && !controller.recordings.busy && !exporting
    if (!available) { autoPlayPending = false; pausePlayer() }
  }
  private fun open(next: Int, offset: Int, autoPlay: Boolean) {
    if (!canPlay()) return
    val continuingPlayback = autoPlay && ownsPlayback
    releasePlayer()
    index = next
    val uri = segmentUris[segments[index].file.absolutePath]
    if (uri == null) { status.text = "This audio part is no longer available on the phone."; return }
    autoPlayPending = autoPlay
    // Keep an existing playback exclusion through the brief automatic segment handoff.
    if (continuingPlayback) markPlaybackActive(true)
    val token = generation
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
      .setOnAudioFocusChangeListener { if (token == generation && it < 0) { autoPlayPending = false; pausePlayer() } }.build()
    try {
      val nextPlayer = MediaPlayer()
      player = nextPlayer
      nextPlayer.apply {
        setAudioAttributes(attributes)
        setDataSource(this@AudioSessionActivity, uri)
        setOnPreparedListener { media ->
          if (token != generation) return@setOnPreparedListener
          prepared = true
          media.seekTo(offset)
          if (autoPlayPending && canPlay()) startPreparedPlayer() else markPlaybackActive(false)
          autoPlayPending = false
        }
        setOnCompletionListener {
          if (token != generation) return@setOnCompletionListener
          if (index < segments.lastIndex && canPlay()) open(index + 1, 0, true)
          else markPlaybackActive(false)
        }
        setOnErrorListener { _, _, _ ->
          if (token == generation) { status.text = "This segment could not be played."; releasePlayer() }
          true
        }
        prepareAsync()
      }
    } catch (error: Exception) { status.text = "Playback failed: ${error.message}"; releasePlayer() }
  }
  private fun export() {
    if (controller.recordings.busy || exporting) return
    exporting = true
    refreshControls()
    scope.launch {
      try {
        status.text = "Preparing export…"
        val job = currentCoroutineContext()
        val file = withContext(Dispatchers.IO) {
          val manifest = Gson().toJson(mapOf("session" to id, "format" to "PCM 16000 Hz mono 16 bit",
            "segments" to segments.map { mapOf("file" to it.file.name, "startedAt" to it.startedAt, "pcmBytes" to it.pcmBytes) },
            "gaps" to repository.gaps(id)))
          AudioSessionExporter.save(segments, File(repository.directory, "exports"), manifest,
            RecordingSettings(this@AudioSessionActivity, repository).guard()::requireCapacity, job::ensureActive,
            media::openInput)
        }
        val uri = FileProvider.getUriForFile(this@AudioSessionActivity, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
          .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export audio recording"))
        status.text = "Export ready. Original recording retained."
      } catch (error: Exception) { if (error !is CancellationException) status.text = "Export failed: ${error.message}" }
      finally { exporting = false; refreshControls() }
    }
  }
  private fun releasePlayer() {
    markPlaybackActive(false)
    generation++
    autoPlayPending = false
    prepared = false
    player?.release(); player = null
    focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
  }
  private fun pausePlayer() {
    autoPlayPending = false
    try {
      if (prepared && player?.isPlaying == true) player?.pause()
      focus?.let { audio.abandonAudioFocusRequest(it) }
    } finally { markPlaybackActive(false) }
  }
  private fun startPreparedPlayer() {
    if (!prepared || !canPlay()) { markPlaybackActive(false); return }
    try {
      if (focus?.let { audio.requestAudioFocus(it) } == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) player?.start()
      markPlaybackActive(player?.isPlaying == true)
    } catch (error: RuntimeException) {
      status.text = "Playback failed: ${error.message}"
      releasePlayer()
    }
  }
  override fun onResume() { super.onResume(); visibleForPlayback = true; if (boundForPlayback) refreshControls() }
  override fun onPause() { visibleForPlayback = false; autoPlayPending = false; pausePlayer(); super.onPause() }
  override fun onStop() { visibleForPlayback = false; autoPlayPending = false; markPlaybackActive(false); super.onStop() }
  override fun onDestroy() { handler.removeCallbacksAndMessages(null); releasePlayer(); scope.cancel(); super.onDestroy() }
}
