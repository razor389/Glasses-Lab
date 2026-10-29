package com.mentra.asg_client.camera.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import android.util.Size;
import com.mentra.asg_client.camera.lifecycle.CameraOpener;
import com.mentra.asg_client.camera.model.ActivePhotoCapture;
import com.mentra.asg_client.camera.model.PhotoCaptureSettings;
import com.mentra.asg_client.camera.model.QueuedPhotoRequest;
import com.mentra.asg_client.settings.AsgSettings;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Wire parsing, merge and queue coverage for opt-in archival quality. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class PhotoQualityProfileTest {
    @Test
    public void missingProfileRetainsOriginPolicy() {
        assertThat(PhotoQualityProfile.fromJson(new JSONObject())).isEqualTo(PhotoQualityProfile.DEFAULT);
        assertThat(PhotoQualityProfile.DEFAULT.usesSdkQuality(true)).isTrue();
        assertThat(PhotoQualityProfile.DEFAULT.usesSdkQuality(false)).isFalse();
    }

    @Test
    public void invalidValuesAndRequestShapesAreRejected() throws Exception {
        for (Object value : new Object[] {"unknown", "", 1, true, JSONObject.NULL}) {
            JSONObject data = new JSONObject().put("qualityProfile", value);
            assertThatThrownBy(() -> PhotoQualityProfile.validateRequest(data))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (JSONObject data : new JSONObject[] {
                local().put("save", false), local().put("save", "true"),
                local().put("mode", "text"), local().put("mode", "unknown"),
                local().put("webhookUrl", "https://example.com/photo")}) {
            assertThatThrownBy(() -> PhotoQualityProfile.validateRequest(data))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        PhotoQualityProfile.validateRequest(local());
    }

    @Test
    public void localSurvivesMergeQueueAndActivationWithoutChangingOrigin() throws Exception {
        PhotoCaptureSettings parsed = PhotoCaptureSettings.fromTakePhotoJson(local());
        AsgSettings stored = new AsgSettings(RuntimeEnvironment.getApplication());
        PhotoCaptureSettings merged = PhotoCaptureSettings.mergeForSdkRequest(parsed, stored);
        QueuedPhotoRequest queued = new QueuedPhotoRequest(
                "/tmp/local.jpg", "high", true, true, null, null, merged, null);
        ActivePhotoCapture active = ActivePhotoCapture.fromQueued(queued);
        assertThat(active.isFromSdk).isTrue();
        assertThat(active.captureSettings.qualityProfile).isEqualTo(PhotoQualityProfile.LOCAL);
        assertThat(active.captureSettings.qualityProfile.usesSdkQuality(active.isFromSdk)).isFalse();
        assertThat(PhotoCaptureSettings.mergeWithStoredDefaults(parsed, stored).qualityProfile)
                .isEqualTo(PhotoQualityProfile.LOCAL);
    }

    @Test
    public void eachLocalTierResolvesTheSameOutputAsButtonTier() {
        Size[] outputs = {new Size(640, 480), new Size(960, 720), new Size(1280, 960),
                new Size(1440, 1088), new Size(1920, 1440), new Size(3264, 2448)};
        for (String tier : new String[] {"low", "medium", "high", "max"}) {
            Size button = CameraOpener.resolveJpegSize(outputs, false, tier);
            Size phone = CameraOpener.resolveJpegSize(outputs,
                    PhotoQualityProfile.LOCAL.usesSdkQuality(true), tier);
            assertThat(phone).isEqualTo(button);
        }
        assertThat(CameraOpener.resolveJpegSize(outputs, true, "high"))
                .isEqualTo(new Size(1920, 1440));
        assertThat(CameraOpener.resolveJpegSize(outputs, false, "high"))
                .isEqualTo(new Size(3264, 2448));
    }

    private static JSONObject local() throws Exception {
        return new JSONObject().put("qualityProfile", "local").put("save", true)
                .put("mode", "photo");
    }
}
