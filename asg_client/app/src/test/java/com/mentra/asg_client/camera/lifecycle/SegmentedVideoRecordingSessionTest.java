package com.mentra.asg_client.camera.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import com.mentra.asg_client.sensors.ImuRecorder;
import com.mentra.asg_client.settings.SegmentedVideoOptions;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Camera-independent event ordering tests; device acceptance still verifies HAL rollover behavior. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SegmentedVideoRecordingSessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final AtomicReference<String> terminal = new AtomicReference<>();
    private final AtomicInteger terminated = new AtomicInteger();
    private final MediaRecorder recorder = mock(MediaRecorder.class);

    private VideoRecordingSession recording(File first, File next, boolean valid) throws Exception {
        VideoSegmentFiles files = new VideoSegmentFiles(first,
                new SegmentedVideoOptions(8L << 20, 32L << 20, 100L << 20), directory -> {});
        VideoRecordingSession session = new VideoRecordingSession(null, new Handler(Looper.getMainLooper()),
                Runnable::run, new VideoRecordingSession.Hooks() {
                    public ImuRecorder ensureImuRecorder() { throw new AssertionError("No segmented IMU"); }
                    public ImuRecorder currentImuRecorder() { return null; }
                    public int videoOrientation() { return 90; }
                    public void onSessionTerminated() { terminated.incrementAndGet(); }
                }, path -> valid);
        session.setCallback(new VideoRecordingSession.Callback() {
            public void onRecordingStarted(String id) {}
            public void onRecordingProgress(String id, long duration) {}
            public void onRecordingStopped(String id, String path) { throw new AssertionError("Legacy stop callback"); }
            public void onRecordingError(String id, String message) { throw new AssertionError(message); }
            public void onSegmentedRecordingStopped(String id, int count, String reason, boolean success) {
                terminal.set(id + ":" + count + ":" + reason + ":" + success);
            }
        });
        session.prepareRequest("session", first.getAbsolutePath(), null);
        ReflectionHelpers.setField(session, "segments", files);
        ReflectionHelpers.setField(session, "nextSegmentFile", next);
        ReflectionHelpers.setField(session, "mediaRecorder", recorder);
        ReflectionHelpers.setField(session, "isRecording", true);
        return session;
    }

    @Test public void rolloverPublishesClosedSegmentWithoutStoppingEncoder() throws Exception {
        File first = VideoSegmentFiles.firstFile(temporary.getRoot(), "session");
        VideoSegmentFiles files = new VideoSegmentFiles(first,
                new SegmentedVideoOptions(8L << 20, 32L << 20, 100L << 20), directory -> {});
        File next = files.nextFile(1);
        Files.write(first.toPath(), new byte[] {1});
        Files.write(next.toPath(), new byte[] {2});
        VideoRecordingSession session = recording(first, next, true);
        ReflectionHelpers.callInstanceMethod(session, "onSegmentedRecorderInfo",
                ClassParameter.from(MediaRecorder.class, recorder),
                ClassParameter.from(int.class, MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED));
        assertThat(new File(first.getParentFile(), "base.mp4")).exists();
        assertThat(next).exists();
        assertThat(session.currentVideoPath()).isEqualTo(next.getAbsolutePath());
        assertThat(terminal.get()).isNull();
        verify(recorder, never()).stop();
        verify(recorder, never()).reset();
        session.stopRecording("session", "user_requested");
        assertThat(terminal.get()).isEqualTo("session:2:user_requested:true");
        assertThat(new File(next.getParentFile(), "base.mp4")).exists();
        assertThat(terminated.get()).isEqualTo(1);
    }

    @Test public void stopBeforeQueuedRolloverCallbackStillPublishesBothClosedFiles() throws Exception {
        File first = VideoSegmentFiles.firstFile(temporary.getRoot(), "session");
        VideoSegmentFiles files = new VideoSegmentFiles(first,
                new SegmentedVideoOptions(8L << 20, 32L << 20, 100L << 20), directory -> {});
        File next = files.nextFile(1);
        Files.write(first.toPath(), new byte[] {1});
        Files.write(next.toPath(), new byte[] {2});
        VideoRecordingSession session = recording(first, next, true);
        session.stopRecording("session", "max_duration");
        assertThat(terminal.get()).isEqualTo("session:2:max_duration:true");
        assertThat(first).doesNotExist();
        assertThat(next).doesNotExist();
    }

    @Test public void failedValidationRetainsFootageAndNeverReportsSuccess() throws Exception {
        File first = VideoSegmentFiles.firstFile(temporary.getRoot(), "session");
        Files.write(first.toPath(), new byte[] {1});
        VideoRecordingSession session = recording(first, null, false);
        session.stopRecording("session", "backlog_full");
        assertThat(terminal.get()).isEqualTo("session:0:finalization_error:false");
        assertThat(first).exists();
        assertThat(new File(first.getParentFile(), "base.mp4")).doesNotExist();
        assertThat(terminated.get()).isEqualTo(1);
    }
}
