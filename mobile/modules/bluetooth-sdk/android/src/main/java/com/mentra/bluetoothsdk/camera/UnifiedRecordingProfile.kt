package com.mentra.bluetoothsdk

/** One immutable profile for phone and hardware-button video, independent of temporary phone grants. */
data class UnifiedRecordingProfile(
    val revision: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val maxRecordingTimeMinutes: Int,
    val mode: String,
    val segmentMaxBytes: Long = 64L * 1024 * 1024,
    val maxBacklogBytes: Long = 256L * 1024 * 1024,
) {
    init {
        require(revision.isNotBlank() && revision.toByteArray(Charsets.UTF_8).size <= 128) { "A recording profile revision is required." }
        require((width == 1280 && height == 720) || (width == 1920 && height == 1080)) { "Unsupported recording resolution." }
        require(fps in listOf(5, 15, 30)) { "Unsupported recording frame rate." }
        require(maxRecordingTimeMinutes in DURATIONS) { "Unsupported recording duration." }
        require(mode == "local" || mode == "phone") { "Recording mode must be local or phone." }
        SegmentedVideoOptions(segmentMaxBytes, segmentMaxBytes, maxBacklogBytes)
    }

    /** Wire and acknowledgement fields, containing no consumable phone-readiness authority. */
    fun values(): Map<String, Any> = linkedMapOf(
        "revision" to revision, "width" to width, "height" to height, "fps" to fps,
        "maxRecordingTimeMinutes" to maxRecordingTimeMinutes, "mode" to mode,
        "segmentMaxBytes" to segmentMaxBytes, "maxBacklogBytes" to maxBacklogBytes,
    )

    companion object {
        val DURATIONS: Set<Int> = setOf(0, 3, 5, 10, 15, 20, 30, 60, 120)

        /** Missing or malformed snapshots remain unknown instead of inventing settings for adoption. */
        @JvmStatic fun fromMap(values: Map<String, Any>): UnifiedRecordingProfile? = runCatching {
            fun integer(key: String): Long {
                val number = values[key] as? Number ?: error("Missing $key")
                val value = number.toLong()
                require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) { "Invalid $key" }
                return value
            }
            fun smallInteger(key: String) = integer(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
            UnifiedRecordingProfile(
                revision = values["revision"] as? String ?: error("Missing revision"),
                width = smallInteger("width"), height = smallInteger("height"), fps = smallInteger("fps"),
                maxRecordingTimeMinutes = smallInteger("maxRecordingTimeMinutes"),
                mode = values["mode"] as? String ?: error("Missing mode"),
                segmentMaxBytes = integer("segmentMaxBytes"), maxBacklogBytes = integer("maxBacklogBytes"),
            )
        }.getOrNull()
    }
}

internal object RecordingProfileCommand {
    fun fields(requestId: String?, profile: UnifiedRecordingProfile): Map<String, Any> = linkedMapOf<String, Any>(
        "type" to "set_recording_profile",
    ).apply {
        requestId?.takeIf { it.isNotBlank() }?.let { put("requestId", it) }
        putAll(profile.values())
    }

    fun statusFields(requestId: String?): Map<String, Any> = linkedMapOf<String, Any>(
        "type" to "get_video_recording_status",
    ).apply { requestId?.takeIf { it.isNotBlank() }?.let { put("requestId", it) } }
}
