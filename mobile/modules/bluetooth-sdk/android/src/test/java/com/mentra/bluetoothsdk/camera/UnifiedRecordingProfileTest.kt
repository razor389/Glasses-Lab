package com.mentra.bluetoothsdk.camera

import com.mentra.bluetoothsdk.RecordingProfileCommand
import com.mentra.bluetoothsdk.UnifiedRecordingProfile
import com.mentra.bluetoothsdk.VideoRecordingCommand
import com.mentra.bluetoothsdk.VideoRecordingRequest
import com.mentra.bluetoothsdk.VideoRecordingStatusEvent
import org.junit.Assert.*
import org.junit.Test

class UnifiedRecordingProfileTest {
    private val profile = UnifiedRecordingProfile("revision-one", 1280, 720, 30, 0, "phone")

    @Test fun atomicProfileIncludesEveryEffectiveSettingAndNoConsumableGrant() {
        val fields = RecordingProfileCommand.fields("settings-request", profile)
        assertEquals("set_recording_profile", fields["type"])
        assertEquals("settings-request", fields["requestId"])
        assertEquals(profile.values(), fields - setOf("type", "requestId"))
        assertEquals(0, fields["maxRecordingTimeMinutes"])
        assertFalse(fields.containsKey("maxSessionBytes"))
        assertFalse(fields.containsKey("grantId"))
        assertFalse(fields.containsKey("sessionId"))
    }

    @Test fun reconnectProfileIsOneCompleteCommandWithoutReplayableReadiness() {
        val fields = RecordingProfileCommand.fields(null, profile)
        assertEquals(profile.values() + ("type" to "set_recording_profile"), fields)
        assertFalse(fields.containsKey("requestId"))
        assertFalse(fields.containsKey("ttlSeconds"))
    }

    @Test fun localAndPhoneModesAcceptTheSameExactDurationChoices() {
        for (mode in listOf("local", "phone")) {
            for (minutes in listOf(0, 3, 5, 10, 15, 20, 30, 60, 120)) {
                assertEquals(minutes, profile.copy(mode = mode, maxRecordingTimeMinutes = minutes).maxRecordingTimeMinutes)
            }
            assertThrows(IllegalArgumentException::class.java) { profile.copy(mode = mode, maxRecordingTimeMinutes = 7) }
        }
    }

    @Test fun malformedProfilesCannotBeSentOrAdopted() {
        assertThrows(IllegalArgumentException::class.java) { profile.copy(revision = "") }
        assertThrows(IllegalArgumentException::class.java) { profile.copy(mode = "fallback") }
        assertThrows(IllegalArgumentException::class.java) { profile.copy(fps = 60) }
        assertThrows(IllegalArgumentException::class.java) { profile.copy(width = 1280, height = 1080) }
        assertThrows(IllegalArgumentException::class.java) { profile.copy(maxBacklogBytes = 1) }
        assertEquals(profile, UnifiedRecordingProfile.fromMap(profile.values()))
        assertNull(UnifiedRecordingProfile.fromMap(profile.values() - "revision"))
        assertNull(UnifiedRecordingProfile.fromMap(profile.values() + ("fps" to 15.5)))
        assertNull(UnifiedRecordingProfile.fromMap(profile.values() + ("width" to Double.POSITIVE_INFINITY)))
    }

    @Test fun unifiedStartUsesPersistedProfileWithoutConflictingOverrides() {
        val request = VideoRecordingRequest("session", true, true,
            useRecordingProfile = true, profileRevision = profile.revision)
        assertEquals(mapOf("type" to "start_video_recording", "requestId" to "session", "save" to true,
            "sound" to true, "useRecordingProfile" to true, "profileRevision" to profile.revision),
            VideoRecordingCommand.fields(request))
        assertThrows(IllegalArgumentException::class.java) { VideoRecordingCommand.fields(request.copy(profileRevision = null)) }
        assertThrows(IllegalArgumentException::class.java) { VideoRecordingCommand.fields(request.copy(fps = 15)) }
        assertThrows(IllegalArgumentException::class.java) { VideoRecordingCommand.fields(request.copy(save = false)) }
    }

    @Test fun unsolicitedButtonStatusRetainsIdentityAndImmutableProfileForAdoption() {
        val metadata = mapOf("origin" to "button", "segmented" to true, "profile" to profile.values(),
            "grantId" to "grant-one", "sessionId" to "physical-session", "recording" to true, "duration_ms" to 1234L)
        for (payload in listOf(metadata, mapOf("data" to metadata))) {
            val event = VideoRecordingStatusEvent(payload + mapOf("requestId" to "physical-session",
                "success" to true, "status" to "recording_started"))
            assertEquals("physical-session", event.requestId)
            assertEquals("physical-session", event.sessionId)
            assertEquals("button", event.origin)
            assertEquals(true, event.segmented)
            assertEquals(profile, event.profile)
            assertEquals("grant-one", event.grantId)
            assertEquals(true, event.recording)
            assertEquals(1234L, event.durationMs)
        }
        val legacy = VideoRecordingStatusEvent(emptyMap())
        assertNull(legacy.profile)
        assertNull(legacy.origin)
        assertNull(legacy.segmented)
    }

    @Test fun statusQueryDoesNotInventASessionIdentity() {
        assertEquals(mapOf("type" to "get_video_recording_status"), RecordingProfileCommand.statusFields(null))
        assertEquals(mapOf("type" to "get_video_recording_status", "requestId" to "known-session"),
            RecordingProfileCommand.statusFields("known-session"))
    }
}
