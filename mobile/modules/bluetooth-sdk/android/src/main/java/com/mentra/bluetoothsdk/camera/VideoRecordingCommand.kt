package com.mentra.bluetoothsdk

/** Shared command construction keeps optional recording modes out of legacy requests. */
internal object VideoRecordingCommand {
    fun fields(request: VideoRecordingRequest): Map<String, Any> = linkedMapOf<String, Any>(
        "type" to "start_video_recording",
        "requestId" to request.requestId,
        "save" to request.save,
        "sound" to request.sound,
    ).apply {
        if (request.useRecordingProfile) {
            require(!request.profileRevision.isNullOrBlank()) { "The applied recording profile revision is required." }
            require(request.save && request.width == 0 && request.height == 0 && request.fps == 0 &&
                request.maxRecordingTimeMinutes == 0 && request.segmented == null && request.enableFlash == null) {
                "A unified recording uses its saved profile, without per-request setting overrides."
            }
            put("useRecordingProfile", true)
            put("profileRevision", request.profileRevision)
            return@apply
        }
        require(request.profileRevision == null) { "profileRevision requires useRecordingProfile." }
        request.enableFlash?.let { put("flash", it) }
        // An ordinary request historically omits zero. Segmented requests explicitly
        // carry it so the session duration is independent of saved button defaults.
        if (request.maxRecordingTimeMinutes > 0 || request.segmented != null) {
            put("maxRecordingTimeMinutes", request.maxRecordingTimeMinutes)
        }
        val settings = linkedMapOf<String, Any>()
        if (request.width > 0) settings["width"] = request.width
        if (request.height > 0) settings["height"] = request.height
        if (request.fps > 0) settings["fps"] = request.fps
        if (settings.isNotEmpty()) put("settings", settings)
        request.segmented?.let { options ->
            put("segmented", true)
            put("segmentMaxBytes", options.segmentMaxBytes)
            put("maxBacklogBytes", options.maxBacklogBytes)
            put("maxSessionBytes", options.maxSessionBytes)
        }
    }
}
