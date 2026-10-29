package dev.ross.glasses

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VideoFileTransferTest {
  private val directory = Files.createTempDirectory("lab-video-transfer").toFile()
  private val target = File(directory, "video.mp4")
  private val partial = File(directory, "video.mp4.part")
  @After fun cleanup() { directory.deleteRecursively() }

  @Test fun killedOffloadScratchCannotPermanentlyConsumeRetryAllowance() {
    val offload = File(directory, "glasses-${"a".repeat(64)}.mp4")
    val scratch = File(directory, offload.name + ".part").apply { writeBytes(ByteArray(100)) }
    val guard = RecordingStorageGuard(
      { RecordingStoragePolicy(200, RecordingStoragePolicy.MIN_RESERVE) },
      { RecordingStorageGuard.usedBytes(directory) },
      { RecordingStoragePolicy.MIN_RESERVE + 1_000 },
    )
    assertThrows(IOException::class.java) { guard.checkCapacity(200) }
    assertTrue(VideoFileTransfer.discardStaleOffloadPartial(directory, offload))
    guard.invalidate()
    guard.checkCapacity(200) // Receive + pending MediaStore copy now fit the configured quota.
    assertFalse(scratch.exists())
    assertFalse(offload.exists())
    assertFalse(VideoFileTransfer.discardStaleOffloadPartial(directory, offload))
  }

  @Test fun staleScratchCleanupPreservesSavedTargetAndOtherTransfers() {
    val offload = File(directory, "glasses-${"a".repeat(64)}.jpg").apply { writeText("verified photo") }
    File(directory, offload.name + ".part").writeText("interrupted copy")
    val other = File(directory, "glasses-${"b".repeat(64)}.jpg.part").apply { writeText("other transfer") }
    assertTrue(VideoFileTransfer.discardStaleOffloadPartial(directory, offload))
    assertEquals("verified photo", offload.readText())
    assertEquals("other transfer", other.readText())
  }

  @Test fun scratchCleanupRejectsOutsideTargetsAndUnknownNames() {
    val owned = File(directory, "owned").apply { mkdirs() }
    val outside = File(directory, "glasses-${"a".repeat(64)}.mp4")
    val scratch = File(directory, outside.name + ".part").apply { writeText("retain") }
    assertThrows(IllegalArgumentException::class.java) {
      VideoFileTransfer.discardStaleOffloadPartial(owned, outside)
    }
    assertThrows(IllegalArgumentException::class.java) {
      VideoFileTransfer.discardStaleOffloadPartial(directory, target)
    }
    assertEquals("retain", scratch.readText())
  }

  @Test fun historicalSilentVideoIsAcceptedButAudioOnlyAndEmptyFilesAreRejected() {
    VideoFileTransfer.validateVideoTracks(listOf("video/avc"))
    VideoFileTransfer.validateVideoTracks(listOf("audio/mp4a-latm", "video/hevc"))
    assertThrows(IllegalStateException::class.java) { VideoFileTransfer.validateVideoTracks(listOf("audio/mp4a-latm")) }
    assertThrows(IllegalStateException::class.java) { VideoFileTransfer.validateVideoTracks(emptyList()) }
  }

  @Test fun onlyValidatedCompleteBytesBecomeVisibleAsARecording() {
    val bytes = ByteArray(100_000) { (it % 251).toByte() }
    val progress = mutableListOf<Int>()
    val saved = VideoFileTransfer.save(ByteArrayInputStream(bytes), target, bytes.size.toLong(),
      validate = { file ->
        assertFalse(target.exists())
        assertEquals(partial, file)
        assertArrayEquals(bytes, file.readBytes())
      }, progress = { progress.add(it) })
    assertEquals(target, saved)
    assertArrayEquals(bytes, target.readBytes())
    assertEquals(100, progress.last())
    assertFalse(partial.exists())
  }

  @Test fun truncatedTransferCannotBeMistakenForASavedVideo() {
    assertThrows(IllegalStateException::class.java) {
      VideoFileTransfer.save(ByteArrayInputStream(ByteArray(50)), target, 100, { fail("Must not validate a truncated file") })
    }
    assertNoDownload()
  }

  @Test fun responseCannotExceedItsDeclaredLength() {
    assertThrows(IllegalStateException::class.java) {
      VideoFileTransfer.save(ByteArrayInputStream(ByteArray(101)), target, 100, { fail("Must not validate excess data") })
    }
    assertNoDownload()
  }

  @Test fun missingAudioOrInvalidMediaCanBeRetriedWithoutABadFinalFile() {
    assertThrows(IllegalArgumentException::class.java) {
      VideoFileTransfer.save(ByteArrayInputStream(ByteArray(100)), target, 100, { throw IllegalArgumentException("No audio track") })
    }
    assertNoDownload()
    assertTrue(VideoFileTransfer.save(ByteArrayInputStream(ByteArray(100)), target, 100, {}).exists())
  }

  @Test fun cancellationRemovesOnlyTheUnfinishedCopy() {
    var checks = 0
    assertThrows(CancellationException::class.java) {
      VideoFileTransfer.save(ByteArrayInputStream(ByteArray(100_000)), target, 100_000, {},
        checkActive = { if (++checks == 2) throw CancellationException() })
    }
    assertNoDownload()
  }

  @Test fun connectionFailureDoesNotPublishPartialData() {
    val input = object : InputStream() {
      private var reads = 0
      override fun read(): Int = throw UnsupportedOperationException()
      override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (++reads > 1) throw IOException("Connection lost")
        buffer[offset] = 1
        return 1
      }
    }
    assertThrows(IOException::class.java) { VideoFileTransfer.save(input, target, 100, {}) }
    assertNoDownload()
  }

  @Test fun existingSavedRecordingIsNeverOverwritten() {
    target.writeText("existing recording")
    assertThrows(IllegalStateException::class.java) {
      VideoFileTransfer.save(ByteArrayInputStream(ByteArray(100)), target, 100, {})
    }
    assertEquals("existing recording", target.readText())
    assertFalse(partial.exists())
  }

  private fun assertNoDownload() { assertFalse(target.exists()); assertFalse(partial.exists()) }

  @Test fun recordingStorageQuotaAlsoAppliesToDownloads() {
    assertThrows(IOException::class.java) {
      VideoFileTransfer.save(ByteArrayInputStream(ByteArray(100)), target, 100, {},
        capacity = { throw IOException("Storage allowance reached") })
    }
    assertNoDownload()
  }
}
