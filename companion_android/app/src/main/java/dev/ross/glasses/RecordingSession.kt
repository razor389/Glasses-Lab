package dev.ross.glasses

/** Keeps a video stoppable after a lost acknowledgement or a disconnected phone. */
class RecordingSession {
  enum class Kind { AUDIO, VIDEO }
  enum class Phase { IDLE, STARTING, RECORDING, STOPPING, UNCERTAIN, PAUSED, WAITING }
  var kind: Kind? = null
    private set
  var phase = Phase.IDLE
    private set
  var id: String? = null
    private set
  val idle get() = phase == Phase.IDLE

  fun begin(kind: Kind, id: String) {
    check(idle) { "Stop the current recording first." }
    require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
    this.kind = kind
    this.id = id
    phase = Phase.STARTING
  }

  fun restoreVideo(id: String) { begin(Kind.VIDEO, id); uncertain(id) }
  fun started(id: String) {
    if (this.id == id && phase in listOf(Phase.STARTING, Phase.UNCERTAIN)) phase = Phase.RECORDING
  }
  fun stopping(): String {
    val requestId = checkNotNull(id) { "No recording is active." }
    check(phase != Phase.STOPPING) { "Already stopping." }
    phase = Phase.STOPPING
    return requestId
  }
  fun uncertain(id: String) {
    if (this.id == id) phase = Phase.UNCERTAIN
  }
  fun pause(id: String, waiting: Boolean) {
    if (this.id == id && kind == Kind.AUDIO) phase = if (waiting) Phase.WAITING else Phase.PAUSED
  }
  fun resume() {
    check(kind == Kind.AUDIO && phase in listOf(Phase.PAUSED, Phase.WAITING))
    phase = Phase.STARTING
  }
  fun finish(id: String): Boolean {
    if (this.id != id) return false
    this.id = null
    kind = null
    phase = Phase.IDLE
    return true
  }
}
