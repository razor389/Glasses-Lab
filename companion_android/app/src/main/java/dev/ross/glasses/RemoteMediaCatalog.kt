package dev.ross.glasses

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

data class VideoSegmentMetadata(val sessionId: String, val index: Int) {
  init {
    // Session IDs are opaque SQL-bound metadata, never filesystem paths.
    require(sessionId.isNotEmpty() && sessionId.toByteArray(Charsets.UTF_8).size <= 128) { "Invalid video session identity." }
    require(index >= 0) { "Invalid video segment number." }
  }
}

data class RemoteMedia(
  val name: String, val location: String, val bytes: Long, val generation: String,
  val modified: Long, val mime: String, val requestId: String?, val captureId: String,
  val videoSegment: VideoSegmentMetadata? = null,
) {
  val photo get() = mime == "image/jpeg"
  val key: String get() = MessageDigest.getInstance("SHA-256")
    .digest("$name\n$generation\n$bytes".toByteArray()).joinToString("") { "%02x".format(it) }
  val localName get() = "glasses-$key.${if (photo) "jpg" else "mp4"}"
}
data class RemoteMediaPage(
  val files: List<RemoteMedia>, val cursor: String?, val recoveryPending: Int = 0,
  val retainedAuxiliaries: Int = 0, val retainedAuxiliaryBytes: Long = 0,
  val auxiliaryTotalsReported: Boolean = false,
  val segmentedRecordingVersion: Int = 0,
)

/** New manifests repeat gallery totals on each page; older manifests only expose page entries. */
data class AuxiliaryMediaTotals(val files: Int = 0, val bytes: Long = 0, val global: Boolean = false) {
  fun include(page: RemoteMediaPage): AuxiliaryMediaTotals = when {
    page.auxiliaryTotalsReported -> AuxiliaryMediaTotals(
      maxOf(if (global) files else 0, page.retainedAuxiliaries),
      maxOf(if (global) bytes else 0, page.retainedAuxiliaryBytes), true,
    )
    global -> this
    else -> AuxiliaryMediaTotals(Math.addExact(files, page.retainedAuxiliaries), Math.addExact(bytes, page.retainedAuxiliaryBytes))
  }
}

/** Completed image/video captures, including HDR brackets, retain their exact source identity. */
object RemoteMediaCatalog {
  fun data(json: String): JsonObject {
    val root = JsonParser.parseString(json).asJsonObject
    check(root.get("status")?.asString == "success") { "Glasses did not confirm the media request." }
    return root.getAsJsonObject("data") ?: error("Missing glasses media response.")
  }
  fun page(json: String): RemoteMediaPage {
    val data = data(json)
    check(data.get("api_version")?.asInt == 1) { "Update the glasses build to enable verified media sync." }
    var filteredBytes = 0L
    fun retained(item: JsonObject): RemoteMedia? {
      filteredBytes = Math.addExact(filteredBytes, (item.get("size")?.asLong ?: 0L).coerceAtLeast(0))
      return null
    }
    val files = data.getAsJsonArray("files")?.mapNotNull { raw ->
      val item = raw.asJsonObject
      val role = item.get("role")?.asString ?: "primary"
      if (role !in listOf("primary", "bracket")) return@mapNotNull retained(item)
      val name = item.get("name")?.asString ?: error("Missing media name.")
      val mime = item.get("mime_type")?.asString.orEmpty()
      if (mime !in listOf("image/jpeg", "video/mp4")) return@mapNotNull retained(item)
      require(validName(name)) { "Invalid glasses media path." }
      val location = item.get("location")?.asString.orEmpty()
      require(location in listOf("live", "trash"))
      val size = item.get("size")?.asLong ?: 0
      require(size > 0)
      val generation = item.get("generation")?.asString.orEmpty()
      require(generation.isNotBlank() && generation.length <= 256 && generation.none { it.code < 32 })
      val session = item.get("session_id")?.takeUnless { it.isJsonNull }
      val segment = item.get("segment_index")?.takeUnless { it.isJsonNull }
      require((session == null) == (segment == null)) { "Incomplete video segment identity." }
      val videoSegment = session?.let {
        require(mime == "video/mp4" && it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Invalid video session identity." }
        require(segment!!.isJsonPrimitive && segment.asJsonPrimitive.isNumber &&
          segment.asString.matches(Regex("0|[1-9][0-9]*"))) { "Invalid video segment number." }
        VideoSegmentMetadata(it.asString, segment.asString.toIntOrNull() ?: error("Video segment number is too large."))
      }
      RemoteMedia(name, location, size, generation, item.get("modified")?.asLong ?: 0,
        mime, item.get("request_id")?.takeUnless { it.isJsonNull }?.asString,
        item.get("capture_id")?.asString.orEmpty(), videoSegment)
    }.orEmpty()
    val more = data.get("has_more")?.asBoolean == true
    val cursor = if (more) data.get("next_cursor")?.takeUnless { it.isJsonNull }?.asString else null
    check(!more || !cursor.isNullOrBlank()) { "Invalid media pagination response." }
    val totalFiles = data.get("retained_auxiliary_files")?.takeUnless { it.isJsonNull }?.asLong
    val totalBytes = data.get("retained_auxiliary_bytes")?.takeUnless { it.isJsonNull }?.asLong
    require((totalFiles == null) == (totalBytes == null)) { "Incomplete glasses auxiliary totals." }
    require(totalFiles == null || totalFiles in 0..Int.MAX_VALUE.toLong()) { "Invalid glasses auxiliary file count." }
    require(totalBytes == null || totalBytes >= 0) { "Invalid glasses auxiliary byte count." }
    val version = data.get("segmented_recording_version")?.takeUnless { it.isJsonNull }?.let {
      require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString.matches(Regex("0|[1-9][0-9]*"))) {
        "Invalid segmented recording capability."
      }
      it.asString.toIntOrNull() ?: error("Unsupported segmented recording capability.")
    } ?: 0
    return RemoteMediaPage(files, cursor, data.get("recovery_pending")?.asInt ?: 0,
      totalFiles?.toInt() ?: ((data.getAsJsonArray("files")?.size() ?: 0) - files.size),
      totalBytes ?: filteredBytes, totalFiles != null, version)
  }
  fun validName(value: String): Boolean = value.length in 1..240 &&
    value.matches(Regex("[A-Za-z0-9_./-]+")) && value.split('/').none { it.isBlank() || it == "." || it == ".." }
}
