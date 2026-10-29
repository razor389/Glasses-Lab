package com.mentra.asg_client.io.server.services;

import com.mentra.asg_client.camera.lifecycle.VideoSegmentFiles;

/** Immutable phone capacity reserved for one particular recording before either start source runs. */
public final class RecordingGrant {
    public final String profileRevision;
    public final String grantId;
    public final String sessionId;
    public final long maxSessionBytes;

    public RecordingGrant(String profileRevision, String grantId, String sessionId, long maxSessionBytes) {
        VideoSegmentFiles.validateRequestId(profileRevision);
        VideoSegmentFiles.validateRequestId(grantId);
        VideoSegmentFiles.validateRequestId(sessionId);
        if (maxSessionBytes <= 0) throw new IllegalArgumentException("Invalid recording allowance");
        this.profileRevision = profileRevision;
        this.grantId = grantId;
        this.sessionId = sessionId;
        this.maxSessionBytes = maxSessionBytes;
    }

    boolean sameAs(RecordingGrant other) {
        return grantId.equals(other.grantId) && sessionId.equals(other.sessionId)
                && profileRevision.equals(other.profileRevision) && maxSessionBytes == other.maxSessionBytes;
    }
}
