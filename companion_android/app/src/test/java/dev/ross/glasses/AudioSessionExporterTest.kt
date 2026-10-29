package dev.ross.glasses

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AudioSessionExporterTest {
  private val root = Files.createTempDirectory("lab-export").toFile()
  @After fun cleanup() { root.deleteRecursively() }
  private fun audio(): List<AudioSegment> {
    val saved = mutableListOf<AudioSegment>()
    SegmentedAudioWriter(File(root, "audio"), {}, saved::add, segmentBytes = 320).use { it.append(ByteArray(640)) }
    return saved
  }
  @Test fun bundleContainsOriginalWavsAndMetadata() {
    val source = audio()
    val file = AudioSessionExporter.save(source, File(root, "exports"), "{\"gaps\":[]}", {})
    ZipFile(file).use { zip ->
      assertEquals(3, zip.size())
      source.forEach { assertArrayEquals(it.file.readBytes(), zip.getInputStream(zip.getEntry(it.file.name)).readBytes()) }
    }
  }
  @Test fun failedExportRemovesOnlyItsTemporaryCopy() {
    val source = audio()
    var calls = 0
    try {
      AudioSessionExporter.save(source, File(root, "exports"), "{}", { if (++calls == 3) throw IOException("full") })
      fail("Expected storage failure")
    } catch (_: IOException) {}
    assertTrue(source.all { it.file.isFile })
    assertTrue(File(root, "exports").listFiles()!!.isEmpty())
  }
}
