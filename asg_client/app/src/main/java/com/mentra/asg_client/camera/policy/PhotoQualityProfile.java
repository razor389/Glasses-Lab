package com.mentra.asg_client.camera.policy;

import com.mentra.asg_client.AsgConstants;
import org.json.JSONObject;

/** Size/JPEG policy selected independently of the capture's SDK or button origin. */
public enum PhotoQualityProfile {
    DEFAULT,
    LOCAL;

    /** Parses the optional wire field strictly; existing requests keep their original policy. */
    public static PhotoQualityProfile fromJson(JSONObject data) {
        if (data == null || !data.has("qualityProfile")) return DEFAULT;
        Object value = data.opt("qualityProfile");
        if (AsgConstants.PHOTO_QUALITY_PROFILE_DEFAULT.equals(value)) return DEFAULT;
        if (AsgConstants.PHOTO_QUALITY_PROFILE_LOCAL.equals(value)) return LOCAL;
        throw new IllegalArgumentException("qualityProfile must be default or local.");
    }

    /** Checks the opt-in contract before any output allocation or capture side effects. */
    public static void validateRequest(JSONObject data) {
        if (fromJson(data) != LOCAL) return;
        if (!Boolean.TRUE.equals(data.opt("save"))
                || !data.optString("webhookUrl", "").isEmpty()
                || !PhotoMode.PHOTO.equals(data.optString("mode", PhotoMode.PHOTO))) {
            throw new IllegalArgumentException(
                    "Local photo quality requires mode=photo, save=true and no webhookUrl.");
        }
    }

    /** Returns the effective quality policy without changing source or request identity. */
    public boolean usesSdkQuality(boolean fromSdk) {
        return fromSdk && this != LOCAL;
    }

    /** Effective policy name for resolved capture metadata. */
    public String resolvedName(boolean fromSdk) {
        return usesSdkQuality(fromSdk)
                ? AsgConstants.PHOTO_QUALITY_PROFILE_SDK
                : AsgConstants.PHOTO_QUALITY_PROFILE_LOCAL;
    }
}
