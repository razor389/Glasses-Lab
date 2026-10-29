package dev.ross.glasses

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.util.UUID

/** Receives the SDK's BLE photo relay on this phone only; never listens on the LAN. */
class LocalPhotoReceiver(
  private val photoDirectory: File,
  private val accepts: (String) -> Boolean,
  private val onPhoto: (String, File) -> Unit,
) : NanoHTTPD("127.0.0.1", 0), AutoCloseable {
  val token: String = UUID.randomUUID().toString()
  val uploadUrl: String get() = "http://127.0.0.1:$listeningPort/upload"

  override fun serve(session: IHTTPSession): Response {
    if (session.method != Method.POST || session.uri != "/upload") {
      return reply(Response.Status.NOT_FOUND, "not_found")
    }
    if (session.headers["authorization"] != "Bearer $token") {
      return reply(Response.Status.UNAUTHORIZED, "unauthorized")
    }
    val length = session.headers["content-length"]?.toLongOrNull()
    if (length == null || length <= 0 || length > MAX_UPLOAD_BYTES) {
      return reply(Response.Status.BAD_REQUEST, "invalid_length")
    }
    return try {
      val files = mutableMapOf<String, String>()
      session.parseBody(files)
      val requestId = session.parameters["requestId"]?.firstOrNull().orEmpty()
      if (!accepts(requestId)) return reply(Response.Status.CONFLICT, "unexpected_request")
      val source = files["photo"]?.let(::File)
        ?: return reply(Response.Status.BAD_REQUEST, "missing_photo")
      if (source.length() !in 3..MAX_UPLOAD_BYTES) return reply(Response.Status.BAD_REQUEST, "invalid_photo")
      // The SDK converts BLE payloads to JPEG before relaying them.
      source.inputStream().use {
        if (it.read() != 0xff || it.read() != 0xd8 || it.read() != 0xff) {
          return reply(Response.Status.BAD_REQUEST, "not_jpeg")
        }
      }
      check(photoDirectory.isDirectory || photoDirectory.mkdirs())
      val destination = File(photoDirectory, "${System.currentTimeMillis()}-${UUID.randomUUID()}.jpg")
      source.copyTo(destination)
      if (!accepts(requestId)) {
        destination.delete()
        return reply(Response.Status.CONFLICT, "request_cancelled")
      }
      onPhoto(requestId, destination)
      newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
    } catch (error: Exception) {
      reply(Response.Status.INTERNAL_ERROR, "receive_failed")
    }
  }

  private fun reply(status: Response.Status, error: String) =
    newFixedLengthResponse(status, "application/json", "{\"success\":false,\"error\":\"$error\"}")

  override fun close() = stop()

  companion object { const val MAX_UPLOAD_BYTES = 24L * 1024 * 1024 }
}
