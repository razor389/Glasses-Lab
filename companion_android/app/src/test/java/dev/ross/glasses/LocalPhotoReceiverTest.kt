package dev.ross.glasses

import java.io.File
import java.nio.file.Files
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LocalPhotoReceiverTest {
  private lateinit var directory: File
  private lateinit var receiver: LocalPhotoReceiver
  private var delivered: File? = null
  private val client = OkHttpClient()

  @Before fun setup() {
    directory = Files.createTempDirectory("glasses-photo-test").toFile()
    receiver = LocalPhotoReceiver(directory, { it == "expected-photo" }) { _, file -> delivered = file }
    receiver.start(3000, true)
  }

  @After fun cleanup() {
    receiver.close()
    directory.deleteRecursively()
    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()
  }

  private fun upload(id: String, token: String = receiver.token, jpeg: Boolean = true): Int {
    val bytes = if (jpeg) byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()) else byteArrayOf(1, 2, 3, 4)
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("requestId", id)
      .addFormDataPart("photo", "camera.jpg", bytes.toRequestBody("image/jpeg".toMediaType())).build()
    val request = Request.Builder().url(receiver.uploadUrl).header("Authorization", "Bearer $token").post(body).build()
    return client.newCall(request).execute().use { it.code }
  }

  @Test fun sdkMultipartPhotoIsStoredLocally() {
    assertEquals("127.0.0.1", receiver.hostname)
    assertEquals(200, upload("expected-photo"))
    assertNotNull(delivered)
    assertEquals(directory.canonicalPath, delivered!!.parentFile.canonicalPath)
    assertEquals(4L, delivered!!.length())
  }

  @Test fun wrongTokenCannotWritePhotos() {
    assertEquals(401, upload("expected-photo", "incorrect"))
    assertNull(delivered)
    assertTrue(directory.listFiles()!!.isEmpty())
  }

  @Test fun staleRequestCannotWritePhotos() {
    assertEquals(409, upload("stale-photo"))
    assertTrue(directory.listFiles()!!.isEmpty())
  }

  @Test fun unexpectedContentCannotBecomeAPhoto() {
    assertEquals(400, upload("expected-photo", jpeg = false))
    assertNull(delivered)
  }
}
