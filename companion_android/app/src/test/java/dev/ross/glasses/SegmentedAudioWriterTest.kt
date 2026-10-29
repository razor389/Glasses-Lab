package dev.ross.glasses

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SegmentedAudioWriterTest {
  private val directory = Files.createTempDirectory("lab-segments").toFile()
  @After fun cleanup() { directory.deleteRecursively() }

  @Test fun rotationPreservesEverySampleAcrossUnevenInputBoundaries() {
    val saved = mutableListOf<AudioSegment>()
    val pcm = ByteArray(2002) { (it % 251).toByte() }
    SegmentedAudioWriter(directory, {}, saved::add, segmentBytes = 320).use { writer ->
      writer.append(pcm.copyOfRange(0, 142)); writer.append(pcm.copyOfRange(142, pcm.size))
    }
    assertEquals(7, saved.size)
    assertEquals((1L..7L).toList(), saved.map { it.sequence })
    assertArrayEquals(pcm, saved.flatMap { it.file.readBytes().drop(44) }.toByteArray())
    assertTrue(saved.all { WavWriter.inspect(it.file) == it.pcmBytes })
  }

  @Test fun resumedWriterNeverOverwritesEarlierSegments() {
    val saved = mutableListOf<AudioSegment>()
    repeat(2) { pass ->
      SegmentedAudioWriter(directory, {}, saved::add, segmentBytes = 320).use { it.append(ByteArray(160) { pass.toByte() }) }
    }
    assertEquals(listOf(1L, 2L), saved.map { it.sequence })
    assertEquals(0, saved[0].file.readBytes()[44].toInt())
    assertEquals(1, saved[1].file.readBytes()[44].toInt())
  }

  @Test fun resumesAfterPublishedPrivateSegmentsWereReclaimed() {
    val saved = mutableListOf<AudioSegment>()
    SegmentedAudioWriter(directory, {}, saved::add, segmentBytes = 320).use { it.append(ByteArray(160) { 7 }) }
    val publishedSequence = saved.single().sequence
    assertTrue(saved.single().file.delete()) // Durable public copy is represented by the indexed sequence.
    SegmentedAudioWriter(directory, {}, saved::add, segmentBytes = 320,
      initialSequence = publishedSequence).use { it.append(ByteArray(160) { 9 }) }
    assertEquals(listOf(1L, 2L), saved.map { it.sequence })
    assertEquals("00000002.wav", saved.last().file.name)
    assertEquals(9, saved.last().file.readBytes()[44].toInt())
  }

  @Test fun retainedPartialSequenceWinsOverOlderPublishedIndex() {
    val partial = File(directory, "00000003.wav.part")
    WavWriter(partial).use { it.append(ByteArray(80) { 4 }) }
    val original = partial.readBytes()
    val saved = mutableListOf<AudioSegment>()
    SegmentedAudioWriter(directory, {}, saved::add, segmentBytes = 320,
      initialSequence = 1).use { it.append(ByteArray(160) { 8 }) }
    assertEquals(4L, saved.single().sequence)
    assertEquals("00000004.wav", saved.single().file.name)
    assertArrayEquals(original, partial.readBytes())
  }

  @Test fun storageExhaustionPreservesAcceptedAudio() {
    val saved = mutableListOf<AudioSegment>()
    var remaining = 44 + 320L
    val writer = SegmentedAudioWriter(directory, { requested ->
      if (requested > remaining) throw IOException("full")
      remaining -= requested
    }, saved::add, segmentBytes = 1000)
    writer.append(ByteArray(320))
    try { writer.append(ByteArray(2)); fail("Expected quota failure") } catch (_: IOException) {}
    writer.close()
    assertEquals(320L, saved.single().pcmBytes)
    assertEquals(320L, WavWriter.inspect(saved.single().file))
  }

  @Test fun indexFailureLeavesCompletedFileForRecoveryWithoutDoubleCommit() {
    var attempts = 0
    val writer = SegmentedAudioWriter(directory, {}, { attempts++; throw IOException("index unavailable") }, segmentBytes = 320)
    try { writer.append(ByteArray(320)); fail("Expected index failure") } catch (_: IOException) {}
    writer.close()
    assertEquals(1, attempts)
    assertEquals(320L, WavWriter.inspect(File(directory, "00000001.wav")))
  }

  @Test fun fortyFiveMinutesOfSamplesHasNoThirtyMinuteCeiling() {
    val saved = mutableListOf<AudioSegment>()
    val frame = ByteArray(WavWriter.BYTES_PER_SECOND) { (it % 251).toByte() }
    SegmentedAudioWriter(directory, {}, saved::add).use { writer ->
      repeat(45 * 60) { writer.append(frame) }
      assertEquals(45L * 60 * 32_000, writer.bytes)
    }
    assertEquals(9, saved.size)
    assertEquals(45L * 60 * 32_000, saved.sumOf { WavWriter.inspect(it.file) })
  }
  @Test fun checkpointsFollowCapturedSamplesEvenIfWallClockMovesBackward() {
    var clock = 10000L
    SegmentedAudioWriter(directory, {}, {}, clock = { clock }).use {
      it.append(ByteArray(4 * 32_000))
      clock = 0
      it.append(ByteArray(32_000))
      assertEquals(5L * 32_000, WavWriter.inspect(File(directory, "00000001.wav.part")))
    }
  }
}
