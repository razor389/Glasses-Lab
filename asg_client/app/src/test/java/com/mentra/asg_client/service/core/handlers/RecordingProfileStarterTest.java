package com.mentra.asg_client.service.core.handlers;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import android.content.Context;
import com.mentra.asg_client.AsgConstants;
import com.mentra.asg_client.io.media.core.MediaCaptureService;
import com.mentra.asg_client.io.server.services.AsgCameraServer;
import com.mentra.asg_client.io.server.services.RecordingGrant;
import com.mentra.asg_client.service.legacy.managers.AsgClientServiceManager;
import com.mentra.asg_client.settings.AsgSettings;
import com.mentra.asg_client.settings.RecordingProfile;
import com.mentra.asg_client.settings.VideoSettings;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RecordingProfileStarterTest {
    private AsgClientServiceManager manager;
    private AsgSettings settings;
    private MediaCaptureService capture;
    private AsgCameraServer server;
    @Before public void setup() {
        manager = mock(AsgClientServiceManager.class);
        settings = mock(AsgSettings.class);
        capture = mock(MediaCaptureService.class);
        server = mock(AsgCameraServer.class);
        Context context = mock(Context.class);
        when(context.getPackageName()).thenReturn(AsgConstants.PERSONAL_ASG_PACKAGE);
        when(manager.getContext()).thenReturn(context);
        when(manager.getAsgSettings()).thenReturn(settings);
        when(manager.getMediaCaptureService()).thenReturn(capture);
        when(manager.getCameraServer()).thenReturn(server);
    }
    private RecordingProfile profile(String mode) {
        return new RecordingProfile("revision",1280,720,15,60,mode,64L<<20,256L<<20);
    }
    @Test public void appAndButtonUseIdenticalCanonicalLocalChoices() {
        when(settings.getRecordingProfile()).thenReturn(profile("local"));
        assertEquals("app-id", RecordingProfileStarter.start(manager,"app-id","revision","app",true));
        String buttonId = RecordingProfileStarter.start(manager,null,null,"button",true);
        assertTrue(buttonId.startsWith("button_"));
        ArgumentCaptor<VideoSettings> videos = ArgumentCaptor.forClass(VideoSettings.class);
        verify(capture,times(2)).handleStartVideoCommand(anyString(),eq(true),videos.capture(),eq(true),eq(true),eq(60));
        assertTrue(videos.getAllValues().get(0).recordingProfile.sameValues(videos.getAllValues().get(1).recordingProfile));
        assertEquals("button", videos.getAllValues().get(1).recordingOrigin);
        verifyNoInteractions(server);
    }
    @Test public void buttonUsesPhoneReservedIdentityAndImmutableAllowance() {
        when(settings.getRecordingProfile()).thenReturn(profile("phone"));
        when(server.consumeRecordingGrant("revision", null)).thenReturn(new RecordingGrant("revision","grant","phone-session",512L<<20));
        when(settings.claimRecordingGrant("grant","phone-session")).thenReturn(true);
        assertEquals("phone-session", RecordingProfileStarter.start(manager,null,null,"button",true));
        ArgumentCaptor<VideoSettings> video = ArgumentCaptor.forClass(VideoSettings.class);
        verify(capture).handleStartVideoCommand(eq("phone-session"),eq(true),video.capture(),eq(true),eq(true),eq(60));
        assertEquals(512L<<20, video.getValue().segmented.maxSessionBytes);
        assertEquals("grant", video.getValue().recordingGrantId);
    }
    @Test public void unavailablePhoneNeverFallsBackToLocal() {
        when(settings.getRecordingProfile()).thenReturn(profile("phone"));
        when(server.consumeRecordingGrant("revision",null)).thenThrow(new IllegalStateException("phone_not_ready"));
        assertThrows(IllegalStateException.class, () -> RecordingProfileStarter.start(manager,null,null,"button",true));
        verify(capture,never()).handleStartVideoCommand(anyString(),anyBoolean(),any(),anyBoolean(),anyBoolean(),anyInt());
    }
    @Test public void busyAndStaleProfileDeclineBeforeConsumingGrant() {
        when(settings.getRecordingProfile()).thenReturn(profile("phone"));
        assertThrows(IllegalStateException.class, () -> RecordingProfileStarter.start(manager,"id","old","app",true));
        when(capture.isVideoRecordingActiveOrStarting()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> RecordingProfileStarter.start(manager,"id","revision","app",true));
        verifyNoInteractions(server);
    }
    @Test public void consumedGrantPersistenceFailureProducesTerminalZero() {
        when(settings.getRecordingProfile()).thenReturn(profile("phone"));
        when(server.consumeRecordingGrant("revision",null)).thenReturn(new RecordingGrant("revision","grant","session",512L<<20));
        RecordingProfileStarter.start(manager,null,null,"button",true);
        verify(capture).finishProfileRecording(eq("session"),any(),eq(0),eq("grant_persistence_error"),eq(false));
        verify(capture,never()).handleStartVideoCommand(anyString(),anyBoolean(),any(),anyBoolean(),anyBoolean(),anyInt());
    }

    @Test public void bothOriginsUseDisabledExternalFlashFromSavedProfile() {
        when(settings.getRecordingProfile()).thenReturn(
                new RecordingProfile("revision",1280,720,15,60,"local",64L<<20,256L<<20,false));
        RecordingProfileStarter.start(manager,"app-id","revision","app",true);
        RecordingProfileStarter.start(manager,null,null,"button",true);
        verify(capture,times(2)).handleStartVideoCommand(anyString(),eq(true),any(),eq(false),eq(true),eq(60));
    }
}
