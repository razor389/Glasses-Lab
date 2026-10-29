package dev.ross.glasses

import java.io.File
import java.io.IOException

class RecordingCapacityExceeded : IOException("Recording storage allowance reached. Saved media is retained; adjust Storage settings or free space.")

/** Storage limits are independent of elapsed time and individual file sizes. */
data class RecordingStoragePolicy(val budgetBytes: Long? = null, val reserveBytes: Long = GIB) {
  init {
    require(budgetBytes == null || budgetBytes > 0) { "Storage budget must be positive or blank." }
    require(reserveBytes >= MIN_RESERVE) { "Keep at least 64 MiB free for finalization." }
  }

  fun remaining(usedBytes: Long, freeBytes: Long): Long = minOf(
    (freeBytes - reserveBytes).coerceAtLeast(0),
    budgetBytes?.let { (it - usedBytes).coerceAtLeast(0) } ?: Long.MAX_VALUE,
  )

  companion object {
    const val GIB = 1024L * 1024 * 1024
    const val MIN_RESERVE = 64L * 1024 * 1024
  }
}

/** Shared allowance for audio, downloads, and exports; disk scans also account for other apps. */
class RecordingStorageGuard(
  private val policy: () -> RecordingStoragePolicy,
  private val diskUsage: () -> Long,
  private val freeSpace: () -> Long,
  private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
  private var scannedAt = Long.MIN_VALUE
  private var used = 0L
  private var free = 0L

  /** Recount after moving a staged file into its canonical phone-media location. */
  @Synchronized fun invalidate() { scannedAt = Long.MIN_VALUE }

  @Synchronized fun requireCapacity(bytes: Long) {
    checkCapacity(bytes)
    used = Math.addExact(used, bytes)
    free = (free - bytes).coerceAtLeast(0)
  }

  @Synchronized fun checkCapacity(bytes: Long) {
    require(bytes >= 0)
    val now = clock()
    if (scannedAt == Long.MIN_VALUE || now - scannedAt >= 2000) {
      used = diskUsage()
      free = freeSpace()
      scannedAt = now
    }
    if (bytes > policy().remaining(used, free)) {
      throw RecordingCapacityExceeded()
    }
  }

  companion object {
    fun usedBytes(root: File): Long = if (!root.exists()) 0 else
      root.walkTopDown().filter { it.isFile }.fold(0L) { total, file -> Math.addExact(total, file.length()) }
  }
}
