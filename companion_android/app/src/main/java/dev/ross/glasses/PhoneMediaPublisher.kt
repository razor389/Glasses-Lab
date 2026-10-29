package dev.ross.glasses

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException

enum class MediaKind { PHOTO, VIDEO, AUDIO }

data class PublishedMedia(
  val sourcePath: String, val uri: Uri, val bytes: Long, val sha256: String,
  val kind: MediaKind, val displayName: String, val createdAt: Long,
) { val file: File get() = File(sourcePath) }

/**
 * Publishes immutable completed files into phone media, with a durable retry journal.
 * Every method except the cached counters performs blocking I/O; call from an IO worker.
 * Publication never deletes private input. Owners opt into reclamation after adapting their index.
 */
class PhoneMediaPublisher(
  private val context: Context,
  databaseName: String = "media-publications.sqlite",
  sourceRoots: List<File> = listOf(File(context.filesDir, "photos"), File(context.filesDir, "recordings")),
  private val albumName: String = "Glasses Lab",
  private val checkpoint: (String) -> Unit = {},
) : Closeable {
  private val resolver = context.contentResolver
  private val store = MediaPublicationStore(context, databaseName)
  private val roots = sourceRoots.map { it.canonicalFile }
  private val cachedRows = mutableMapOf<String, Pair<MediaKind, Long>>()

  /** Safe to read while the recording storage guard holds its own lock. */
  @Volatile var cachedRecordingBytes = 0L
    private set
  @Volatile var cachedTotalBytes = 0L
    private set

  init { require(albumName.matches(Regex("[A-Za-z0-9 _-]{1,80}"))) }

  @Synchronized fun publish(
    file: File, kind: MediaKind, displayName: String? = null,
    capacity: (Long) -> Unit = {}, checkCancelled: () -> Unit = {},
  ): PublishedMedia {
    check(Build.VERSION.SDK_INT >= 29) { "Phone media saving requires Android 10 or newer. The private original is retained." }
    val source = owned(file)
    validateExtension(source, kind)
    var entry = store.find(source.path)
    check(entry?.state !in listOf("MISSING", "DELETED")) {
      "This item was removed from phone media; it will not be silently recreated."
    }
    if (entry != null) {
      check(entry.kind == kind) { "Media type changed; original retained." }
      if (source.exists()) check(source.inputStream().use { MediaFingerprint.read(it, checkCancelled) } == fingerprint(entry)) {
        "Completed source changed; original and published media retained."
      }
    } else {
      check(source.isFile && source.length() > 0) { "The completed media file is missing or empty." }
      val digest = source.inputStream().use { MediaFingerprint.read(it, checkCancelled) }
      val token = UUID.randomUUID().toString().replace("-", "")
      val created = source.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
      entry = MediaPublicationEntry(source.path, token, null, digest.bytes, digest.sha256, kind,
        name(source, displayName, token, created), relativePath(kind), created, "PREPARED")
      store.insert(entry)
      checkpoint("prepared")
    }
    return complete(entry, source, capacity, checkCancelled)
  }

  @Synchronized fun lookup(file: File): PublishedMedia? = store.find(owned(file).path)
    ?.takeIf { it.state == "PUBLISHED" && it.uri != null }?.let(::published)

  /** Prevents automatic sync from recreating a copy that the user removed. */
  @Synchronized fun isSuppressed(file: File): Boolean = store.find(owned(file).path)?.state in listOf("MISSING", "DELETED")

  @Synchronized fun list(kind: MediaKind? = null): List<PublishedMedia> = store.all()
    .filter { it.state == "PUBLISHED" && it.uri != null && (kind == null || it.kind == kind) }.map(::published)

  @Synchronized fun isAvailable(publication: PublishedMedia): Boolean = runCatching {
    val entry = matching(publication)
    val info = row(publication.uri) ?: return@runCatching false
    info.owner == context.packageName && !info.pending && entry.state == "PUBLISHED"
  }.getOrDefault(false)

  /** A fresh end-to-end check, suitable before sending a destructive glasses receipt. */
  @Synchronized fun verify(publication: PublishedMedia, checkCancelled: () -> Unit = {}): Boolean = try {
    val entry = matching(publication)
    entry.state == "PUBLISHED" && verifies(entry, publication.uri, checkCancelled) && row(publication.uri)?.pending == false
  } catch (error: Exception) {
    if (error is CancellationException) throw error
    false
  }

  @Synchronized fun openInput(file: File): InputStream {
    val source = owned(file)
    lookup(source)?.let { publication ->
      if (isAvailable(publication)) {
        try { return resolver.openInputStream(publication.uri) ?: throw FileNotFoundException("Published media is unavailable.") }
        catch (_: FileNotFoundException) { /* A retained private original can still be played. */ }
      }
    }
    return source.inputStream()
  }

  /** Removes only the byte-identical private source after rechecking its committed public copy. */
  @Synchronized fun removePrivateCopy(file: File, checkCancelled: () -> Unit = {}): Boolean {
    val source = owned(file)
    val publication = lookup(source) ?: return false
    check(verify(publication, checkCancelled)) { "Phone media verification failed; private original retained." }
    if (!source.exists()) return true
    val beforeLength = source.length()
    val beforeModified = source.lastModified()
    check(source.inputStream().use { MediaFingerprint.read(it, checkCancelled) } == MediaFingerprint(publication.bytes, publication.sha256)) {
      "Private source changed; both copies retained."
    }
    check(source.length() == beforeLength && source.lastModified() == beforeModified) { "Source is still changing; original retained." }
    checkCancelled()
    return source.delete().also { check(it) { "Verified media is saved, but the private copy could not be removed." } }
  }

  /** Explicit user deletion only. It cannot delete a URI missing from our journal. */
  @Synchronized fun deletePublished(file: File): Boolean {
    val entry = store.find(owned(file).path) ?: return false
    if (entry.state in listOf("MISSING", "DELETED")) return true
    val uri = entry.uri?.let(Uri::parse) ?: findInserted(entry)
    val info = uri?.let(::row)
    if (uri != null && info != null) {
      val ownedPartial = info.pending && entry.state != "PUBLISHED" && info.owner == context.packageName &&
        info.displayName == entry.displayName && info.relativePath == entry.relativePath
      check(ownedPartial || verifies(entry, uri)) { "Phone media changed or cannot be verified; it was not deleted." }
      check(resolver.delete(uri, null, null) == 1) { "Phone media could not be deleted." }
    }
    store.update(entry, "DELETED", uri?.toString())
    cache(entry, 0)
    return true
  }

  /** Reconcile external Gallery deletion without automatically bringing deleted media back. */
  @Synchronized fun reconcile(): List<String> {
    val errors = mutableListOf<String>()
    store.all().forEach { entry ->
      try {
        if (entry.state == "PUBLISHED" && entry.uri != null && row(Uri.parse(entry.uri)) == null) {
          store.update(entry, "MISSING")
        }
      } catch (error: Exception) { errors.add("${entry.displayName}: ${error.message}") }
    }
    trackedBytes(false)
    return errors
  }

  /** Retry interrupted publication; caller supplies the allowance for each media kind. */
  @Synchronized fun recover(
    capacity: (MediaKind, Long) -> Unit = { _, _ -> }, checkCancelled: () -> Unit = {},
  ): List<String> {
    val errors = reconcile().toMutableList()
    store.all().filter { it.state !in listOf("PUBLISHED", "MISSING", "DELETED") }.forEach { entry ->
      checkCancelled()
      try { publish(File(entry.sourcePath), entry.kind, capacity = { capacity(entry.kind, it) }, checkCancelled = checkCancelled) }
      catch (error: Exception) {
        if (error is CancellationException) throw error
        errors.add("${entry.displayName}: ${error.message}")
      }
    }
    trackedBytes(false)
    return errors
  }

  /** Actual public/pending allocation, including copies whose publication was interrupted. */
  @Synchronized fun trackedBytes(recordingsOnly: Boolean = true): Long {
    cachedRows.clear()
    store.all().forEach { entry ->
      val bytes = if (entry.state in listOf("MISSING", "DELETED")) 0 else try {
        val uri = entry.uri?.let(Uri::parse) ?: findInserted(entry)
        if (uri == null || row(uri) == null) 0 else physicalSize(uri, entry.bytes)
      } catch (_: Exception) { entry.bytes } // Unknown allocation stays charged until reconciled.
      cachedRows[entry.sourcePath] = entry.kind to bytes
    }
    updateCounters()
    return if (recordingsOnly) cachedRecordingBytes else cachedTotalBytes
  }

  private fun complete(original: MediaPublicationEntry, source: File, capacity: (Long) -> Unit, cancelled: () -> Unit): PublishedMedia {
    var entry = original
    var uri = entry.uri?.let(Uri::parse)
    // A committed row is immutable. Missing/corrupt content, or a provider/user
    // changing its visibility, must never turn a retry into a fresh overwrite.
    if (entry.state == "PUBLISHED") {
      val destination = checkNotNull(uri) { "Published media identity is missing; original retained." }
      check(verifies(entry, destination, cancelled) && row(destination)?.pending == false) {
        "Published phone media changed or is unavailable; original retained."
      }
      cache(entry, entry.bytes)
      return published(entry)
    }
    if (uri != null && row(uri) == null) {
      entry = store.update(entry, uri = null)
      uri = null
    }
    if (uri == null) {
      uri = findInserted(entry) ?: resolver.insert(collection(entry.kind), ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, entry.displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mime(entry.kind))
        put(MediaStore.MediaColumns.RELATIVE_PATH, entry.relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
        put(MediaStore.MediaColumns.DATE_ADDED, entry.createdAt / 1000)
      }) ?: throw IOException("Phone media storage could not create a file.")
      checkpoint("pending_inserted") // Recovery finds the exact random name if URI journaling was interrupted.
      entry = store.update(entry, "COPYING", uri.toString())
    }
    val destination = uri
    try {
      if (!verifies(entry, destination, cancelled)) {
        val info = row(destination) ?: throw IOException("Pending phone media disappeared.")
        check(info.owner == context.packageName && info.pending &&
          info.displayName == entry.displayName && info.relativePath == entry.relativePath) {
          "Phone media identity or visibility changed; original retained."
        }
        check(source.isFile) { "Private source is unavailable; pending media retained for recovery." }
        entry = store.update(entry, "COPYING", destination.toString())
        copy(source, destination, entry, capacity, cancelled)
        check(verifies(entry, destination, cancelled)) { "Phone media checksum did not match; private original retained." }
      }
      // Also sync after process-restart recovery of a completed but uncommitted copy.
      resolver.openFileDescriptor(destination, "rw")?.use { it.fileDescriptor.sync() }
        ?: throw IOException("Phone media could not be synced.")
      entry = store.update(entry, "VERIFIED", destination.toString())
      checkpoint("verified")
      check(resolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
        "Phone media could not be made visible; original retained."
      }
      checkpoint("visible")
      entry = store.update(entry, "PUBLISHED", destination.toString())
      cache(entry, entry.bytes)
      checkpoint("committed")
      return published(entry)
    } finally {
      cache(entry, try { if (row(destination) == null) 0 else physicalSize(destination, entry.bytes) } catch (_: Exception) { entry.bytes })
    }
  }

  private fun copy(source: File, uri: Uri, entry: MediaPublicationEntry, capacity: (Long) -> Unit, cancelled: () -> Unit) {
    val outputDescriptor = resolver.openFileDescriptor(uri, "rwt") ?: throw IOException("Phone media is not writable.")
    ParcelFileDescriptor.AutoCloseOutputStream(outputDescriptor).use { output ->
      cache(entry, 0)
      val digest = MessageDigest.getInstance("SHA-256")
      var count = 0L
      source.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          cancelled()
          val size = input.read(buffer)
          if (size < 0) break
          if (size == 0) continue
          capacity(size.toLong())
          output.write(buffer, 0, size)
          digest.update(buffer, 0, size)
          count = Math.addExact(count, size.toLong())
          cache(entry, count)
        }
      }
      output.flush()
      outputDescriptor.fileDescriptor.sync()
      check(count == entry.bytes && source.length() == entry.bytes &&
        digest.digest().joinToString("") { "%02x".format(it) } == entry.sha256) {
        "Source changed during publication; private original retained."
      }
    }
  }

  private data class Row(val owner: String?, val pending: Boolean, val size: Long, val displayName: String, val relativePath: String)
  private fun row(uri: Uri): Row? = (resolver.query(uri,
    arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE,
      MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
    null, null, null,
  ) ?: throw IOException("Phone media provider is temporarily unavailable.")).use {
    if (!it.moveToFirst()) null else Row(it.getString(0), it.getInt(1) != 0, it.getLong(2), it.getString(3), it.getString(4))
  }

  @Suppress("DEPRECATION")
  private fun findInserted(entry: MediaPublicationEntry): Uri? = (resolver.query(
    MediaStore.setIncludePending(collection(entry.kind)), arrayOf(MediaStore.MediaColumns._ID),
    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
    arrayOf(entry.displayName, entry.relativePath, context.packageName), null,
  ) ?: throw IOException("Phone media provider is temporarily unavailable.")).use {
    if (!it.moveToFirst()) null else {
      val id = it.getLong(0)
      check(!it.moveToNext()) { "Ambiguous pending media identity; all copies retained." }
      ContentUris.withAppendedId(collection(entry.kind), id)
    }
  }

  private fun verifies(entry: MediaPublicationEntry, uri: Uri, cancelled: () -> Unit = {}): Boolean {
    val info = row(uri) ?: return false
    if (info.owner != context.packageName) return false
    return try {
      resolver.openInputStream(uri)?.use { MediaFingerprint.read(it, cancelled) == fingerprint(entry) } ?: false
    } catch (_: FileNotFoundException) {
      // MediaStore can insert a pending row before its payload exists. Only
      // complete()'s owned, uncommitted pending-row guard may proceed to copying.
      false
    }
  }

  private fun physicalSize(uri: Uri, fallback: Long): Long = try {
    resolver.openFileDescriptor(uri, "r")?.use {
      it.statSize.takeIf { size -> size >= 0 } ?: row(uri)?.size?.takeIf { size -> size >= 0 } ?: fallback
    } ?: fallback
  } catch (error: FileNotFoundException) {
    val info = row(uri)
    if (info == null || (info.owner == context.packageName && info.pending && info.size == 0L)) 0
    else throw error // Unknown allocation stays charged by the caller.
  }

  private fun matching(media: PublishedMedia): MediaPublicationEntry {
    val entry = store.find(owned(media.file).path) ?: error("Unknown media publication.")
    check(entry.uri == media.uri.toString() && entry.sha256 == media.sha256 && entry.bytes == media.bytes && entry.kind == media.kind) {
      "Publication identity does not match."
    }
    return entry
  }

  private fun owned(file: File): File = file.canonicalFile.also { path ->
    require(roots.any { path.path.startsWith(it.path + File.separator) }) { "Media source is outside owned capture storage." }
    require(!path.name.endsWith(".part")) { "Active or partial media cannot be published." }
  }

  private fun validateExtension(file: File, kind: MediaKind) {
    require(file.extension.lowercase(Locale.US) in when (kind) {
      MediaKind.PHOTO -> listOf("jpg", "jpeg")
      MediaKind.VIDEO -> listOf("mp4")
      MediaKind.AUDIO -> listOf("wav")
    }) { "Media type does not match the completed file." }
  }

  private fun name(source: File, requested: String?, token: String, created: Long): String {
    val label = (requested ?: source.nameWithoutExtension).substringBeforeLast('.', requested ?: source.nameWithoutExtension)
      .replace(Regex("[^A-Za-z0-9 _-]"), "_").take(64).ifBlank { "Recording" }
    val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(created))
    return "${date}_${label}_${token}.${source.extension.lowercase(Locale.US)}"
  }

  private fun relativePath(kind: MediaKind) = when (kind) {
    MediaKind.PHOTO -> Environment.DIRECTORY_PICTURES
    MediaKind.VIDEO -> Environment.DIRECTORY_MOVIES
    MediaKind.AUDIO -> Environment.DIRECTORY_MUSIC
  } + "/$albumName/"
  private fun collection(kind: MediaKind): Uri = when (kind) {
    MediaKind.PHOTO -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    MediaKind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
  }
  private fun mime(kind: MediaKind) = when (kind) { MediaKind.PHOTO -> "image/jpeg"; MediaKind.VIDEO -> "video/mp4"; MediaKind.AUDIO -> "audio/wav" }
  private fun fingerprint(entry: MediaPublicationEntry) = MediaFingerprint(entry.bytes, entry.sha256)
  private fun published(entry: MediaPublicationEntry) = PublishedMedia(entry.sourcePath, Uri.parse(checkNotNull(entry.uri)),
    entry.bytes, entry.sha256, entry.kind, entry.displayName, entry.createdAt)
  private fun cache(entry: MediaPublicationEntry, bytes: Long) {
    val previous = cachedRows.put(entry.sourcePath, entry.kind to bytes)
    cachedTotalBytes = Math.addExact(cachedTotalBytes - (previous?.second ?: 0), bytes)
    cachedRecordingBytes = Math.addExact(cachedRecordingBytes -
      (previous?.takeIf { it.first != MediaKind.PHOTO }?.second ?: 0), if (entry.kind == MediaKind.PHOTO) 0 else bytes)
  }
  private fun updateCounters() {
    cachedTotalBytes = cachedRows.values.fold(0L) { total, item -> Math.addExact(total, item.second) }
    cachedRecordingBytes = cachedRows.values.filter { it.first != MediaKind.PHOTO }.fold(0L) { total, item -> Math.addExact(total, item.second) }
  }
  @Synchronized override fun close() { store.close() }
}
