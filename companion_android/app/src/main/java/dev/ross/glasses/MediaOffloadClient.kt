package dev.ross.glasses

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Network
import com.google.gson.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Authenticated local transfer; destructive acknowledgement follows durable phone-media verification. */
class MediaOffloadClient(
  private val context: Context, private val network: Network, private val ip: String, private val token: String,
  private val media: PhoneMediaPublisher, private val settings: RecordingSettings,
) {
  init { require(VideoGallery.privateIpv4(ip)); require(token.matches(Regex("[0-9a-f]{64}"))) }
  private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
  private fun query(file: RemoteMedia) = "file=${encode(file.name)}&location=${encode(file.location)}"
  private fun open(path: String) = (network.openConnection(URL("http://$ip:8089/api/lab/v1/$path")) as HttpURLConnection).apply {
    connectTimeout = 5000; readTimeout = 20_000; instanceFollowRedirects = false
    setRequestProperty("Authorization", "Bearer $token")
  }
  private fun json(path: String, body: JsonObject? = null): String {
    val connection = open(path)
    try {
      if (body != null) {
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
      }
      check(connection.responseCode == 200) {
        if (connection.responseCode == 404) "Install the current glasses build to enable verified media sync."
        else "Glasses media request failed (${connection.responseCode}); originals retained."
      }
      return connection.inputStream.bufferedReader().use(VideoGallery::read)
    } finally { connection.disconnect() }
  }
  fun page(cursor: String?): RemoteMediaPage = RemoteMediaCatalog.page(json("manifest?limit=50" +
    (cursor?.let { "&cursor=${encode(it)}" } ?: "")))

  fun supportsSegmentedRecording(): Boolean = page(null).segmentedRecordingVersion == 1

  fun auxiliaryCleanup(preview: AuxiliaryCleanupSummary? = null): AuxiliaryCleanupSummary {
    val connection = open("auxiliary-cleanup")
    try {
      if (preview != null) {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val body = JsonObject().apply { addProperty("confirm", true); addProperty("cleanup_id", preview.id) }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
      }
      check(connection.responseCode == 200) {
        when (connection.responseCode) {
          404 -> "Update the glasses build to clean auxiliary files."
          409 -> "The glasses are busy or the files changed. Wait for captures to finish, then check again."
          401 -> "Reconnect media Wi-Fi before cleaning auxiliary files."
          else -> "Cleanup was not confirmed. Check remaining files before trying again."
        }
      }
      val result = connection.inputStream.bufferedReader().use { AuxiliaryCleanupSummary.parse(VideoGallery.read(it), preview != null) }
      if (preview != null) check(result.id == preview.id && result.files == preview.files && result.bytes == preview.bytes) {
        "Cleanup response did not match your confirmation. Check remaining files."
      }
      return result
    } finally { connection.disconnect() }
  }

  /** Same-ID renewal is idempotent; a consumed reservation can never authorize another start. */
  fun recordingReady(grant: PhoneRecordingGrant, ttlSeconds: Int) {
    val connection = open("recording-ready")
    try {
      connection.requestMethod = "POST"; connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json")
      connection.outputStream.use { it.write(grant.body(ttlSeconds).toString().toByteArray(Charsets.UTF_8)) }
      val response = (if (connection.responseCode == 200) connection.inputStream else connection.errorStream)
        ?.bufferedReader()?.use(VideoGallery::read).orEmpty()
      if (connection.responseCode == 409 && response.contains("grant_consumed")) throw PhoneRecordingGrantConsumed()
      check(connection.responseCode == 200) { "Phone recording readiness was not confirmed (${connection.responseCode})." }
      val data = RemoteMediaCatalog.data(response)
      check(data["grantId"]?.asString == grant.grantId && data["sessionId"]?.asString == grant.sessionId &&
        data["profileRevision"]?.asString == grant.revision && data["maxSessionBytes"]?.asLong == grant.maxSessionBytes &&
        data["consumed"]?.asBoolean == false &&
        (data["expires_in_seconds"]?.asInt ?: -1) == ttlSeconds) { "Glasses did not confirm the recording reservation." }
    } finally { connection.disconnect() }
  }

  /** Caller runs on IO. Returns a phone publication only after the exact source was acknowledged. */
  suspend fun transfer(
    file: RemoteMedia,
    beforeReclaim: suspend (RemoteMedia, PublishedMedia) -> Unit = { _, _ -> },
    progress: (Int) -> Unit,
  ): PublishedMedia? {
    val job = currentCoroutineContext()
    val directory = File(context.filesDir, if (file.photo) "photos" else "recordings").apply { mkdirs() }
    val target = File(directory, file.localName)
    job.ensureActive()
    // Remove only our exact uncommitted retry scratch before charging a new two-copy transfer.
    if (VideoFileTransfer.discardStaleOffloadPartial(directory, target)) settings.guard().invalidate()
    // Respect explicit deletion from Android Gallery, including a failed previous receipt.
    if (media.isSuppressed(target)) return null
    val hash = RemoteMediaCatalog.data(json("hash?${query(file)}"))
    check(hash.get("generation")?.asString == file.generation && hash.get("size")?.asLong == file.bytes) {
      "Glasses media changed; it will be retried from a new manifest."
    }
    val expectedHash = hash.get("sha256")?.asString.orEmpty()
    check(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "Glasses did not supply a valid checksum." }
    val kind = if (file.photo) MediaKind.PHOTO else MediaKind.VIDEO
    val published = media.lookup(target)
    if (published == null) {
      // Allow the full receive plus MediaStore staging copy, without consuming the reserve.
      val additional = Math.multiplyExact(file.bytes, if (target.exists()) 1L else 2L)
      if (file.photo) check(directory.usableSpace - settings.policy().reserveBytes >= additional) { "Phone space is low. Photos remain on the glasses." }
      else settings.guard().checkCapacity(additional)
      if (!target.exists()) {
        val connection = open("file?${query(file)}")
        try {
          connection.setRequestProperty("If-Match", file.generation)
          check(connection.responseCode == 200) { "Glasses media changed or could not be downloaded; original retained." }
          connection.inputStream.use { input ->
            VideoFileTransfer.save(input, target, file.bytes, {
              validate(it, file.photo)
              val digest = it.inputStream().use { input -> MediaFingerprint.read(input, job::ensureActive) }
              check(digest.bytes == file.bytes && digest.sha256 == expectedHash) { "Download checksum mismatch; original retained." }
            }, job::ensureActive,
              progress, { capacity(kind, it) }, maxBytes = Long.MAX_VALUE)
          }
          if (file.modified > 0) target.setLastModified(file.modified)
        } finally { connection.disconnect() }
      }
      val fingerprint = target.inputStream().use { MediaFingerprint.read(it, job::ensureActive) }
      check(fingerprint.bytes == file.bytes && fingerprint.sha256 == expectedHash) { "Download checksum mismatch; glasses original retained." }
    }
    job.ensureActive()
    val copy = published ?: media.publish(target, kind, capacity = { capacity(kind, it) }, checkCancelled = job::ensureActive)
    check(copy.bytes == file.bytes && copy.sha256 == expectedHash && media.verify(copy, job::ensureActive)) { "Phone media verification failed; glasses original retained." }
    VerifiedMediaReceipt.commit(file, MediaFingerprint(copy.bytes, copy.sha256), {
      beforeReclaim(file, copy)
      job.ensureActive()
      media.removePrivateCopy(target, job::ensureActive)
      settings.guard().invalidate()
      job.ensureActive()
    }) { receipt -> json("receipt", receipt) }
    return copy
  }
  private fun capacity(kind: MediaKind, bytes: Long) {
    if (kind != MediaKind.PHOTO) settings.guard().requireCapacity(bytes)
    else check(context.filesDir.usableSpace - settings.policy().reserveBytes >= bytes) { "Phone space is low; original retained." }
  }
  private fun validate(file: File, photo: Boolean) {
    if (photo) {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.path, bounds)
      check(bounds.outWidth > 0 && bounds.outHeight > 0) { "The received photo is not readable." }
    } else {
      val extractor = MediaExtractor()
      try {
        extractor.setDataSource(file.path)
        val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        VideoFileTransfer.validateVideoTracks(types)
      } finally { extractor.release() }
    }
  }
}
