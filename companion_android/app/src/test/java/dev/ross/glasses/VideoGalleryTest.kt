package dev.ross.glasses

import java.io.Reader
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class VideoGalleryTest {
  @Test fun onlyTheRequestedRecordingIsSelected() {
    val json = """{"data":{"photos":[
      {"name":"VID_newer_unrelated/video.mp4","request_id":"unrelated","size":99},
      {"name":"VID_capture_wanted/video.mp4","request_id":"wanted","size":500}
    ]}}"""
    assertEquals(RemoteVideo("VID_capture_wanted/video.mp4", 500), VideoGallery.find(json, "wanted"))
  }
  @Test fun legacyCaptureDirectoryCanCorrelateWithoutExplicitRequestField() {
    val json = """{"data":{"photos":[{"name":"VID_20261026_120000_123_abcd_wanted/video.mp4","size":100}]}}"""
    assertNotNull(VideoGallery.find(json, "wanted"))
  }
  @Test fun explicitMismatchedIdIsNeverOverriddenByAFilename() {
    val json = """{"data":{"photos":[{"name":"VID_capture_wanted/video.mp4","request_id":"other","size":100}]}}"""
    assertNull(VideoGallery.find(json, "wanted"))
  }
  @Test fun traversalsAndNonVideoFilesAreRejected() {
    assertFalse(VideoGallery.validName("VID_wanted/../../video.mp4"))
    assertFalse(VideoGallery.validName("VID_wanted/thumbnail.jpg"))
    assertFalse(VideoGallery.validName("https://example.com/video.mp4"))
  }
  @Test fun networkDestinationMustBeAPrivateIpv4Address() {
    assertTrue(VideoGallery.privateIpv4("192.168.43.1"))
    assertTrue(VideoGallery.privateIpv4("10.0.0.1"))
    assertFalse(VideoGallery.privateIpv4("127.0.0.1"))
    assertFalse(VideoGallery.privateIpv4("8.8.8.8"))
    assertFalse(VideoGallery.privateIpv4("192.168.1.999"))
    assertFalse(VideoGallery.privateIpv4("192.168.1.1.example.com"))
    assertFalse(VideoGallery.privateIpv4("010.0.0.1"))
    assertFalse(VideoGallery.privateIpv4("+10.0.0.1"))
    assertFalse(VideoGallery.privateIpv4("192.168.01.1"))
  }
  @Test fun nullRequestFieldStillAllowsLegacyFilenameCorrelation() {
    val json = """{"data":{"photos":[{"name":"VID_capture_wanted/base.mp4","request_id":null,"size":100}]}}"""
    assertEquals(RemoteVideo("VID_capture_wanted/base.mp4", 100), VideoGallery.find(json, "wanted"))
  }
  @Test fun boundedReaderPreservesTheCompleteGalleryResponse() {
    val json = """{"data":{"photos":[]}}"""
    assertEquals(json, VideoGallery.read(StringReader(json)))
  }
  @Test fun endlessResponseIsStoppedBeforeUnboundedAllocation() {
    var consumed = 0
    val reader = object : Reader() {
      override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        buffer.fill(' ', offset, offset + length)
        consumed += length
        return length
      }
      override fun close() {}
    }
    assertThrows(IllegalStateException::class.java) { VideoGallery.read(reader) }
    assertTrue(consumed <= VideoGallery.MAX_GALLERY_CHARS + 8192)
  }
}
