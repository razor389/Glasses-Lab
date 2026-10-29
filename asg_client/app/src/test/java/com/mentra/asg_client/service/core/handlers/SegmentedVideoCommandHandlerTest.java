package com.mentra.asg_client.service.core.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import com.mentra.asg_client.AsgConstants;
import com.mentra.asg_client.io.media.core.MediaCaptureService;
import com.mentra.asg_client.service.legacy.managers.AsgClientServiceManager;
import com.mentra.asg_client.service.media.interfaces.IMediaManager;
import com.mentra.asg_client.service.system.interfaces.IStateManager;
import com.mentra.asg_client.settings.VideoSettings;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** New opt-in requests cannot silently fall back to an unlimited legacy recording. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SegmentedVideoCommandHandlerTest {
    private Context context;
    private MediaCaptureService capture;
    private VideoCommandHandler handler;

    @Before public void setup() {
        context = mock(Context.class);
        when(context.getPackageName()).thenReturn(AsgConstants.PERSONAL_ASG_PACKAGE);
        AsgClientServiceManager manager = mock(AsgClientServiceManager.class);
        capture = mock(MediaCaptureService.class);
        when(manager.getMediaCaptureService()).thenReturn(capture);
        IStateManager state = mock(IStateManager.class);
        when(state.getBatteryLevel()).thenReturn(-1);
        handler = new VideoCommandHandler(context, manager, mock(IMediaManager.class), null, state);
    }

    private JSONObject request() throws Exception {
        return new JSONObject().put("requestId", "session-id").put("packageName", "personal")
                .put("segmented", true).put("save", true).put("maxSessionBytes", 5L * 1024 * 1024 * 1024);
    }

    private void assertNeverStarts() {
        verify(capture, never()).handleStartVideoCommand(anyString(), anyBoolean(), any(),
                anyBoolean(), anyBoolean(), anyInt());
    }

    @Test public void forwardsLongBudgetsAndSessionTimerWithExplicitOptIn() throws Exception {
        JSONObject request = request().put("maxRecordingTimeMinutes", 0);
        assertThat(handler.handleCommand("start_video_recording", request)).isTrue();
        ArgumentCaptor<VideoSettings> settings = ArgumentCaptor.forClass(VideoSettings.class);
        verify(capture).handleStartVideoCommand(eq("session-id"), eq(true), settings.capture(),
                eq(true), eq(true), eq(0));
        assertThat(settings.getValue().segmented.segmentMaxBytes).isEqualTo(64L * 1024 * 1024);
        assertThat(settings.getValue().segmented.maxBacklogBytes).isEqualTo(256L * 1024 * 1024);
        assertThat(settings.getValue().segmented.maxSessionBytes).isEqualTo(5L * 1024 * 1024 * 1024);
    }

    @Test public void rejectsStockPackage() throws Exception {
        when(context.getPackageName()).thenReturn(AsgConstants.STOCK_ASG_PACKAGE);
        assertThat(handler.handleCommand("start_video_recording", request())).isFalse();
        assertNeverStarts();
    }

    @Test public void requiresFiniteSessionAllowanceAndLocalRetention() throws Exception {
        JSONObject request = request();
        request.remove("maxSessionBytes");
        assertThat(handler.handleCommand("start_video_recording", request)).isFalse();
        assertThat(handler.handleCommand("start_video_recording", request().put("save", false))).isFalse();
        assertNeverStarts();
    }

    @Test public void rejectsUnsafeBacklogAndOversizedIdentity() throws Exception {
        assertThat(handler.handleCommand("start_video_recording", request().put("maxBacklogBytes", 1))).isFalse();
        assertThat(handler.handleCommand("start_video_recording", request().put("requestId", "x".repeat(129)))).isFalse();
        assertNeverStarts();
    }

    @Test public void refusesReuseOfCompletedSessionIdentity() throws Exception {
        when(capture.getSegmentedTerminalStatus("session-id")).thenReturn(new JSONObject());
        assertThat(handler.handleCommand("start_video_recording", request())).isFalse();
        assertNeverStarts();
    }
}
