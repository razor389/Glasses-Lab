package dev.ross.glasses

import com.mentra.bluetoothsdk.*

data class PhotoCaptureProfile(val size: PhotoSize = PhotoSize.HIGH) {
  val summary get() = when (size) {
    PhotoSize.LOW -> "Low"
    PhotoSize.MEDIUM -> "Medium"
    PhotoSize.HIGH -> "High"
    PhotoSize.MAX -> "Maximum"
  }
  fun request(id: String) = PhotoRequest(requestId = id, size = size, webhookUrl = "",
    transferMethod = "auto", compress = PhotoCompression.NONE, sound = true, save = true,
    qualityProfile = PhotoQualityProfile.LOCAL)
  fun encode() = "1,${size.value}"
  companion object {
    fun decode(value: String?): PhotoCaptureProfile? = PhotoSize.values()
      .firstOrNull { value == "1,${it.value}" }?.let(::PhotoCaptureProfile)
  }
}
