package com.mentra.asg_client.io.media.core;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.content.Context;
import android.os.Handler;
import androidx.test.core.app.ApplicationProvider;
import com.mentra.asg_client.AsgConstants;
import com.mentra.asg_client.camera.CameraNeoService;
import com.mentra.asg_client.io.hardware.interfaces.IHardwareManager;
import com.mentra.asg_client.io.media.interfaces.ServiceCallbackInterface;
import com.mentra.asg_client.settings.RecordingProfile;
import com.mentra.asg_client.settings.SegmentedVideoOptions;
import com.mentra.asg_client.settings.VideoSettings;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Real lifecycle/status methods with hardware and Android service dispatch replaced at their boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RecordingProfileStatusTest {
    private MediaCaptureService capture;
    private Context context;
    private IHardwareManager hardware;
    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(AsgConstants.VIDEO_SEGMENT_STATUS_PREFS,0).edit().clear().commit();
        capture = mock(MediaCaptureService.class, CALLS_REAL_METHODS);
        hardware = mock(IHardwareManager.class);
        ReflectionHelpers.setField(capture,"mContext",context);
        ReflectionHelpers.setField(capture,"hardwareManager",hardware);
        ReflectionHelpers.setField(capture,"mainHandler",mock(Handler.class));
        ReflectionHelpers.setField(capture,"mStopLock",new Object());
        ReflectionHelpers.setField(capture,"uploadTargetsByCaptureId",new ConcurrentHashMap<>());
        capture.setServiceCallback(mock(ServiceCallbackInterface.class));
    }
    private VideoSettings video() {
        RecordingProfile profile = new RecordingProfile("revision",1280,720,15,0,"phone",64L<<20,256L<<20);
        return new VideoSettings(1280,720,15,new SegmentedVideoOptions(64L<<20,256L<<20,512L<<20),profile,"button","grant");
    }
    @Test public void consumedSetupFailureIsDurableAndRecoverableWithoutKnownId() throws Exception {
        capture.finishProfileRecording("session",video(),0,"start_error",false);
        JSONObject terminal = capture.getRecordingProfileStatus(null);
        assertEquals("session", terminal.getString("sessionId"));
        assertEquals("grant", terminal.getString("grantId"));
        assertEquals("button", terminal.getString("origin"));
        assertEquals(0, terminal.getInt("segmentCount"));
        assertFalse(terminal.getBoolean("success"));
        assertEquals("revision", terminal.getJSONObject("profile").getString("revision"));
        assertNull(capture.getRecordingProfileStatus("another-session"));
        assertFalse(capture.isVideoRecordingActiveOrStarting());
        assertEquals("start_error", capture.getSegmentedTerminalStatus("session").getString("stopReason"));
    }
    @Test public void stopDispatchFailureRetainsLiveIdentityAndCanRetryWithoutFalseTerminal() throws Exception {
        JSONObject active = new JSONObject().put("sessionId","session").put("requestId","session")
                .put("recording",true).put("status","recording_started").put("success",true).put("profile",video().recordingProfile.toJson());
        ReflectionHelpers.setField(capture,"activeProfileStatus",active);
        ReflectionHelpers.setField(capture,"isRecordingVideo",true);
        ReflectionHelpers.setField(capture,"currentVideoId","session");
        ReflectionHelpers.setField(capture,"currentVideoPath","/tmp/VID_local/base.mp4");
        ReflectionHelpers.setField(capture,"currentVideoLedEnabled",false);
        when(hardware.supportsRgbLed()).thenReturn(true);
        when(hardware.supportsRecordingLed()).thenReturn(true);
        try (MockedStatic<CameraNeoService> camera = mockStatic(CameraNeoService.class)) {
            camera.when(() -> CameraNeoService.stopVideoRecording(context,"session"))
                    .thenThrow(new IllegalStateException("service dispatch failed"));
            capture.stopVideoRecording();
            capture.handleStopVideoCommand("session",null,null);
            camera.verify(() -> CameraNeoService.stopVideoRecording(context,"session"),times(2));
        }
        JSONObject status = capture.getRecordingProfileStatus(null);
        assertTrue(capture.isRecordingVideo());
        assertTrue(capture.isVideoRecordingActiveOrStarting());
        assertTrue(status.getBoolean("recording"));
        assertFalse(status.getBoolean("success"));
        assertEquals("recording_status",status.getString("status"));
        assertEquals("stop_dispatch_failed",status.getString("error"));
        assertFalse(status.has("segmentCount"));
        assertNull(capture.getSegmentedTerminalStatus("session"));
        verify(hardware,never()).setRecordingLedOff();
        verify(hardware,never()).setRecordingLedOn();
        verify(hardware,times(2)).setRgbLedSolidWhite(eq(1800000),anyInt());
    }
}
