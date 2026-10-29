package dev.ross.glasses

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MediaFingerprintTest {
  @Test fun verifiesKnownDigestAcrossShortReads() {
    val input = object : FilterInputStream(ByteArrayInputStream("abc".toByteArray())) {
      override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(1, length))
    }
    assertEquals(MediaFingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), MediaFingerprint.read(input))
  }

  @Test fun cancellationInterruptsLargeVerificationWithoutReadingWholeInput() {
    val input = ByteArrayInputStream(ByteArray(512 * 1024))
    var checks = 0
    try {
      MediaFingerprint.read(input) { if (++checks == 3) throw CancellationException("cancel") }
      fail("Expected cancellation")
    } catch (_: CancellationException) {}
    assertEquals(384 * 1024, input.available())
  }
}
