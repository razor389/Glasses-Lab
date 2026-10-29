package dev.ross.glasses

import java.io.InputStream
import java.security.MessageDigest

internal data class MediaFingerprint(val bytes: Long, val sha256: String) {
  companion object {
    fun read(input: InputStream, checkCancelled: () -> Unit = {}): MediaFingerprint {
      val digest = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(64 * 1024)
      var count = 0L
      while (true) {
        checkCancelled()
        val size = input.read(buffer)
        if (size < 0) break
        if (size == 0) continue
        digest.update(buffer, 0, size)
        count = Math.addExact(count, size.toLong())
      }
      return MediaFingerprint(count, digest.digest().joinToString("") { "%02x".format(it) })
    }
  }
}
