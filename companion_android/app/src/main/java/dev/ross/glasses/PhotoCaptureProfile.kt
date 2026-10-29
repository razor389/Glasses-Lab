package dev.ross.glasses

import com.mentra.bluetoothsdk.*

data class PhotoCaptureProfile(val size: PhotoSize = PhotoSize.HIGH, val enableFlash: Boolean = true) {
  val summary get() = when (size) {
    PhotoSize.LOW -> "Low"
    PhotoSize.MEDIUM -> "Medium"
    PhotoSize.HIGH -> "High"
    PhotoSize.MAX -> "Maximum"
  }
  fun request(id: String) = PhotoRequest(requestId = id, size = size, webhookUrl = "",
    transferMethod = "auto", compress = PhotoCompression.NONE, sound = true, save = true,
    qualityProfile = PhotoQualityProfile.LOCAL, enableFlash = enableFlash)
  fun encode() = "1,${size.value},$enableFlash"

  companion object {
    fun decode(value: String?): PhotoCaptureProfile? = runCatching {
      val parts = requireNotNull(value).split(',')
      require(parts.size in 2..3 && parts[0] == "1") {
        "Unknown photo profile"
      }

      PhotoCaptureProfile(
        size = PhotoSize.values().first { it.value == parts[1] },
        enableFlash = parts.getOrNull(2)?.toBooleanStrict() ?: true,
      )
    }.getOrNull()
  }
}
