package dev.ross.glasses

import org.junit.Assert.*
import org.junit.Test

class AuxiliaryCleanupSummaryTest {
  private fun response(deleted: String = "false", files: String = "28", bytes: String = "2712861", version: Int = 1) =
    """{"status":"success","data":{"api_version":$version,"cleanup_id":"${"a".repeat(64)}","files":$files,"bytes":$bytes,"retained_files":1,"deleted":$deleted}}"""

  @Test fun previewAndConfirmationHaveDistinctMeanings() {
    val preview = AuxiliaryCleanupSummary.parse(response(), false)
    assertEquals(28, preview.files)
    assertEquals(2712861L, preview.bytes)
    assertEquals(1, preview.retainedFiles)
    assertEquals(preview, AuxiliaryCleanupSummary.parse(response("true"), true))
    rejects { AuxiliaryCleanupSummary.parse(response(), true) }
    rejects { AuxiliaryCleanupSummary.parse(response("true"), false) }
    rejects { AuxiliaryCleanupSummary.parse(response("\"true\""), true) }
  }

  @Test fun rejectsMalformedCountsAndUnsupportedVersions() {
    for (value in listOf("-1", "1.5", "\"28\"", "2147483648", "null")) {
      rejects { AuxiliaryCleanupSummary.parse(response(files = value), false) }
    }
    rejects { AuxiliaryCleanupSummary.parse(response(bytes = "-1"), false) }
    rejects { AuxiliaryCleanupSummary.parse(response(version = 2), false) }
    rejects { AuxiliaryCleanupSummary.parse(response().replace("a".repeat(64), "bad"), false) }
    assertEquals(0, AuxiliaryCleanupSummary.parse(response(files = "0", bytes = "0"), false).files)
  }

  private fun rejects(block: () -> Unit) {
    try { block(); fail("Invalid cleanup response was accepted") }
    catch (_: IllegalArgumentException) { }
  }
}
