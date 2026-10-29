package dev.ross.glasses

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Uses an isolated database and directory, never the user's actual recording library. */
@RunWith(AndroidJUnit4::class)
class RecordingRepositoryDeviceTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val token = UUID.randomUUID().toString()
  private val database = "test-recordings-$token.sqlite"
  private val directory get() = File(context.filesDir, "test-recordings-$token")
  private lateinit var repository: RecordingRepository
  private val id = "audio-1700000000000-abc12345"

  @Before fun setup() { repository = RecordingRepository(context, database, directory) }
  @After fun cleanup() {
    repository.close()
    context.deleteDatabase(database)
    check(directory.canonicalFile.parentFile == context.filesDir.canonicalFile)
    directory.deleteRecursively()
  }

  @Test fun renamedFileWithMissingIndexCommitIsRecoveredExactlyOnce() {
    repository.create(id, "Test glasses")
    val writer = SegmentedAudioWriter(repository.folder(id), {}, { throw IOException("simulated index failure") }, segmentBytes = 320)
    try { writer.append(ByteArray(320)); fail("Expected failure") } catch (_: IOException) {}
    writer.close()
    repository.close()
    repository = RecordingRepository(context, database, directory)
    assertTrue(repository.recover().isEmpty())
    assertTrue(repository.recover().isEmpty())
    val saved = repository.list().single()
    assertEquals(320L, saved.pcmBytes)
    assertEquals(1, saved.segments)
    assertEquals("INTERRUPTED", saved.state)
  }

  @Test fun committedSegmentIdentityAcceptsFilesDirAliasesButRejectsUnindexedAndUnrelatedFiles() {
    repository.create(id, "Test glasses")
    SegmentedAudioWriter(repository.folder(id), {}, { repository.commit(id, it) }).use {
      it.append(ByteArray(320))
    }
    val indexed = repository.segments(id).single().file
    // Traverse the actual Context path, which can differ from canonical /data/data on Android.
    val fromFilesDir = File(directory, "$id/${indexed.name}")
    val dottedAlias = File(directory, "$id/./${indexed.name}")
    assertTrue(fromFilesDir.isFile)
    assertNotEquals(indexed, dottedAlias) // The former raw File equality gate rejects this valid alias.
    assertEquals(indexed.canonicalFile, fromFilesDir.canonicalFile)
    assertEquals(indexed.canonicalFile, dottedAlias.canonicalFile)
    assertTrue(repository.isCommittedSegment(indexed))
    assertTrue(repository.isCommittedSegment(fromFilesDir))
    assertTrue(repository.isCommittedSegment(dottedAlias))

    val unindexed = File(repository.folder(id), "00000002.wav").apply { writeBytes(indexed.readBytes()) }
    val unrelated = File(directory, indexed.name).apply { writeBytes(indexed.readBytes()) }
    assertFalse(repository.isCommittedSegment(unindexed))
    assertFalse(repository.isCommittedSegment(unrelated))
    assertFalse(repository.isCommittedSegment(File(repository.folder(id), indexed.name + ".part")))
    val otherId = "audio-1700000000001-xyz12345"
    repository.create(otherId, "Other glasses")
    val otherSession = File(repository.folder(otherId), indexed.name).apply { writeBytes(indexed.readBytes()) }
    assertFalse(repository.isCommittedSegment(otherSession))
    repository.state(id, "DELETING", "Test deletion in progress")
    assertFalse(repository.isCommittedSegment(fromFilesDir))
  }

  @Test fun partialFilesAreRecoveredAndExistingFlatAudioIsPreserved() {
    repository.create(id, "Test glasses")
    val partial = File(repository.folder(id), "00000001.wav.part")
    WavWriter(partial).use { it.append(ByteArray(642)) }
    val legacyPartial = File(directory, "legacy.wav.part")
    WavWriter(legacyPartial).use { it.append(ByteArray(160)) }
    assertTrue(repository.recover().isEmpty())
    assertEquals(642L, repository.list().single().pcmBytes)
    assertEquals(160L, WavWriter.inspect(File(directory, "legacy.wav")))
  }

  @Test fun staleRecordingEventCannotUndoPauseAndResumeTracksGap() {
    repository.create(id, "Test glasses")
    repository.state(id, "PAUSED", "Call")
    repository.markRecording(id)
    assertEquals("PAUSED", repository.list().single().state)
    repository.resume(id)
    assertEquals(1, repository.list().single().gaps)
    assertEquals("Call", repository.gaps(id).single()["reason"])
  }

  @Test fun explicitDeletionDoesNotRemoveLegacyOrOtherSessions() {
    repository.create(id, "Test glasses")
    SegmentedAudioWriter(repository.folder(id), {}, { repository.commit(id, it) }).use { it.append(ByteArray(320)) }
    val second = "audio-1700000000001-xyz12345"
    repository.create(second, "Test glasses")
    repository.delete(id)
    assertEquals(listOf(second), repository.list().map { it.id })
    assertTrue(repository.folder(second).isDirectory)
    assertFalse(repository.folder(id).exists())
  }

  @Test fun delayedCallPauseCannotUndoExplicitResumeOrStop() {
    repository.create(id, "Test glasses")
    repository.state(id, "WAITING", "Connection lost")
    repository.pauseWaitingForCall(id)
    assertEquals("PAUSED", repository.list().single().state)
    repository.resume(id)
    repository.pauseWaitingForCall(id)
    assertEquals("STARTING", repository.list().single().state)
    repository.state(id, "FINISHED")
    repository.pauseWaitingForCall(id)
    assertEquals("FINISHED", repository.list().single().state)
    assertEquals(1, repository.gaps(id).size)
  }

  @Test fun stopAndRecoveryCloseTrailingPauseExactlyOnce() {
    repository.create(id, "Test glasses")
    repository.state(id, "PAUSED", "Paused by you")
    repository.state(id, "FINISHED", "Stopped")
    repository.state(id, "FINISHED", "Stopped")
    assertEquals("Paused by you", repository.gaps(id).single()["reason"])
    val second = "audio-1700000000001-xyz12345"
    repository.create(second, "Test glasses")
    repository.state(second, "WAITING", "Connection lost")
    assertTrue(repository.recover().isEmpty())
    assertTrue(repository.recover().isEmpty())
    assertEquals("Connection lost", repository.gaps(second).single()["reason"])
  }
}
