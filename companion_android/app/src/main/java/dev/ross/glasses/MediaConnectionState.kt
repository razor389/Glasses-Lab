package dev.ross.glasses

import java.io.IOException

class MediaConnectionNeedsApproval : IOException("Open Glasses Lab and connect glasses Wi-Fi to save queued captures.")

/** Owns generations and a monotonic deadline; late callbacks cannot revive a released connection. */
internal class MediaConnectionState<T>(private val now: () -> Long) {
  private var generation = 0L
  private var opening: Long? = null
  private var resource: T? = null
  private var deadline = 0L
  private var closed = false

  val connecting: Boolean @Synchronized get() = opening != null
  val held: T? @Synchronized get() = resource
  val current: T? @Synchronized get() = resource?.takeIf { !closed && now() < deadline }

  @Synchronized fun begin(allowPrompt: Boolean): Long {
    check(!closed) { "Media connection is closed." }
    if (!allowPrompt) throw MediaConnectionNeedsApproval()
    check(resource == null && opening == null) { "A media connection is already owned." }
    return (++generation).also { opening = it }
  }

  @Synchronized fun accept(attempt: Long, value: T, ttlSeconds: Int): Boolean {
    require(ttlSeconds in 1..MAX_LEASE_SECONDS) { "Invalid glasses Wi-Fi lease duration." }
    if (closed || generation != attempt || opening != attempt) return false
    resource = value
    opening = null
    deadline = Math.addExact(now(), ttlSeconds * 1000L)
    return true
  }

  @Synchronized fun renew(attempt: Long, ttlSeconds: Int): Boolean {
    require(ttlSeconds in 1..MAX_LEASE_SECONDS) { "Invalid glasses Wi-Fi lease duration." }
    if (closed || generation != attempt || resource == null || now() >= deadline) return false
    deadline = Math.addExact(now(), ttlSeconds * 1000L)
    return true
  }

  @Synchronized fun invalidate(): T? {
    generation++
    opening = null
    deadline = 0
    return resource.also { resource = null }
  }

  @Synchronized fun close(): T? {
    closed = true
    return invalidate()
  }

  companion object { const val MAX_LEASE_SECONDS = 180 }
}
