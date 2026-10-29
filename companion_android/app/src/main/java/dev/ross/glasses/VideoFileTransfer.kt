package dev.ross.glasses

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files

/** Publishes a recording only after an exact-length transfer and media validation succeed. */
object VideoFileTransfer {
  /** A killed process can leave only this private retry scratch file, never a committed capture. */
  fun discardStaleOffloadPartial(directory: File, target: File): Boolean {
    val root = directory.canonicalFile
    require(target.name.matches(Regex("glasses-[0-9a-f]{64}\\.(jpg|mp4)"))) { "Unknown transfer identity." }
    require(target.parentFile?.canonicalFile == root) { "Transfer is outside owned media storage." }
    val exactTarget = File(root, target.name)
    check(!Files.isSymbolicLink(exactTarget.toPath()) && exactTarget.canonicalFile == exactTarget) { "Transfer target must not be a symbolic link." }
    val partial = File(root, target.name + ".part")
    check(!Files.isSymbolicLink(partial.toPath()) && partial.canonicalFile == partial) { "Transfer scratch must not be a symbolic link." }
    if (!partial.exists()) return false
    check(partial.isFile && partial.delete()) { "Could not remove interrupted transfer scratch." }
    return true
  }

  /** Historical camera videos can legitimately be silent; an audio-only file is not a video. */
  fun validateVideoTracks(mimeTypes: Iterable<String>) {
    check(mimeTypes.any { it.startsWith("video/") }) { "The received file has no readable video track." }
  }

  fun save(
    input: InputStream,
    target: File,
    expectedBytes: Long,
    validate: (File) -> Unit,
    checkActive: () -> Unit = {},
    progress: (Int) -> Unit = {},
    capacity: (Long) -> Unit = {},
    maxBytes: Long = VideoGallery.MAX_BYTES,
  ): File {
    require(expectedBytes in 1..maxBytes) { "Unexpected media length." }
    check(!target.exists()) { "A saved recording already exists." }
    val partial = File(target.parentFile, target.name + ".part")
    try {
      var copied = 0L
      var lastPercent = -1
      FileOutputStream(partial).use { output ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          checkActive()
          val count = input.read(buffer)
          if (count < 0) break
          copied = Math.addExact(copied, count.toLong())
          check(copied <= expectedBytes) { "Unexpected video length." }
          capacity(count.toLong())
          output.write(buffer, 0, count)
          val percent = (100 * copied / expectedBytes).toInt()
          if (percent != lastPercent) { progress(percent); lastPercent = percent }
        }
        check(copied == expectedBytes) { "Video transfer was incomplete; retry the download." }
        output.fd.sync()
      }
      checkActive()
      validate(partial)
      checkActive()
      check(!target.exists() && partial.renameTo(target)) { "Could not save the video." }
      return target
    } finally {
      if (partial.exists()) partial.delete()
    }
  }
}
