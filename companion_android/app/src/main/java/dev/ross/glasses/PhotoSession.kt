package dev.ross.glasses

/** Correlates captures so late uploads cannot complete a new connection's request. */
class PhotoSession {
  @Volatile var ready = false
    private set
  @Volatile var activeId: String? = null
    private set

  @Synchronized fun setReady(value: Boolean) {
    ready = value
    if (!value) activeId = null
  }

  @Synchronized fun begin(requestId: String) {
    check(ready) { "Connect to the glasses and wait until they are ready." }
    check(activeId == null) { "A photo is already in progress." }
    require(requestId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
    activeId = requestId
  }

  @Synchronized fun accepts(requestId: String) = ready && activeId == requestId

  @Synchronized fun finish(requestId: String): Boolean {
    if (activeId != requestId) return false
    activeId = null
    return true
  }
}
