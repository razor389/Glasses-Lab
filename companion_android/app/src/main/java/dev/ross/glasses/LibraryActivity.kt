package dev.ross.glasses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.*

/** A chronological library with transfer progress separate from media already saved. */
class LibraryActivity : CompanionActivity() {
  private lateinit var status: TextView
  private lateinit var items: LinearLayout
  private lateinit var pending: LinearLayout
  private lateinit var pendingCard: LinearLayout
  private lateinit var cancel: Button
  private lateinit var syncNow: Button
  private val filters = mutableMapOf<String, Button>()
  private var filter = "All"
  private var visibleLimit = 100
  private var signature = ""
  private var pendingDownload: String? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var groupJob: Job? = null
  private var videoSessions = emptyList<SavedVideoSession>()
  private var groupedVideoPaths = emptySet<String>()
  private val observer: () -> Unit = { refreshVideoGroups(); render() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    filter = savedInstanceState?.getString("filter") ?: "All"
    visibleLimit = savedInstanceState?.getInt("visible_limit", 100)?.coerceAtLeast(100) ?: 100
    val page = LabUi.page(this, "Library")
    page.addView(LabUi.button(this, "Back to capture") { finish() })
    page.addView(LabUi.text(this, "Your photos, videos, and audio recordings", 14, LabUi.muted))
    val filterRow = listOf("All", "Photos", "Video", "Audio").map { name ->
      LabUi.button(this, name) {
        filter = name
        visibleLimit = 100
        signature = ""
        render()
      }.apply {
        textSize = 13f
        setPadding(0, LabUi.dp(this@LibraryActivity, 8), 0, LabUi.dp(this@LibraryActivity, 8))
        this@LibraryActivity.filters[name] = this
      }
    }
    LabUi.row(this, page, *filterRow.toTypedArray())
    pendingCard = LabUi.card(this, page)
    pendingCard.addView(LabUi.text(this, "Transfer queue", 18).apply { typeface = Typeface.DEFAULT_BOLD })
    status = LabUi.text(this, "", 14, LabUi.muted)
    pendingCard.addView(status)
    syncNow = LabUi.button(this, "Connect media Wi-Fi") { controller.recordings.retryAutomaticTransfers() }
    pendingCard.addView(syncNow)
    cancel = LabUi.button(this, "Cancel transfer") { controller.recordings.cancelTransfer() }
    pendingCard.addView(cancel)
    pending = LabUi.column(this)
    pendingCard.addView(pending)
    items = LabUi.column(this)
    page.addView(items)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putString("filter", filter)
    outState.putInt("visible_limit", visibleLimit)
    super.onSaveInstanceState(outState)
  }
  override fun onCompanionReady() { controller.observe(observer); controller.recordings.refreshLibrary(); refreshVideoGroups(); render() }
  override fun onCompanionUnavailable() { controller.unobserve(observer) }

  private fun refreshVideoGroups() {
    if (groupJob?.isActive == true) return
    val store = controller.recordings.videoSessions
    groupJob = scope.launch {
      try {
        val (sessions, sources) = withContext(Dispatchers.IO) { store.list() to store.sources() }
        if (sessions != videoSessions || sources != groupedVideoPaths) {
          videoSessions = sessions
          groupedVideoPaths = sources
          signature = ""
          render()
        }
      } catch (error: Exception) {
        if (error !is CancellationException) status.text = "Video grouping unavailable: ${error.message}. Individual media is retained."
      }
    }
  }

  private fun render() {
    val recordings = controller.recordings
    status.text = recordings.mediaWifiMessage + "\n" + recordings.syncMessage.ifBlank { recordings.message }
    syncNow.text = if (recordings.mediaWifiReady) "Sync now" else "Connect media Wi-Fi"
    LabUi.enabled(syncNow, controller.canCapture)
    cancel.visibility = if (recordings.downloading) View.VISIBLE else View.GONE
    pendingCard.visibility = if (recordings.pendingVideos.isNotEmpty() || recordings.downloading || recordings.syncMessage.isNotBlank()) View.VISIBLE else View.GONE
    filters.forEach { (name, button) ->
      button.setTextColor(if (name == filter) LabUi.background else LabUi.muted)
      button.background = LabUi.shape(this, if (name == filter) LabUi.accent else LabUi.surfaceRaised, 14)
      button.isSelected = name == filter
      button.contentDescription = "$name${if (name == filter) ", selected" else ""}"
    }
    // Flat WAVs predate indexed sessions. Keep them visible after publication,
    // while numbered session parts remain grouped by their audio-session card.
    val published = recordings.mediaFiles.filter {
      it.sourcePath !in groupedVideoPaths &&
        (it.kind != MediaKind.AUDIO || File(it.sourcePath).parentFile?.name == "recordings")
    }
    val publishedPaths = recordings.mediaFiles.map { it.sourcePath }.toSet()
    val photos = File(filesDir, "photos").listFiles()?.filter { it.extension == "jpg" }.orEmpty()
    val files = (recordings.localFiles + photos).filterNot { it.canonicalPath in publishedPaths || it.canonicalPath in groupedVideoPaths }
    val nextSignature = filter + visibleLimit + recordings.pendingVideos.joinToString() + files.joinToString { "${it.name}:${it.length()}" } +
      published.toString() + recordings.busy + controller.canCapture + controller.audio.callActive + recordings.audioSessions.toString() + videoSessions.toString()
    if (signature == nextSignature) return
    signature = nextSignature
    pending.removeAllViews()
    recordings.pendingVideos.take(20).forEach { id ->
      val whenSaved = id.split('-').getOrNull(1)?.toLongOrNull() ?: 0
      pending.addView(LabUi.text(this, "Video · ${LabUi.date(whenSaved)}", 15))
      pending.addView(LabUi.text(this, recordings.pendingVideoDescription(id), 12, LabUi.muted))
      pending.addView(LabUi.button(this, "Retry saving to phone") { download(id) }.apply {
        LabUi.enabled(this, controller.canCapture)
      })
    }
    if (recordings.pendingVideos.size > 20) {
      pending.addView(LabUi.text(this, "${recordings.pendingVideos.size - 20} more videos are queued.", 13, LabUi.muted))
      pending.addView(LabUi.button(this, "Retry queued transfers") { recordings.retryAutomaticTransfers() }.apply {
        LabUi.enabled(this, controller.canCapture)
      })
    }
    items.removeAllViews()
    val entries = mutableListOf<Pair<Long, () -> Unit>>()
    if (filter in listOf("All", "Video")) videoSessions.forEach { session ->
      entries += session.createdAt to {
        val card = mediaCard("Video recording", session.createdAt, LabUi.video)
        card.addView(LabUi.text(this, "${LabUi.size(session.bytes)} · ${session.segments} saved ${if (session.segments == 1) "part" else "parts"}", 15))
        val state = when (session.state) {
          "RECORDING" -> "Recording · completed parts save to the phone"
          "FINISHED" -> "Recording ended"
          "INTERRUPTED" -> "Interrupted · saved parts retained"
          else -> "Recovered recording"
        }
        card.addView(LabUi.text(this, state, 13, LabUi.muted))
        if (session.reason.isNotBlank()) card.addView(LabUi.text(this, PhoneVideoPlan.stopDescription(session.reason), 12, LabUi.muted))
        card.addView(LabUi.button(this, "Play recording") {
          startActivity(Intent(this, PlaybackActivity::class.java).putExtra("video_session", session.id))
        }.apply { LabUi.enabled(this, session.segments > 0 && !recordings.busy && !controller.audio.callActive) })
      }
    }
    if (filter in listOf("All", "Audio")) recordings.audioSessions.forEach { session ->
      entries += session.createdAt to {
        val card = mediaCard("Audio", session.createdAt, LabUi.audio)
        card.addView(LabUi.text(this, "${LabUi.duration(session.durationMs / 1000)} · ${LabUi.size(session.pcmBytes)}", 15))
        val state = when (session.state) {
          "FINISHED" -> "Saved"
          "INTERRUPTED" -> "Interrupted · available audio saved"
          "PAUSED" -> "Paused"
          "WAITING" -> "Waiting for glasses"
          "STARTING", "RECORDING" -> "Recording"
          else -> session.state.lowercase().replaceFirstChar { it.uppercase() }
        }
        card.addView(LabUi.text(this, state + if (session.gaps > 0) " · ${session.gaps} ${if (session.gaps == 1) "gap" else "gaps"}" else "", 13, LabUi.muted))
        card.addView(LabUi.button(this, "Open audio") {
          startActivity(Intent(this, AudioSessionActivity::class.java).putExtra("session", session.id))
        })
      }
    }
    published.forEach { media ->
      val kind = when (media.kind) { MediaKind.PHOTO -> "Photos"; MediaKind.VIDEO -> "Video"; MediaKind.AUDIO -> "Audio" }
      val photo = media.kind == MediaKind.PHOTO
      if (filter == "All" || filter == kind) {
        entries += media.createdAt to {
          val card = mediaCard(when (media.kind) { MediaKind.PHOTO -> "Photo"; MediaKind.VIDEO -> "Video"; MediaKind.AUDIO -> "Audio" },
            media.createdAt, when (media.kind) { MediaKind.PHOTO -> LabUi.accent; MediaKind.VIDEO -> LabUi.video; MediaKind.AUDIO -> LabUi.audio })
          card.addView(LabUi.text(this, "${LabUi.size(media.bytes)} · Saved to phone media", 13, LabUi.muted))
          val open = LabUi.button(this, when (media.kind) { MediaKind.PHOTO -> "View photo"; MediaKind.VIDEO -> "Play video"; MediaKind.AUDIO -> "Play audio" }) {
            startActivity(Intent(this, PlaybackActivity::class.java).putExtra("media", media.sourcePath))
          }.apply { LabUi.enabled(this, photo || (!recordings.busy && !controller.audio.callActive)) }
          val share = LabUi.button(this, "Share") {
            share(media.uri, when (media.kind) { MediaKind.PHOTO -> "image/jpeg"; MediaKind.VIDEO -> "video/mp4"; MediaKind.AUDIO -> "audio/wav" })
          }
          LabUi.row(this, card, open, share)
        }
      }
    }
    files.forEach { file ->
      val kind = when (file.extension) { "mp4" -> "Video"; "wav" -> "Audio"; else -> "Photos" }
      if (filter == "All" || filter == kind) {
        entries += file.lastModified() to {
          val card = mediaCard(when (kind) { "Photos" -> "Photo"; "Video" -> "Video"; else -> "Audio" },
            file.lastModified(), when (kind) { "Photos" -> LabUi.accent; "Video" -> LabUi.video; else -> LabUi.audio })
          card.addView(LabUi.text(this, "${LabUi.size(file.length())} · Saved in Glasses Lab", 13, LabUi.muted))
          val open = LabUi.button(this, if (kind == "Photos") "View photo" else "Play") {
            startActivity(Intent(this, PlaybackActivity::class.java).putExtra("file", "${file.parentFile!!.name}/${file.name}"))
          }.apply { LabUi.enabled(this, kind == "Photos" || (!recordings.busy && !controller.audio.callActive)) }
          val share = LabUi.button(this, "Share") {
            share(FileProvider.getUriForFile(this, "$packageName.files", file),
              when (file.extension) { "mp4" -> "video/mp4"; "wav" -> "audio/wav"; else -> "image/jpeg" })
          }
          LabUi.row(this, card, open, share)
        }
      }
    }
    entries.sortedByDescending { it.first }.take(visibleLimit).forEach { it.second() }
    if (entries.isEmpty()) {
      val empty = LabUi.card(this, items)
      empty.addView(LabUi.text(this, if (filter == "All") "Your next moment starts here" else "No ${filter.lowercase()} yet", 20))
      empty.addView(LabUi.text(this, "Capture from your glasses. Saved media will appear here.", 14, LabUi.muted))
    }
    val moreAudio = filter in listOf("All", "Audio") && recordings.audioSessions.size >= recordings.libraryLimit
    if (entries.size > visibleLimit || moreAudio) {
      items.addView(LabUi.button(this, "Show more") {
        visibleLimit += 100
        signature = ""
        if (moreAudio) recordings.loadMore()
        render()
      })
    }
  }

  private fun mediaCard(title: String, timestamp: Long, color: Int): LinearLayout = LabUi.card(this, items).also {
    it.addView(LabUi.text(this, title, 19, color).apply { typeface = Typeface.DEFAULT_BOLD })
    it.addView(LabUi.text(this, LabUi.date(timestamp), 13, LabUi.muted))
  }

  private fun share(uri: android.net.Uri, mimeType: String) {
    runCatching {
      startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share media"))
    }.onFailure { Toast.makeText(this, "Could not share this file: ${it.message}", Toast.LENGTH_LONG).show() }
  }

  private fun download(id: String) {
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
      pendingDownload = id
      requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 3)
      return
    }
    try {
      check(controller.canCapture) { "Connect and finish any call or capture before transferring." }
      controller.recordings.downloadVideo(id)
    } catch (error: Exception) { Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, results)
    val id = pendingDownload
    pendingDownload = null
    if (requestCode == 3 && id != null) {
      if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) download(id)
      else Toast.makeText(this, "Allow Nearby Wi-Fi devices to save videos to your phone.", Toast.LENGTH_LONG).show()
    }
  }

  override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
