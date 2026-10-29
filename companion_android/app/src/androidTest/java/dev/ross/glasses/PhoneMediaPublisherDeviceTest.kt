package dev.ross.glasses

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** All rows and files use a unique test namespace; no real library or capture is touched. */
@RunWith(AndroidJUnit4::class)
class PhoneMediaPublisherDeviceTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val token = UUID.randomUUID().toString()
  private val database = "test-publications-$token.sqlite"
  private val album = "Glasses Lab Test $token"
  private val directory get() = File(context.filesDir, "test-publications-$token")
  private lateinit var publisher: PhoneMediaPublisher
  private var failAt: String? = null

  @Before fun setup() {
    check(directory.mkdirs())
    publisher = createPublisher()
  }
  private fun createPublisher() = PhoneMediaPublisher(context, database, listOf(directory), album) { phase ->
    if (phase == failAt) { failAt = null; throw IOException("Simulated death at $phase") }
  }
  private fun source(name: String = "00000001.wav"): File = File(directory, name).also { file ->
    WavWriter(file).use { it.append(ByteArray(6400) { index -> (index % 127).toByte() }) }
  }
  private fun pendingRows(): Int = context.contentResolver.query(
    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), arrayOf(MediaStore.MediaColumns._ID),
    "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf("Music/$album/"), null,
  )!!.use { it.count }

  @After fun cleanup() {
    publisher.close()
    // This exact random path was created only by this test fixture.
    context.contentResolver.delete(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
      "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
      arrayOf("Music/$album/", context.packageName))
    context.deleteDatabase(database)
    check(directory.canonicalFile.parentFile == context.filesDir.canonicalFile)
    directory.deleteRecursively()
  }

  @Test fun publishesVerifiedCopyAndReclaimsPrivateBytesOnlyOnRequest() {
    val source = source()
    val result = publisher.publish(source, MediaKind.AUDIO, "Test recording part 1")
    assertTrue(source.isFile)
    assertTrue(publisher.verify(result))
    assertEquals(source.length(), publisher.cachedRecordingBytes)
    assertEquals(result, publisher.publish(source, MediaKind.AUDIO))
    assertEquals(1, pendingRows())
    assertTrue(publisher.removePrivateCopy(source))
    assertFalse(source.exists())
    assertEquals(result.bytes, publisher.openInput(source).use { MediaFingerprint.read(it).bytes })
    assertEquals(result, publisher.lookup(source))
  }

  @Test fun recoversInsertBeforeUriJournalWithoutCreatingDuplicate() {
    val source = source()
    failAt = "pending_inserted"
    try { publisher.publish(source, MediaKind.AUDIO); fail("Expected interruption") } catch (_: IOException) {}
    assertTrue(source.exists())
    assertEquals(0L, publisher.trackedBytes())
    publisher.close()
    publisher = createPublisher()
    assertTrue(publisher.recover().isEmpty())
    assertTrue(publisher.recover().isEmpty())
    assertEquals(1, pendingRows())
    assertTrue(publisher.verify(publisher.lookup(source)!!))
  }

  @Test fun recoversVisibleCopyBeforeCommitAndChargesPendingAllocation() {
    val source = source()
    failAt = "visible"
    try { publisher.publish(source, MediaKind.AUDIO); fail("Expected interruption") } catch (_: IOException) {}
    assertNull(publisher.lookup(source))
    assertEquals(source.length(), publisher.trackedBytes())
    publisher.close()
    publisher = createPublisher()
    assertTrue(publisher.recover().isEmpty())
    assertEquals(1, pendingRows())
    assertEquals(source.length(), publisher.cachedRecordingBytes)
  }

  @Test fun rejectsChangedSourceAndCorruptDestinationBeforeReclamation() {
    val source = source()
    val result = publisher.publish(source, MediaKind.AUDIO)
    context.contentResolver.openOutputStream(result.uri, "wt")!!.use { it.write(ByteArray(17)) }
    assertFalse(publisher.verify(result))
    try { publisher.publish(source, MediaKind.AUDIO); fail("Must not overwrite corrupt published media") } catch (_: IllegalStateException) {}
    assertEquals(17L, context.contentResolver.openInputStream(result.uri)!!.use { MediaFingerprint.read(it).bytes })
    try { publisher.removePrivateCopy(source); fail("Expected verification failure") } catch (_: IllegalStateException) {}
    assertTrue(source.exists())
    source.appendBytes(byteArrayOf(1, 2))
    try { publisher.publish(source, MediaKind.AUDIO); fail("Expected source mismatch") } catch (_: IllegalStateException) {}
    assertTrue(source.exists())
  }

  @Test fun externallyDeletedMediaReleasesBudgetWithoutSilentResurrection() {
    val source = source()
    val result = publisher.publish(source, MediaKind.AUDIO)
    assertEquals(1, context.contentResolver.delete(result.uri, null, null))
    try { publisher.publish(source, MediaKind.AUDIO); fail("Must not recreate missing published media before reconciliation") } catch (_: IllegalStateException) {}
    assertEquals(0, pendingRows())
    assertTrue(publisher.reconcile().isEmpty())
    assertEquals(0L, publisher.cachedRecordingBytes)
    assertNull(publisher.lookup(source))
    assertTrue(publisher.isSuppressed(source))
    assertTrue(publisher.list().isEmpty())
    try { publisher.publish(source, MediaKind.AUDIO); fail("Must not resurrect deleted media") } catch (_: IllegalStateException) {}
    assertEquals(0, pendingRows())
    assertTrue(source.isFile)
  }

  @Test fun publishedRowMadePendingIsNeverRewritten() {
    val source = source()
    val result = publisher.publish(source, MediaKind.AUDIO)
    assertEquals(1, context.contentResolver.update(result.uri, ContentValues().apply {
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }, null, null))
    context.contentResolver.openOutputStream(result.uri, "wt")!!.use { it.write(ByteArray(17)) }
    try { publisher.publish(source, MediaKind.AUDIO); fail("Committed row must not enter the pending-copy path") } catch (_: IllegalStateException) {}
    assertEquals(17L, context.contentResolver.openInputStream(result.uri)!!.use { MediaFingerprint.read(it).bytes })
    assertFalse(publisher.verify(result))
    try { publisher.removePrivateCopy(source); fail("Private original must be retained") } catch (_: IllegalStateException) {}
    assertTrue(source.isFile)
  }

  @Test fun fullStorageLeavesPrivateSourceAndRetryablePendingItem() {
    val source = source()
    try {
      publisher.publish(source, MediaKind.AUDIO, capacity = { throw IOException("full") })
      fail("Expected quota failure")
    } catch (_: IOException) {}
    assertTrue(source.exists())
    assertNull(publisher.lookup(source))
    assertEquals(0L, publisher.trackedBytes())
    assertTrue(publisher.recover().isEmpty())
    assertTrue(publisher.verify(publisher.lookup(source)!!))
    assertEquals(1, pendingRows())
  }

  @Test fun rejectsPartialFilesAndPathsOutsideOwnedRoots() {
    try { publisher.publish(File(directory, "active.wav.part"), MediaKind.AUDIO); fail("Partial accepted") } catch (_: IllegalArgumentException) {}
    try { publisher.publish(File(context.filesDir, "outside.wav"), MediaKind.AUDIO); fail("Outside source accepted") } catch (_: IllegalArgumentException) {}
    assertEquals(0, pendingRows())
  }
}
