package dev.ross.glasses

import com.google.gson.JsonObject

/** Session membership must commit before an exact-source receipt can reclaim glasses media. */
internal object VerifiedMediaReceipt {
  suspend fun commit(
    file: RemoteMedia,
    fingerprint: MediaFingerprint,
    beforeReclaim: suspend () -> Unit,
    send: (JsonObject) -> String,
  ) {
    require(fingerprint.bytes == file.bytes && fingerprint.sha256.matches(Regex("[0-9a-f]{64}"))) {
      "Phone copy does not match the glasses source."
    }
    val receiptId = "phone-${file.location}-${file.key}"
    val receipt = JsonObject().apply {
      addProperty("receipt_id", receiptId)
      addProperty("file", file.name); addProperty("location", file.location)
      addProperty("size", fingerprint.bytes); addProperty("generation", file.generation)
      addProperty("sha256", fingerprint.sha256)
    }
    beforeReclaim()
    val result = RemoteMediaCatalog.data(send(receipt))
    check(result.get("receipt_id")?.asString == receiptId) { "Glasses cleanup was not confirmed; retry is safe." }
  }
}
