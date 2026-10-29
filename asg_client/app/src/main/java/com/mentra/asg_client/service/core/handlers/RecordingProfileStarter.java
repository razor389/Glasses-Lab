package com.mentra.asg_client.service.core.handlers;

import com.mentra.asg_client.AsgConstants;
import com.mentra.asg_client.io.media.core.MediaCaptureService;
import com.mentra.asg_client.io.server.services.RecordingGrant;
import com.mentra.asg_client.service.legacy.managers.AsgClientServiceManager;
import com.mentra.asg_client.settings.AsgSettings;
import com.mentra.asg_client.settings.RecordingProfile;
import com.mentra.asg_client.settings.SegmentedVideoOptions;
import com.mentra.asg_client.settings.VideoSettings;

/** One admission path for physical-button and app starts using the canonical recording profile. */
public final class RecordingProfileStarter {
    private RecordingProfileStarter() {}

    public static String start(AsgClientServiceManager manager, String requestedSessionId,
            String expectedRevision, String origin, boolean sound) {
        if (manager.getContext() == null || !AsgConstants.PERSONAL_ASG_PACKAGE.equals(manager.getContext().getPackageName())) {
            throw new IllegalStateException("profile_unsupported");
        }
        AsgSettings settings = manager.getAsgSettings();
        if (settings == null) throw new IllegalStateException("profile_unavailable");
        synchronized (settings) {
            RecordingProfile profile = settings.getRecordingProfile();
            if (profile == null) throw new IllegalStateException("profile_unavailable");
            if (expectedRevision != null && !expectedRevision.equals(profile.revision)) throw new IllegalStateException("profile_mismatch");
            MediaCaptureService capture = manager.getMediaCaptureService();
            if (capture == null) throw new IllegalStateException("service_unavailable");
            if (capture.isVideoRecordingActiveOrStarting()) throw new IllegalStateException("recording_busy");
            RecordingGrant grant = null;
            String sessionId = requestedSessionId;
            SegmentedVideoOptions segments = null;
            if (profile.offloadsToPhone()) {
                if (manager.getCameraServer() == null) throw new IllegalStateException("phone_not_ready");
                grant = manager.getCameraServer().consumeRecordingGrant(profile.revision, requestedSessionId);
                sessionId = grant.sessionId;
                segments = new SegmentedVideoOptions(profile.segmentMaxBytes, profile.maxBacklogBytes, grant.maxSessionBytes);
            } else if (sessionId == null || sessionId.isEmpty()) {
                sessionId = "button_" + java.util.UUID.randomUUID().toString();
            }
            VideoSettings video = new VideoSettings(profile.width, profile.height, profile.fps, segments,
                    profile, origin, grant != null ? grant.grantId : null);
            if (grant != null && !settings.claimRecordingGrant(grant.grantId, sessionId)) {
                capture.finishProfileRecording(sessionId, video, 0, "grant_persistence_error", false);
                return sessionId;
            }
            try {
                capture.handleStartVideoCommand(sessionId, true, video, profile.enableFlash, sound, profile.maxRecordingTimeMinutes);
            } catch (Exception error) {
                capture.finishProfileRecording(sessionId, video, 0, "start_error", false);
            }
            return sessionId;
        }
    }
}
