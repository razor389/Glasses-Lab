package com.mentra.asg_client.settings;

import com.mentra.asg_client.AsgConstants;

/** Immutable, explicitly opted-in limits for a recording archived by the personal companion. */
public final class SegmentedVideoOptions {
    public final long segmentMaxBytes;
    public final long maxBacklogBytes;
    public final long maxSessionBytes;

    /** Reject unsafe limits rather than silently starting an unbounded recording. */
    public SegmentedVideoOptions(long segmentMaxBytes, long maxBacklogBytes, long maxSessionBytes) {
        if (segmentMaxBytes < AsgConstants.VIDEO_SEGMENT_MIN_BYTES
                || segmentMaxBytes > AsgConstants.VIDEO_SEGMENT_MAX_BYTES
                || maxBacklogBytes < 3 * segmentMaxBytes
                || maxBacklogBytes > AsgConstants.VIDEO_SEGMENT_MAX_BACKLOG_BYTES
                || maxSessionBytes < segmentMaxBytes) {
            throw new IllegalArgumentException("Invalid segmented recording byte limits");
        }
        this.segmentMaxBytes = segmentMaxBytes;
        this.maxBacklogBytes = maxBacklogBytes;
        this.maxSessionBytes = maxSessionBytes;
    }
}
