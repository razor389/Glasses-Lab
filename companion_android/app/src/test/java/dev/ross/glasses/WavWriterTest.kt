package dev.ross.glasses

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WavWriterTest {
  private val directory = Files.createTempDirectory("lab-wav").toFile()
  @After fun cleanup() { directory.deleteRecursively() }

  @Test fun wavContainsCorrectFormatDurationAndUnmodifiedSamples() {
    val file = File(directory, "audio.wav.part")
    val pcm = ByteArray(32_000) { (it % 251).toByte() }
    WavWriter(file).use { it.append(pcm.copyOfRange(0, 10_000)); it.append(pcm.copyOfRange(10_000, pcm.size)) }
    val saved = WavWriter.recover(file)!!
    val bytes = saved.readBytes()
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
    assertEquals(32_036, header.getInt(4))
    assertEquals(1, header.getShort(22).toInt())
    assertEquals(16_000, header.getInt(24))
    assertEquals(16, header.getShort(34).toInt())
    assertEquals(32_000, header.getInt(40))
    assertArrayEquals(pcm, bytes.copyOfRange(44, bytes.size))
  }

  @Test fun interruptedRecordingIsRecoveredFromActualPcmLength() {
    val file = File(directory, "interrupted.wav.part")
    WavWriter(file).use { it.append(ByteArray(320)) }
    RandomAccessFile(file, "rw").use { out ->
      out.seek(4); out.write(ByteArray(4)); out.seek(40); out.write(ByteArray(4))
      out.seek(out.length()); out.write(1) // an interrupted half-sample
    }
    val saved = WavWriter.recover(file)!!
    assertEquals(364L, saved.length())
    assertEquals(320, ByteBuffer.wrap(saved.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
  }

  @Test(expected = IllegalArgumentException::class) fun partialPcmSamplesAreRejected() {
    WavWriter(File(directory, "odd.wav.part")).use { it.append(byteArrayOf(1)) }
  }

  @Test fun noSamplesDoesNotProduceAPretendRecording() {
    val file = File(directory, "empty.wav.part")
    WavWriter(file).close()
    assertNull(WavWriter.recover(file))
  }

  @Test fun existingPartialIsNeverTruncatedByANewWriter() {
    val file = File(directory, "existing.wav.part")
    WavWriter(file).use { it.append(ByteArray(320) { 7 }) }
    val original = file.readBytes()
    try { WavWriter(file); fail("Expected collision rejection") } catch (_: IllegalStateException) {}
    assertArrayEquals(original, file.readBytes())
  }

  @Test fun malformedPartialIsRetainedWithoutRepair() {
    val file = File(directory, "foreign.wav.part")
    file.writeBytes(ByteArray(100) { 7 })
    val original = file.readBytes()
    try { WavWriter.recover(file); fail("Expected invalid format") } catch (_: IllegalArgumentException) {}
    assertArrayEquals(original, file.readBytes())
  }
}
