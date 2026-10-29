package dev.ross.glasses

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VerifiedMediaReceiptTest {
  private val file = RemoteMedia("VID_123/part.mp4", "live", 100, "g1", 1, "video/mp4", "video-1", "VID_123",
    VideoSegmentMetadata("video-1", 0))
  private val fingerprint = MediaFingerprint(100, "a".repeat(64))

  @Test fun durableMembershipMustSucceedBeforeSourceReclamation() = runTest {
    var sent = false
    try {
      VerifiedMediaReceipt.commit(file, fingerprint, { throw IOException("Session journal unavailable") }) {
        sent = true; error("Must not reclaim before membership is durable")
      }
      fail("Missing session journal was accepted")
    } catch (_: IOException) {}
    assertFalse(sent)
  }

  @Test fun receiptRetryKeepsTheExactSourceIdentityAndWaitsForCommit() = runTest {
    val requests = mutableListOf<String>()
    repeat(2) {
      var committed = false
      VerifiedMediaReceipt.commit(file, fingerprint, { committed = true }) { body ->
        assertTrue(committed)
        assertEquals(file.name, body.get("file").asString)
        assertEquals(file.generation, body.get("generation").asString)
        assertEquals(fingerprint.sha256, body.get("sha256").asString)
        assertEquals(100, body.get("size").asLong)
        requests += body.toString()
        """{"status":"success","data":{"receipt_id":"${body.get("receipt_id").asString}"}}"""
      }
    }
    assertEquals(requests[0], requests[1])
  }

  @Test fun unconfirmedCleanupCannotBecomeATransferSuccess() = runTest {
    try {
      VerifiedMediaReceipt.commit(file, fingerprint, {}) { """{"status":"success","data":{"receipt_id":"another-source"}}""" }
      fail("Wrong receipt accepted")
    } catch (_: IllegalStateException) {}
  }

  @Test fun mismatchedCopyNeverReachesCommitOrReceipt() = runTest {
    try {
      VerifiedMediaReceipt.commit(file, fingerprint.copy(bytes = 99), { fail("Wrong copy committed") }) {
        error("Wrong copy reclaimed")
      }
      fail("Wrong size accepted")
    } catch (_: IllegalArgumentException) {}
  }
}
