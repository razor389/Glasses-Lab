package dev.ross.glasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*

/** Opens only private media or a published item recorded in our own journal. */
class PlaybackActivity : CompanionActivity() {
  private data class Source(val file: File, val uri: Uri, val kind: String, val bitmap: Bitmap?)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val publisher get() = (application as LabApplication).media
  private val playbackOwner = Any()
  private fun markPlaybackActive(active: Boolean) =
    (application as LabApplication).mediaPlayback.setActive(playbackOwner, active)
  private val audio by lazy { getSystemService(AudioManager::class.java) }
  private val handler = Handler(Looper.getMainLooper())
  private var player: MediaPlayer? = null
  private var video: VideoView? = null
  private var focus: AudioFocusRequest? = null
  private var prepared = false
  private var boundForPlayback = false
  private var visibleForPlayback = false
  private var autoPlayRequested = true
  private var loadStarted = false
  private var mediaKind = ""
  private var generation = 0L
  private var videoParts = emptyList<SavedVideoSegment>()
  private var videoPartIndex = 0
  private var videoHandoff = false
  private lateinit var previousPart: Button
  private lateinit var nextPart: Button
  private lateinit var content: LinearLayout
  private lateinit var status: TextView
  private lateinit var play: Button
  private lateinit var seek: SeekBar
  private lateinit var position: TextView
  private val observer: () -> Unit = { refreshControls() }
  private val tick = object : Runnable {
    override fun run() {
      markPlaybackActive(visibleForPlayback && (videoHandoff || (prepared && runCatching {
        player?.isPlaying == true || video?.isPlaying == true
      }.getOrDefault(false))))
      if (prepared && mediaKind == "wav") {
        player?.let { current ->
          seek.progress = current.currentPosition
          position.text = "${LabUi.duration(current.currentPosition / 1000L)} / ${LabUi.duration(current.duration / 1000L)}"
          play.text = if (current.isPlaying) "Pause" else "Play"
        }
      } else if (prepared && mediaKind == "mp4") play.text = if (video?.isPlaying == true) "Pause" else "Play"
      handler.postDelayed(this, 500)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val page = LabUi.page(this, "Playback")
    page.addView(LabUi.button(this, "Back to library") { finish() })
    status = LabUi.text(this, "Opening media…", 15, LabUi.muted)
    page.addView(status)
    content = LabUi.column(this)
    page.addView(content)
    position = LabUi.text(this, "", 18)
    page.addView(position)
    seek = SeekBar(this).apply { visibility = View.GONE }
    page.addView(seek)
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onStartTrackingTouch(seekBar: SeekBar?) {}
      override fun onStopTrackingTouch(seekBar: SeekBar?) {}
      override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (fromUser && prepared && canPlay()) player?.seekTo(progress)
      }
    })
    play = LabUi.button(this, "Play", primary = true) {
      autoPlayRequested = false
      if (player?.isPlaying == true || video?.isPlaying == true) pausePlayback()
      else startPlayback()
      refreshControls()
    }.apply { isEnabled = false; visibility = View.GONE }
    page.addView(play)
    previousPart = LabUi.button(this, "Previous part") { openVideoPart(videoPartIndex - 1, true) }.apply { visibility = View.GONE }
    nextPart = LabUi.button(this, "Next part") { openVideoPart(videoPartIndex + 1, true) }.apply { visibility = View.GONE }
    LabUi.row(this, page, previousPart, nextPart)
    handler.post(tick)
  }

  override fun onCompanionReady() {
    boundForPlayback = true
    controller.observe(observer)
    if (!loadStarted) loadMedia()
    refreshControls()
  }

  override fun onCompanionUnavailable() {
    boundForPlayback = false
    controller.unobserve(observer)
    autoPlayRequested = false
    pausePlayback()
    refreshControls()
  }

  private fun loadMedia() {
    loadStarted = true
    val token = generation
    scope.launch {
      try {
        val sessionId = intent.getStringExtra("video_session")
        if (sessionId != null) {
          videoParts = withContext(Dispatchers.IO) { controller.recordings.videoSessions.segments(sessionId) }
          check(videoParts.isNotEmpty()) { "This recording has no saved video parts yet." }
          if (token != generation || isDestroyed) return@launch
          mediaKind = "mp4"
          previousPart.visibility = View.VISIBLE
          nextPart.visibility = View.VISIBLE
          openVideoPart(0, autoPlayRequested)
          return@launch
        }
        val source = withContext(Dispatchers.IO) {
          val journalPath = intent.getStringExtra("media")
          val file = if (journalPath != null) {
            require(File(journalPath).isAbsolute) { "Invalid media selection." }
            File(journalPath).canonicalFile.also {
              check(publisher.lookup(it) != null) { "This item is not in the Glasses Lab media library." }
            }
          } else {
            val relative = intent.getStringExtra("file") ?: error("No media selected.")
            require(!File(relative).isAbsolute) { "Invalid media selection." }
            File(filesDir, relative).canonicalFile.also {
              val parent = requireNotNull(it.parentFile) { "Invalid media selection." }
              require(parent in listOf(File(filesDir, "photos").canonicalFile,
                File(filesDir, "recordings").canonicalFile)) { "Invalid media selection." }
            }
          }
          val publication = publisher.lookup(file)?.takeIf { publisher.isAvailable(it) }
          check(publication != null || file.isFile) { "This item is no longer available on the phone." }
          val kind = file.extension.lowercase(Locale.US)
          require(kind in setOf("jpg", "wav", "mp4")) { "Unsupported media format." }
          val bitmap = if (kind == "jpg") {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            publisher.openInput(file).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            publisher.openInput(file).use {
              BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("This photo could not be opened.")
          } else null
          Source(file, publication?.uri ?: Uri.fromFile(file), kind, bitmap)
        }
        if (token != generation || isDestroyed) return@launch
        mediaKind = source.kind
        when (source.kind) {
          "jpg" -> {
            content.addView(ImageView(this@PlaybackActivity).apply {
              contentDescription = "Photo from your glasses"
              adjustViewBounds = true
              setImageBitmap(source.bitmap)
            })
            status.text = "Photo"
          }
          "mp4" -> prepareVideo(source.uri, token)
          "wav" -> prepareAudio(source.uri, token)
        }
        refreshControls()
      } catch (error: Exception) {
        if (error !is CancellationException) {
          markPlaybackActive(false)
          prepared = false
          player?.release(); player = null
          video?.stopPlayback(); video = null
          focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
          status.text = error.message ?: "This item could not be opened."
        }
      }
    }
  }

  /** Uses the publication journal for each part; missing parts are shown instead of silently skipped. */
  private fun openVideoPart(index: Int, autoPlay: Boolean) {
    if (index !in videoParts.indices) return
    generation++
    val token = generation
    prepared = false
    video?.stopPlayback(); video = null
    videoHandoff = autoPlay && canPlay()
    markPlaybackActive(videoHandoff)
    content.removeAllViews()
    videoPartIndex = index
    autoPlayRequested = autoPlay
    status.text = "Opening video part ${videoParts[index].index + 1}…"
    refreshControls()
    scope.launch {
      try {
        val part = videoParts[index]
        val uri = withContext(Dispatchers.IO) {
          val publication = publisher.lookup(part.file)
          check(publication != null && publication.bytes == part.bytes && publication.sha256 == part.sha256 &&
            publisher.isAvailable(publication)) { "Video part ${part.index + 1} is no longer available on the phone." }
          publication.uri
        }
        if (token != generation || isDestroyed) return@launch
        prepareVideo(uri, token)
      } catch (error: Exception) {
        if (error !is CancellationException && token == generation) {
          autoPlayRequested = false
          videoHandoff = false
          markPlaybackActive(false)
          status.text = error.message ?: "This video part could not be opened."
          refreshControls()
        }
      }
    }
  }

  private fun prepareVideo(uri: Uri, token: Long) {
    status.text = "Preparing video…"
    video = object : VideoView(this) {
      override fun start() {
        if (!canPlay()) return
        try { super.start(); markPlaybackActive(isPlaying) }
        catch (error: RuntimeException) { markPlaybackActive(false); throw error }
      }
      override fun pause() { try { super.pause() } finally { markPlaybackActive(false) } }
      override fun stopPlayback() { try { super.stopPlayback() } finally { markPlaybackActive(false) } }
    }.also { view ->
      content.addView(view, LinearLayout.LayoutParams(-1, LabUi.dp(this, 300)))
      view.setMediaController(MediaController(this).apply { setAnchorView(view) })
      view.setAudioFocusRequest(AudioManager.AUDIOFOCUS_GAIN)
      view.setOnPreparedListener {
        if (token != generation) return@setOnPreparedListener
        prepared = true
        status.text = if (videoParts.isEmpty()) "Video" else {
          val part = videoParts[videoPartIndex]
          "Video part ${part.index + 1} · ${videoParts.size} saved parts" +
            if ((videoPartIndex == 0 && part.index != 0) ||
              (videoPartIndex > 0 && part.index != videoParts[videoPartIndex - 1].index + 1)) " · Earlier parts are missing" else ""
        }
        maybeAutoPlay()
        videoHandoff = false
        markPlaybackActive(view.isPlaying)
        refreshControls()
      }
      view.setOnCompletionListener {
        if (token != generation) return@setOnCompletionListener
        if (videoParts.isNotEmpty() && videoPartIndex < videoParts.lastIndex && canPlay()) {
          val current = videoParts[videoPartIndex]
          val next = videoParts[videoPartIndex + 1]
          if (next.index == current.index + 1) openVideoPart(videoPartIndex + 1, true)
          else {
            markPlaybackActive(false); autoPlayRequested = false; play.text = "Play"
            status.text = "A video part is missing. Use Next part to continue with the saved recording."
          }
        } else { markPlaybackActive(false); autoPlayRequested = false; play.text = "Play" }
      }
      view.setOnErrorListener { _, _, _ ->
        if (token != generation) return@setOnErrorListener true
        markPlaybackActive(false)
        videoHandoff = false
        prepared = false
        autoPlayRequested = false
        status.text = "This video could not be played."
        refreshControls()
        true
      }
      view.setVideoURI(uri)
    }
    play.visibility = View.VISIBLE
  }

  private fun prepareAudio(uri: Uri, token: Long) {
    status.text = "Preparing audio…"
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
      .setOnAudioFocusChangeListener { change ->
        if (token == generation && change < 0) { autoPlayRequested = false; pausePlayback(); refreshControls() }
      }.build()
    val nextPlayer = MediaPlayer()
    player = nextPlayer
    nextPlayer.also { media ->
      media.setAudioAttributes(attributes)
      media.setDataSource(this, uri)
      media.setOnPreparedListener {
        if (token != generation) return@setOnPreparedListener
        prepared = true
        seek.max = it.duration
        status.text = "Audio from the glasses microphone"
        maybeAutoPlay()
        refreshControls()
      }
      media.setOnCompletionListener {
        if (token != generation) return@setOnCompletionListener
        markPlaybackActive(false); autoPlayRequested = false; play.text = "Play"
      }
      media.setOnErrorListener { _, _, _ ->
        if (token != generation) return@setOnErrorListener true
        markPlaybackActive(false)
        prepared = false
        autoPlayRequested = false
        status.text = "This audio could not be played."
        refreshControls()
        true
      }
      media.prepareAsync()
    }
    play.visibility = View.VISIBLE
    seek.visibility = View.VISIBLE
  }

  private fun canPlay() = boundForPlayback && visibleForPlayback &&
    !controller.recordings.busy && !controller.audio.callActive

  private fun maybeAutoPlay() {
    if (autoPlayRequested && prepared && canPlay()) {
      autoPlayRequested = false
      startPlayback()
    }
  }

  private fun startPlayback() {
    if (!prepared || !canPlay()) return
    try {
      if (mediaKind == "mp4") video?.start()
      else if (focus?.let { audio.requestAudioFocus(it) } == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        player?.start()
        markPlaybackActive(player?.isPlaying == true)
      }
    } catch (error: RuntimeException) {
      markPlaybackActive(false)
      prepared = false
      status.text = "Playback failed: ${error.message}"
    }
  }

  private fun pausePlayback() {
    videoHandoff = false
    try {
      if (prepared && player?.isPlaying == true) player?.pause()
      video?.pause()
      focus?.let { audio.abandonAudioFocusRequest(it) }
    } finally { markPlaybackActive(false) }
  }

  private fun refreshControls() {
    val available = canPlay()
    LabUi.enabled(play, prepared && available)
    LabUi.enabled(seek, prepared && available)
    LabUi.enabled(previousPart, available && videoPartIndex > 0)
    LabUi.enabled(nextPart, available && videoPartIndex < videoParts.lastIndex)
    if (!available) {
      if (boundForPlayback && (controller.recordings.busy || controller.audio.callActive)) autoPlayRequested = false
      pausePlayback()
    }
  }

  override fun onResume() {
    super.onResume()
    visibleForPlayback = true
    maybeAutoPlay()
    refreshControls()
  }
  override fun onPause() {
    visibleForPlayback = false
    autoPlayRequested = false
    pausePlayback()
    super.onPause()
  }
  override fun onStop() { visibleForPlayback = false; markPlaybackActive(false); super.onStop() }
  override fun onDestroy() {
    markPlaybackActive(false)
    generation++
    handler.removeCallbacksAndMessages(null)
    player?.release(); player = null
    video?.stopPlayback(); video = null
    scope.cancel()
    super.onDestroy()
  }
}
