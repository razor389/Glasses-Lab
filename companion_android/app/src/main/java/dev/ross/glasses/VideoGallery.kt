package dev.ross.glasses

import com.google.gson.JsonParser
import java.io.Reader

data class RemoteVideo(val name: String, val bytes: Long)

/** Correlates downloads to our capture ID; never picks an unrelated 'latest' file. */
object VideoGallery {
  /** Bound the allocation while reading, including chunked HTTP responses. */
  fun read(reader: Reader): String {
    val result = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
      val count = reader.read(buffer)
      if (count < 0) return result.toString()
      check(result.length + count <= MAX_GALLERY_CHARS) { "Gallery response was too large." }
      result.append(buffer, 0, count)
    }
  }

  fun find(json: String, requestId: String): RemoteVideo? {
    require(requestId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
    val root = JsonParser.parseString(json).asJsonObject
    val photos = root.getAsJsonObject("data")?.getAsJsonArray("photos") ?: return null
    return photos.firstNotNullOfOrNull { element ->
      val item = element.asJsonObject
      val name = item.get("name")?.asString.orEmpty()
      val id = item.get("request_id")?.takeUnless { it.isJsonNull }?.asString
      val matches = id == requestId || (id == null && name.substringBefore('/').endsWith("_$requestId"))
      val bytes = item.get("size")?.asLong ?: 0
      if (matches && validName(name) && bytes in 1..MAX_BYTES) RemoteVideo(name, bytes) else null
    }
  }

  fun validName(name: String) = name.startsWith("VID_") && name.endsWith(".mp4") &&
    name.matches(Regex("[A-Za-z0-9_./-]+")) && name.split('/').none { it.isBlank() || it == "." || it == ".." }

  fun privateIpv4(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) }) return false
    val p = parts.map { it.toInt() }
    if (p.size != 4 || p.any { it !in 0..255 }) return false
    return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
  }
  const val MAX_BYTES = 1024L * 1024 * 1024
  const val MAX_GALLERY_CHARS = 4 * 1024 * 1024
}
