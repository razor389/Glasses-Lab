package com.mentra.asg_client.settings;

import com.mentra.asg_client.AsgConstants;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Immutable recording choices shared by the app and the physical camera button. */
public final class RecordingProfile {
    public final String revision;
    public final int width;
    public final int height;
    public final int fps;
    public final int maxRecordingTimeMinutes;
    public final String mode;
    public final long segmentMaxBytes;
    public final long maxBacklogBytes;

    public RecordingProfile(String revision, int width, int height, int fps, int minutes,
            String mode, long segmentMaxBytes, long maxBacklogBytes) {
        if (revision == null || revision.isEmpty() || revision.getBytes(StandardCharsets.UTF_8).length > 128
                || !((width == 1920 && height == 1080) || (width == 1280 && height == 720))
                || !(fps == 5 || fps == 15 || fps == 30) || !isDurationAllowed(minutes)
                || !("local".equals(mode) || "phone".equals(mode))) {
            throw new IllegalArgumentException("Invalid recording profile");
        }
        new SegmentedVideoOptions(segmentMaxBytes, maxBacklogBytes, segmentMaxBytes);
        this.revision = revision;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.maxRecordingTimeMinutes = minutes;
        this.mode = mode;
        this.segmentMaxBytes = segmentMaxBytes;
        this.maxBacklogBytes = maxBacklogBytes;
    }

    public boolean offloadsToPhone() { return "phone".equals(mode); }

    public boolean sameValues(RecordingProfile other) {
        return revision.equals(other.revision) && width == other.width && height == other.height
                && fps == other.fps && maxRecordingTimeMinutes == other.maxRecordingTimeMinutes
                && mode.equals(other.mode) && segmentMaxBytes == other.segmentMaxBytes && maxBacklogBytes == other.maxBacklogBytes;
    }

    public static boolean isDurationAllowed(int minutes) {
        return minutes == 0 || minutes == 3 || minutes == 5 || minutes == 10 || minutes == 15
                || minutes == 20 || minutes == 30 || minutes == 60 || minutes == 120;
    }

    public static RecordingProfile fromJson(JSONObject json) throws JSONException {
        for (String key : new String[] {"width", "height", "fps", "maxRecordingTimeMinutes", "segmentMaxBytes", "maxBacklogBytes"}) {
            Object value = json.get(key);
            if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException(key + " must be an integer");
            if (!key.endsWith("Bytes") && (((Number) value).longValue() < 0 || ((Number) value).longValue() > Integer.MAX_VALUE)) {
                throw new IllegalArgumentException(key + " is out of range");
            }
        }
        return new RecordingProfile(json.getString("revision"), json.getInt("width"),
                json.getInt("height"), json.getInt("fps"), json.getInt("maxRecordingTimeMinutes"),
                json.getString("mode"), json.optLong("segmentMaxBytes", AsgConstants.VIDEO_SEGMENT_DEFAULT_BYTES),
                json.optLong("maxBacklogBytes", AsgConstants.VIDEO_SEGMENT_DEFAULT_BACKLOG_BYTES));
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("revision", revision).put("width", width).put("height", height)
                .put("fps", fps).put("maxRecordingTimeMinutes", maxRecordingTimeMinutes).put("mode", mode)
                .put("segmentMaxBytes", segmentMaxBytes).put("maxBacklogBytes", maxBacklogBytes);
    }

    public RecordingProfile withVideo(VideoSettings settings) {
        return new RecordingProfile(java.util.UUID.randomUUID().toString(), settings.width, settings.height,
                settings.fps, maxRecordingTimeMinutes, mode, segmentMaxBytes, maxBacklogBytes);
    }

    public RecordingProfile withDuration(int minutes) {
        return new RecordingProfile(java.util.UUID.randomUUID().toString(), width, height, fps,
                minutes, mode, segmentMaxBytes, maxBacklogBytes);
    }
}
