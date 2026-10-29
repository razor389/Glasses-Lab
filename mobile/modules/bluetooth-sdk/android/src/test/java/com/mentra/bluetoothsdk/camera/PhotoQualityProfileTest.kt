package com.mentra.bluetoothsdk.camera

import com.mentra.bluetoothsdk.PhotoMode
import com.mentra.bluetoothsdk.PhotoQualityProfile
import com.mentra.bluetoothsdk.PhotoRequest
import com.mentra.bluetoothsdk.PhotoSize
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PhotoQualityProfileTest {
    @Test
    fun `legacy upload requests omit quality profile from the wire`() {
        val request = PhotoRequest.fromMap(mapOf("size" to "high", "webhookUrl" to "https://example.com/upload"))
        val payload = JSONObject()
        PhotoRequest.appendQualityProfile(payload, request)

        assertThat(request.qualityProfile).isEqualTo(PhotoQualityProfile.DEFAULT)
        assertThat(payload.has("qualityProfile")).isFalse()
    }

    @Test
    fun `local archival profile survives parsing routing copies and serialization`() {
        val original = PhotoRequest.fromMap(mapOf(
            "size" to "high", "save" to true, "qualityProfile" to "local", "requestId" to "original",
        ))
        val routed = original.copy(requestId = "routed", exposureTimeNs = 1000000.0)
        val payload = JSONObject()
        PhotoRequest.appendQualityProfile(payload, routed)

        assertThat(routed.qualityProfile).isEqualTo(PhotoQualityProfile.LOCAL)
        assertThat(routed.webhookUrl).isEmpty()
        assertThat(routed.save).isTrue()
        assertThat(routed.size).isEqualTo(PhotoSize.HIGH)
        assertThat(payload.getString("qualityProfile")).isEqualTo("local")
        assertThat(original.requestId).isEqualTo("original")
    }

    @Test
    fun `local quality rejects non archival and text requests before sending`() {
        val request = PhotoRequest(size = PhotoSize.HIGH, webhookUrl = "", save = true, qualityProfile = PhotoQualityProfile.LOCAL)
        assertThatThrownBy { request.copy(save = false) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { request.copy(webhookUrl = "https://example.com/upload") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { request.copy(mode = PhotoMode.TEXT) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unknown or non string quality profiles are rejected`() {
        for (invalid in listOf("button", "LOCAL", 1, true)) {
            assertThatThrownBy {
                PhotoRequest.fromMap(mapOf("size" to "high", "save" to true, "qualityProfile" to invalid))
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `explicit default retains legacy text and upload behavior`() {
        val request = PhotoRequest.fromMap(mapOf(
            "size" to "max", "mode" to "text", "qualityProfile" to "default", "webhookUrl" to "https://example.com/upload",
        ))
        assertThat(request.mode).isEqualTo(PhotoMode.TEXT)
        assertThat(request.qualityProfile).isEqualTo(PhotoQualityProfile.DEFAULT)
    }
}
