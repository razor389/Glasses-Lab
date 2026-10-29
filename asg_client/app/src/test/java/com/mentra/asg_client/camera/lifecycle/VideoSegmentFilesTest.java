package com.mentra.asg_client.camera.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mentra.asg_client.settings.SegmentedVideoOptions;
import com.mentra.asg_client.utils.GallerySyncFilter;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Covers durable readiness and byte accounting independent of camera hardware. */
public class VideoSegmentFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final long segmentBytes = 8L * 1024 * 1024;

    private VideoSegmentFiles files(File first) throws IOException {
        return new VideoSegmentFiles(first,
                new SegmentedVideoOptions(segmentBytes, 4 * segmentBytes, 10 * segmentBytes), directory -> {});
    }

    private void write(File file, long bytes) throws IOException {
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) { output.setLength(bytes); }
    }

    @Test public void segmentIdentityRoundTripsUnicodeAndSeparators() throws Exception {
        File first = VideoSegmentFiles.firstFile(temporary.getRoot(), "session/雪_with-dashes");
        VideoSegmentFiles.Identity identity = VideoSegmentFiles.identity(first.getParentFile().getName());
        assertThat(identity.sessionId).isEqualTo("session/雪_with-dashes");
        assertThat(identity.index).isZero();
        assertThat(VideoSegmentFiles.identity("VID_old_capture")).isNull();
        assertThat(VideoSegmentFiles.identity("VID_123_SEG_bad_-1")).isNull();
    }

    @Test public void closedFileBecomesReadyOnlyAfterVerifiedPublish() throws Exception {
        File first = VideoSegmentFiles.firstFile(temporary.getRoot(), "session");
        VideoSegmentFiles files = files(first);
        write(first, 1234);
        assertThat(GallerySyncFilter.isCaptureBlockedFromSync(first.getName(), null, Collections.emptySet())).isTrue();
        File ready = files.publishVerified(first);
        assertThat(first).doesNotExist();
        assertThat(ready).hasSize(1234);
        assertThat(files.finalizedCount()).isEqualTo(1);
        assertThat(GallerySyncFilter.isCaptureBlockedFromSync(ready.getName(), null, Collections.emptySet())).isFalse();
        // Reclamation changes backlog, but cannot replenish the user's lifetime session budget.
        assertThat(ready.delete()).isTrue();
        assertThat(files.backlogBytes()).isZero();
        assertThat(files.totalBytes(null, null)).isEqualTo(1234);
    }

    @Test public void interruptedPreviousSessionStillCountsAgainstBacklog() throws Exception {
        File previous = VideoSegmentFiles.firstFile(temporary.getRoot(), "previous");
        write(previous, 3 * segmentBytes);
        File current = VideoSegmentFiles.firstFile(temporary.getRoot(), "current");
        VideoSegmentFiles files = files(current);
        assertThat(files.backlogBytes()).isEqualTo(3 * segmentBytes);
        assertThat(files.nextFileStopReason(current, Long.MAX_VALUE)).isEqualTo("backlog_full");
        assertThat(previous).exists();
    }

    @Test public void diskAndSessionLimitsPreventQueueingAnotherFullSegment() throws Exception {
        File first = VideoSegmentFiles.firstFile(temporary.getRoot(), "session");
        VideoSegmentFiles files = new VideoSegmentFiles(first,
                new SegmentedVideoOptions(segmentBytes, 4 * segmentBytes, segmentBytes), directory -> {});
        write(first, segmentBytes / 2);
        assertThat(files.nextFileStopReason(first, segmentBytes)).isEqualTo("storage_low");
        assertThat(files.nextFileStopReason(first, Long.MAX_VALUE)).isEqualTo("session_byte_limit");
    }

    @Test public void noOutOfOrderOrUnrelatedCaptureCanBecomeReady() throws Exception {
        File first = VideoSegmentFiles.firstFile(temporary.getRoot(), "session");
        VideoSegmentFiles files = files(first);
        File second = files.nextFile(1);
        write(second, 10);
        assertThatThrownBy(() -> files.publishVerified(second)).isInstanceOf(IOException.class);
        File unrelated = VideoSegmentFiles.firstFile(temporary.getRoot(), "other");
        write(unrelated, 10);
        assertThatThrownBy(() -> files.publishVerified(unrelated)).isInstanceOf(IOException.class);
        assertThat(files.finalizedCount()).isZero();
        assertThat(second).exists();
        assertThat(unrelated).exists();
    }

    @Test public void durabilityFailureBeforeRenameLeavesPartialHidden() throws Exception {
        File first = VideoSegmentFiles.firstFile(temporary.getRoot(), "session");
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean();
        VideoSegmentFiles files = new VideoSegmentFiles(first,
                new SegmentedVideoOptions(segmentBytes, 4 * segmentBytes, 10 * segmentBytes), directory -> {
                    if (fail.get()) throw new IOException("disk failure");
                });
        write(first, 100);
        fail.set(true);
        assertThatThrownBy(() -> files.publishVerified(first)).isInstanceOf(IOException.class);
        assertThat(first).exists();
        assertThat(new File(first.getParentFile(), "base.mp4")).doesNotExist();
        assertThat(files.finalizedCount()).isZero();
    }

    @Test public void validatesBoundedByteBudgets() {
        assertThatThrownBy(() -> new SegmentedVideoOptions(0, Long.MAX_VALUE, Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SegmentedVideoOptions(segmentBytes, segmentBytes, segmentBytes))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SegmentedVideoOptions(segmentBytes, 4 * segmentBytes, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test public void finalDirectorySyncFailureRollsBackAndDoesNotCountSegment() throws Exception {
        File first = VideoSegmentFiles.firstFile(temporary.getRoot(), "session");
        File ready = new File(first.getParentFile(), "base.mp4");
        VideoSegmentFiles files = new VideoSegmentFiles(first,
                new SegmentedVideoOptions(segmentBytes, 4 * segmentBytes, 10 * segmentBytes), directory -> {
                    if (ready.exists()) {
                        assertThat(VideoSegmentFiles.isPublicationPending(directory.getName())).isTrue();
                        throw new IOException("final directory sync failed");
                    }
                });
        write(first, 100);
        assertThatThrownBy(() -> files.publishVerified(first)).isInstanceOf(IOException.class);
        assertThat(first).exists();
        assertThat(ready).doesNotExist();
        assertThat(files.finalizedCount()).isZero();
    }
}
