package com.mentra.asg_client.settings;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import com.mentra.asg_client.AsgConstants;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RecordingProfileTest {
    private Context context;
    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("asg_settings", 0).edit().clear().commit();
    }
    private RecordingProfile profile(String revision, int duration, String mode) {
        return new RecordingProfile(revision, 1280, 720, 15, duration, mode, 64L << 20, 256L << 20);
    }
    @Test public void fullProfilePersistsAsOneSnapshotAcrossRestart() throws Exception {
        AsgSettings settings = new AsgSettings(context);
        RecordingProfile expected = profile("profile-1", 120, "phone");
        assertTrue(settings.setRecordingProfile(expected));
        AsgSettings reopened = new AsgSettings(context);
        assertTrue(expected.sameValues(reopened.getRecordingProfile()));
        assertEquals(120, reopened.getButtonMaxRecordingTimeMinutes());
        assertEquals(15, reopened.getButtonVideoSettings().fps);
        assertTrue(reopened.getRecordingProfile().offloadsToPhone());
    }
    @Test public void allSharedDurationsSurviveLegacyGettersAndUpdates() {
        AsgSettings settings = new AsgSettings(context);
        for (int duration : new int[] {0,3,5,10,15,20,30,60,120}) {
            assertTrue(settings.setRecordingProfile(profile("revision-" + duration, duration, "local")));
            assertEquals(duration, settings.getButtonMaxRecordingTimeMinutes());
        }
        settings.setButtonMaxRecordingTimeMinutes(0);
        assertEquals(0, new AsgSettings(context).getRecordingProfile().maxRecordingTimeMinutes);
    }
    @Test public void failedCommitCannotPublishNewProfileOrMixedLegacyFields() {
        SharedPreferences real = context.getSharedPreferences("asg_settings", 0);
        SharedPreferences wrapped = spy(real);
        Context fakeContext = mock(Context.class);
        when(fakeContext.getSharedPreferences("asg_settings", 0)).thenReturn(wrapped);
        AsgSettings settings = new AsgSettings(fakeContext);
        SharedPreferences.Editor failing = mock(SharedPreferences.Editor.class, RETURNS_SELF);
        doAnswer(invocation -> { real.edit().putString(AsgConstants.RECORDING_PROFILE_KEY, "bad")
                .putInt("button_video_width", 1280).putInt("button_max_recording_time_minutes", 0).commit(); return false; })
                .when(failing).commit();
        when(wrapped.edit()).thenReturn(failing, real.edit());
        assertFalse(settings.setRecordingProfile(profile("failed", 0, "phone")));
        assertNull(settings.getRecordingProfile());
        assertEquals(1920, settings.getButtonVideoSettings().width);
        assertEquals(10, settings.getButtonMaxRecordingTimeMinutes());
        assertNull(new AsgSettings(context).getRecordingProfile());
    }
    @Test public void profileRevisionCannotBeReusedForDifferentSettings() {
        AsgSettings settings = new AsgSettings(context);
        assertTrue(settings.setRecordingProfile(profile("same", 30, "local")));
        assertTrue(settings.setRecordingProfile(profile("same", 30, "local")));
        assertThrows(IllegalArgumentException.class, () -> settings.setRecordingProfile(profile("same", 60, "phone")));
        assertEquals(30, settings.getRecordingProfile().maxRecordingTimeMinutes);
    }
    @Test public void corruptedProfileNeverFallsBackToLocalCapture() {
        context.getSharedPreferences("asg_settings", 0).edit().putString(AsgConstants.RECORDING_PROFILE_KEY, "bad").commit();
        AsgSettings settings = new AsgSettings(context);
        assertThrows(IllegalStateException.class, settings::getRecordingProfile);
        assertThrows(IllegalStateException.class, settings::getButtonVideoSettings);
        assertTrue(settings.setRecordingProfile(profile("repaired", 30, "phone")));
        assertTrue(settings.getRecordingProfile().offloadsToPhone());
    }
    @Test public void rejectsCoercedNumericFieldsAndUnsupportedCameraChoices() throws Exception {
        JSONObject data = profile("x", 30, "phone").toJson();
        assertThrows(IllegalArgumentException.class, () -> RecordingProfile.fromJson(data.put("fps", 15.5)));
        assertThrows(IllegalArgumentException.class, () -> RecordingProfile.fromJson(data.put("fps", "15")));
        assertThrows(IllegalArgumentException.class, () -> new RecordingProfile("x",1920,1080,24,30,"phone",64L<<20,256L<<20));
    }
    @Test public void consumedIdentitySurvivesRestartAndRejectsEitherReusedId() {
        AsgSettings settings = new AsgSettings(context);
        assertTrue(settings.claimRecordingGrant("grant", "session"));
        AsgSettings reopened = new AsgSettings(context);
        assertTrue(reopened.isRecordingGrantConsumed("grant", "other"));
        assertFalse(reopened.claimRecordingGrant("other", "session"));
    }
}
