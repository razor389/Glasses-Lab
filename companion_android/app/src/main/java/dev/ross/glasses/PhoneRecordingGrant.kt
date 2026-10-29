package dev.ross.glasses

import com.google.gson.JsonObject

/** One durable reservation for one app- or button-started session. Authority expires on glasses. */
data class PhoneRecordingGrant(
  val grantId: String, val sessionId: String, val revision: String,
  val profile: VideoRecordingProfile, val maxSessionBytes: Long,
) {
  init {
    require(grantId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
    require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
    require(revision.isNotEmpty() && profile.offloadToPhone && maxSessionBytes >= PhoneVideoPlan.SEGMENT_BYTES)
  }
  fun body(ttlSeconds: Int) = JsonObject().apply {
    require(ttlSeconds in 0..90)
    addProperty("profileRevision", revision)
    addProperty("grantId", grantId)
    addProperty("sessionId", sessionId)
    addProperty("maxSessionBytes", maxSessionBytes)
    addProperty("ttlSeconds", ttlSeconds)
  }
  fun encode(): String = body(0).apply { addProperty("profile", profile.encode()) }.toString()
  companion object {
    fun decode(value: String?): PhoneRecordingGrant? = runCatching {
      val data = com.google.gson.JsonParser.parseString(requireNotNull(value)).asJsonObject
      PhoneRecordingGrant(data["grantId"].asString, data["sessionId"].asString,
        data["profileRevision"].asString, requireNotNull(VideoRecordingProfile.decode(data["profile"].asString)),
        data["maxSessionBytes"].asLong)
    }.getOrNull()
  }
}

class PhoneRecordingGrantConsumed : IllegalStateException("The glasses have started this recording. Checking its status…")
