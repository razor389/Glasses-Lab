package dev.ross.glasses

import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Streams an export bundle; cancellation never removes original segments. */
object AudioSessionExporter {
  fun save(segments: List<AudioSegment>, directory: File, manifest: String,
    capacity: (Long) -> Unit, checkCancelled: () -> Unit = {},
    openInput: (File) -> java.io.InputStream = { it.inputStream() }): File {
    require(segments.isNotEmpty()) { "No completed audio segments to export." }
    check(directory.isDirectory || directory.mkdirs())
    val target = File(directory, "audio-${UUID.randomUUID()}.zip")
    val partial = File(directory, target.name + ".part")
    check(partial.createNewFile())
    try {
      val description = manifest.toByteArray(Charsets.UTF_8)
      capacity(description.size + 512L)
      ZipOutputStream(partial.outputStream().buffered()).use { zip ->
        zip.setLevel(0) // Predictable space and CPU; original WAV bytes are preserved.
        zip.putNextEntry(ZipEntry("session.json")); zip.write(description); zip.closeEntry()
        val buffer = ByteArray(64 * 1024)
        segments.forEach { segment ->
          checkCancelled()
          require(segment.file.name.matches(Regex("[0-9]+\\.wav")))
          capacity(512)
          zip.putNextEntry(ZipEntry(segment.file.name))
          var copied = 0L
          openInput(segment.file).use { input ->
            while (true) {
              checkCancelled()
              val size = input.read(buffer)
              if (size < 0) break
              copied = Math.addExact(copied, size.toLong())
              check(copied <= segment.pcmBytes + 44) { "Audio segment changed; export cancelled." }
              capacity(size + 64L)
              zip.write(buffer, 0, size)
            }
          }
          check(copied == segment.pcmBytes + 44) { "Audio segment was incomplete; export cancelled." }
          zip.closeEntry()
        }
      }
      java.io.RandomAccessFile(partial, "rw").use { it.fd.sync() }
      check(!target.exists() && partial.renameTo(target)) { "Could not finish export." }
      return target
    } catch (error: Throwable) {
      partial.delete()
      throw error
    }
  }
}
