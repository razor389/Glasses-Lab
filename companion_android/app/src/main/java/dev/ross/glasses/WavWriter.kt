package dev.ross.glasses

import java.io.File
import java.io.RandomAccessFile

/** Writes the SDK's 16 kHz, mono, signed little-endian PCM without speech gating. */
class WavWriter(private val partial: File) : AutoCloseable {
  private val output = run {
    check(partial.createNewFile()) { "Audio segment already exists." }
    RandomAccessFile(partial, "rw")
  }
  var dataBytes = 0L
    private set
  private var closed = false

  init { output.setLength(0); header(output, 0) }

  fun append(pcm: ByteArray) {
    check(!closed)
    require(pcm.size % 2 == 0) { "Incomplete PCM sample." }
    check(dataBytes + pcm.size <= MAX_BYTES) { "Audio segment reached the WAV format limit." }
    output.write(pcm)
    dataBytes += pcm.size
  }

  fun checkpoint() {
    check(!closed)
    header(output, dataBytes)
    output.fd.sync()
  }

  override fun close() {
    if (closed) return
    closed = true
    try { header(output, dataBytes); output.fd.sync() } finally { output.close() }
  }

  companion object {
    const val BYTES_PER_SECOND = 32_000
    // A container bound, not a recording-duration bound. Long sessions rotate files.
    const val MAX_BYTES = 0xffff_ffdaL

    fun inspect(file: File): Long {
      val pcm = file.length() - 44
      require(pcm in 2..MAX_BYTES && pcm % 2 == 0L) { "Invalid audio segment length." }
      RandomAccessFile(file, "r").use { input ->
        val raw = ByteArray(44)
        input.readFully(raw)
        val header = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        require(String(raw, 0, 4, Charsets.US_ASCII) == "RIFF" && String(raw, 8, 8, Charsets.US_ASCII) == "WAVEfmt " &&
          String(raw, 36, 4, Charsets.US_ASCII) == "data" && header.getInt(24) == 16000 &&
          header.getShort(20).toInt() == 1 && header.getShort(22).toInt() == 1 && header.getShort(34).toInt() == 16 &&
          (header.getInt(40).toLong() and 0xffff_ffffL) == pcm) { "Invalid WAV segment; original retained." }
      }
      return pcm
    }

    /** A killed app can leave PCM intact without its final WAV sizes. Repair only our .wav.part files. */
    fun recover(file: File): File? {
      require(file.name.endsWith(".wav.part"))
      val bytes = ((file.length() - 44).coerceAtLeast(0) / 2) * 2
      if (bytes == 0L || bytes > MAX_BYTES) return null
      RandomAccessFile(file, "rw").use { out ->
        val format = ByteArray(44)
        out.readFully(format)
        val expected = java.nio.ByteBuffer.wrap(format).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        require(String(format, 0, 4, Charsets.US_ASCII) == "RIFF" &&
          String(format, 8, 8, Charsets.US_ASCII) == "WAVEfmt " &&
          String(format, 36, 4, Charsets.US_ASCII) == "data" &&
          expected.getInt(16) == 16 && expected.getShort(20).toInt() == 1 &&
          expected.getShort(22).toInt() == 1 && expected.getInt(24) == 16_000 &&
          expected.getInt(28) == BYTES_PER_SECOND && expected.getShort(32).toInt() == 2 &&
          expected.getShort(34).toInt() == 16) { "Unrecognized audio segment; original retained." }
        out.setLength(44 + bytes)
        header(out, bytes)
        out.fd.sync()
      }
      val target = File(file.parentFile, file.name.removeSuffix(".part"))
      check(!target.exists() && file.renameTo(target)) { "Could not finish audio file." }
      return target
    }

    private fun header(out: RandomAccessFile, bytes: Long) {
      out.seek(0)
      out.writeBytes("RIFF"); int(out, bytes + 36); out.writeBytes("WAVEfmt ")
      int(out, 16); short(out, 1); short(out, 1); int(out, 16_000)
      int(out, BYTES_PER_SECOND.toLong()); short(out, 2); short(out, 16)
      out.writeBytes("data"); int(out, bytes)
      out.seek(44 + bytes)
    }
    private fun int(out: RandomAccessFile, value: Long) {
      repeat(4) { out.write((value shr (8 * it)).toInt() and 255) }
    }
    private fun short(out: RandomAccessFile, value: Int) {
      out.write(value and 255); out.write((value shr 8) and 255)
    }
  }
}
