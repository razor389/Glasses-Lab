package com.mentra.bluetoothsdk.camera

import com.mentra.bluetoothsdk.SegmentedVideoOptions
import com.mentra.bluetoothsdk.VideoRecordingCommand
import com.mentra.bluetoothsdk.VideoRecordingRequest
import com.mentra.bluetoothsdk.VideoRecordingStatusEvent
import org.junit.Assert.*
import org.junit.Test

class SegmentedVideoOptionsTest {
    private val mib = 1024L * 1024

    @Test fun legacyRecordingKeepsItsWireContract() {
        val fields = VideoRecordingCommand.fields(VideoRecordingRequest("legacy", save = true, sound = false))
        assertEquals(mapOf("type" to "start_video_recording", "requestId" to "legacy", "save" to true, "sound" to false), fields)
    }

    @Test fun partialSettingsDoNotOverrideSavedResolution() {
        val fields = VideoRecordingCommand.fields(VideoRecordingRequest("fps-only", true, true, fps = 15, maxRecordingTimeMinutes = 5))
        assertEquals(mapOf("fps" to 15), fields["settings"])
        assertEquals(5, fields["maxRecordingTimeMinutes"])
        assertFalse(fields.containsKey("segmented"))
        assertFalse(fields.containsKey("maxSessionBytes"))
    }

    @Test fun segmentedWireUsesLongByteLimitsAndExplicitUnlimitedDuration() {
        val allowance = 10L * 1024 * 1024 * 1024 + 7
        val fields = VideoRecordingCommand.fields(VideoRecordingRequest("long", true, true,
            width = 1280, height = 720, fps = 30, segmented = SegmentedVideoOptions(maxSessionBytes = allowance)))
        assertEquals(true, fields["segmented"])
        assertEquals(64 * mib, fields["segmentMaxBytes"])
        assertEquals(256 * mib, fields["maxBacklogBytes"])
        assertEquals(allowance, fields["maxSessionBytes"])
        assertEquals(0, fields["maxRecordingTimeMinutes"])
        assertEquals(mapOf("width" to 1280, "height" to 720, "fps" to 30), fields["settings"])
    }

    @Test fun durationAndCustomBoundsSurviveRequestRouting() {
        val options = SegmentedVideoOptions(maxSessionBytes = 5L * 1024 * mib, segmentMaxBytes = 128 * mib, maxBacklogBytes = 512 * mib)
        val request = VideoRecordingRequest("original", true, true, maxRecordingTimeMinutes = 120, segmented = options)
        val fields = VideoRecordingCommand.fields(request.copy(requestId = "routed"))
        assertEquals("routed", fields["requestId"])
        assertEquals(120, fields["maxRecordingTimeMinutes"])
        assertEquals(128 * mib, fields["segmentMaxBytes"])
        assertEquals(512 * mib, fields["maxBacklogBytes"])
        assertEquals(options.maxSessionBytes, fields["maxSessionBytes"])
    }

    @Test fun acceptedBoundsIncludeBothEndpoints() {
        SegmentedVideoOptions(maxSessionBytes = 8 * mib, segmentMaxBytes = 8 * mib, maxBacklogBytes = 24 * mib)
        SegmentedVideoOptions(maxSessionBytes = 256 * mib, segmentMaxBytes = 256 * mib, maxBacklogBytes = 4096 * mib)
    }

    @Test fun invalidSegmentsAreRejectedBeforeMultiplicationOrTransmission() {
        for (invalid in listOf(Long.MIN_VALUE, 0, 8 * mib - 1, 256 * mib + 1, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                SegmentedVideoOptions(maxSessionBytes = Long.MAX_VALUE, segmentMaxBytes = invalid)
            }
        }
    }

    @Test fun insufficientAndOversizedBacklogsAreRejected() {
        for (invalid in listOf(0, 192 * mib - 1, 4096 * mib + 1, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                SegmentedVideoOptions(maxSessionBytes = 1024 * mib, maxBacklogBytes = invalid)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { SegmentedVideoOptions(maxSessionBytes = 64 * mib - 1) }
        assertThrows(IllegalArgumentException::class.java) { SegmentedVideoOptions(maxSessionBytes = 0) }
    }

    @Test fun stopMetadataSupportsNestedAndTopLevelPayloadsWithoutInventingCounts() {
        val metadata = mapOf("sessionId" to "session-one", "segmentCount" to 3, "stopReason" to "max_session_bytes")
        for (values in listOf(metadata, mapOf("data" to metadata))) {
            val event = VideoRecordingStatusEvent(values)
            assertEquals("session-one", event.sessionId)
            assertEquals(3, event.segmentCount)
            assertEquals("max_session_bytes", event.stopReason)
        }
        val legacy = VideoRecordingStatusEvent(emptyMap())
        assertNull(legacy.sessionId)
        assertNull(legacy.segmentCount)
        assertNull(legacy.stopReason)
        assertEquals(0, VideoRecordingStatusEvent(mapOf("segmentCount" to 0)).segmentCount)
    }
}
