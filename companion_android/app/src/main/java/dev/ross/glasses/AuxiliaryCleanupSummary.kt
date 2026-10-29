package dev.ross.glasses

/** A server preview identifies exactly the eligible files covered by the user's confirmation. */
data class AuxiliaryCleanupSummary(val id: String, val files: Int, val bytes: Long, val retainedFiles: Int) {
  companion object {
    fun parse(json: String, deleted: Boolean): AuxiliaryCleanupSummary {
      val data = RemoteMediaCatalog.data(json)
      fun number(key: String): Long {
        val value = data.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(value != null && value.isNumber && value.asString.matches(Regex("[0-9]+"))) {
          "Invalid auxiliary cleanup response."
        }
        return value.asString.toLong()
      }
      require(number("api_version") == 1L) { "Update the glasses build to clean auxiliary files." }
      val confirmation = data.get("deleted")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
      require(confirmation?.isBoolean == true && confirmation.asBoolean == deleted) {
        "Glasses did not confirm auxiliary cleanup."
      }
      val id = data.get("cleanup_id")?.asString.orEmpty()
      require(id.matches(Regex("[0-9a-f]{64}"))) { "Invalid auxiliary cleanup preview." }
      val files = number("files")
      val retained = number("retained_files")
      require(files <= Int.MAX_VALUE && retained <= Int.MAX_VALUE)
      return AuxiliaryCleanupSummary(id, files.toInt(), number("bytes"), retained.toInt())
    }
  }
}
