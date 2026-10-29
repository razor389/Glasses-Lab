package com.mentra.asg_client.io.server.services;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.app.Application;
import com.mentra.asg_client.AsgConstants;
import com.mentra.asg_client.camera.lifecycle.PhotoPersistenceRegistry;
import com.mentra.asg_client.camera.lifecycle.VideoSegmentFiles;
import com.mentra.asg_client.settings.SegmentedVideoOptions;
import com.mentra.asg_client.settings.RecordingProfile;
import com.mentra.asg_client.io.bluetooth.managers.mentralive.internal.LinkStateMachine;
import com.mentra.asg_client.io.file.core.FileManager;
import com.mentra.asg_client.io.server.interfaces.*;
import com.mentra.asg_client.logging.Logger;
import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 30)
public class LabGalleryApiTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private MockedStatic<AndroidDirectorySync> directorySync;

    @Before public void setupDirectorySync() { directorySync = mockStatic(AndroidDirectorySync.class); }
    @After public void closeDirectorySync() { directorySync.close(); }

    @Test public void stockBuildDoesNotAdvertiseOrExposePermanentReclamation() throws Exception {
        AsgCameraServer server = server(AsgConstants.STOCK_ASG_PACKAGE, temporary.newFolder());
        assertFalse(json(server.handleRequest(request("/api/v3/capabilities", null, null)))
                .getJSONObject("data").has("lab_sync_version"));
        assertFalse(json(server.handleRequest(request("/api/v3/capabilities", null, null)))
                .getJSONObject("data").has("segmented_recording_version"));
        assertFalse(json(server.handleRequest(request("/api/v3/capabilities", null, null)))
                .getJSONObject("data").has("lab_sync_idle_lease_max_seconds"));
        server.configureLabSync("a".repeat(64), () -> true, () -> "ssid:password");
        assertEquals(404, server.handleRequest(request("/api/lab/v1/manifest", "a".repeat(64), null)).getStatus().getRequestStatus());
        assertEquals(404, server.handleRequest(postJson("/api/lab/v1/lease", "a".repeat(64),
                new JSONObject().put("ttl_seconds", 180))).getStatus().getRequestStatus());
        assertEquals(404, server.handleRequest(postJson("/api/lab/v1/recording-ready", "a".repeat(64),
                recordingGrant("revision-1", "grant-1", "session-1", 90))).getStatus().getRequestStatus());
    }

    @Test public void forkRequiresValidBleProvisionedAuthorityForEveryLabEndpoint() throws Exception {
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, temporary.newFolder());
        assertEquals(1, json(server.handleRequest(request("/api/v3/capabilities", null, null)))
                .getJSONObject("data").getInt("lab_sync_version"));
        for (String path : new String[] {"manifest", "hash", "file", "receipt", "lease", "recording-ready"}) {
            assertEquals(401, server.handleRequest(request("/api/lab/v1/" + path, null, null)).getStatus().getRequestStatus());
        }
        server.configureLabSync("a".repeat(64), () -> true, () -> "ssid:password");
        assertEquals(401, server.handleRequest(request("/api/lab/v1/manifest", "b".repeat(64), null)).getStatus().getRequestStatus());
        assertEquals(200, server.handleRequest(request("/api/lab/v1/manifest", "a".repeat(64), null)).getStatus().getRequestStatus());
    }

    @Test public void manifestAndHashExposeExactIdentityAndDownloadHonorsGeneration() throws Exception {
        File root = temporary.newFolder();
        File photo = new File(root, "IMG_1.jpg");
        Files.write(photo.toPath(), "photo".getBytes(StandardCharsets.UTF_8));
        photo.setLastModified(10_000L);
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, root);
        server.configureLabSync("a".repeat(64), () -> true, () -> "ssid:password");
        JSONObject item = json(server.handleRequest(request("/api/lab/v1/manifest", "a".repeat(64), null)))
                .getJSONObject("data").getJSONArray("files").getJSONObject(0);
        assertEquals("IMG_1.jpg", item.getString("name"));
        assertEquals("live", item.getString("location"));
        assertEquals("image/jpeg", item.getString("mime_type"));
        Map<String, String> params = new HashMap<>();
        params.put("file", "IMG_1.jpg"); params.put("location", "live");
        JSONObject hash = json(server.handleRequest(request("/api/lab/v1/hash", "a".repeat(64), params))).getJSONObject("data");
        assertEquals(item.getString("generation"), hash.getString("generation"));
        assertEquals(GalleryTransferProtocol.sha256(photo), hash.getString("sha256"));
        NanoHTTPD.IHTTPSession download = request("/api/lab/v1/file", "a".repeat(64), params);
        download.getHeaders().put("if-match", "wrong-generation");
        assertEquals(412, server.handleRequest(download).getStatus().getRequestStatus());
    }

    @Test public void completedSegmentsExposeMembershipWhileActiveAndInterruptedPartialsStayHidden() throws Exception {
        File root = temporary.newFolder();
        File first = VideoSegmentFiles.firstFile(root, "session/雪");
        VideoSegmentFiles segments = new VideoSegmentFiles(first,
                new SegmentedVideoOptions(8L << 20, 32L << 20, 100L << 20), directory -> {});
        Files.write(first.toPath(), "closed video".getBytes(StandardCharsets.UTF_8));
        File next = segments.nextFile(1);
        Files.write(next.toPath(), "still recording".getBytes(StandardCharsets.UTF_8));
        first.setLastModified(10_000L);
        next.setLastModified(10_000L);
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, root);
        String token = "a".repeat(64);
        server.configureLabSync(token, () -> true, () -> "ssid:password");
        assertEquals(0, json(server.handleRequest(request("/api/lab/v1/manifest", token, null)))
                .getJSONObject("data").getJSONArray("files").length());
        File ready = segments.publishVerified(first);
        ready.setLastModified(10_000L);
        JSONObject data = json(server.handleRequest(request("/api/lab/v1/manifest", token, null))).getJSONObject("data");
        assertEquals(1, data.getInt("segmented_recording_version"));
        assertEquals(1, data.getJSONArray("files").length());
        JSONObject item = data.getJSONArray("files").getJSONObject(0);
        assertEquals("session/雪", item.getString("session_id"));
        assertEquals("session/雪", item.getString("request_id"));
        assertEquals(0, item.getInt("segment_index"));
        assertEquals("video/mp4", item.getString("mime_type"));
        assertTrue(next.isFile());
    }

    @Test public void photoWriteAndInterruptedMarkerBlockLocalManifestUntilFullyFinalized() throws Exception {
        File root = temporary.newFolder();
        File photo = new File(root, "IMG_1.jpg");
        Files.write(photo.toPath(), "photo".getBytes(StandardCharsets.UTF_8));
        photo.setLastModified(10_000L);
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, root);
        server.configureLabSync("a".repeat(64), () -> true, () -> "ssid:password");
        try (PhotoPersistenceRegistry.Lease lease = PhotoPersistenceRegistry.begin(photo.getAbsolutePath())) {
            assertEquals(0, json(server.handleRequest(request("/api/lab/v1/manifest", "a".repeat(64), null)))
                    .getJSONObject("data").getJSONArray("files").length());
            lease.complete();
        }
        assertEquals(1, json(server.handleRequest(request("/api/lab/v1/manifest", "a".repeat(64), null)))
                .getJSONObject("data").getJSONArray("files").length());
        PhotoPersistenceRegistry.begin(photo.getAbsolutePath()).close();
        assertFalse(PhotoPersistenceRegistry.isCaptureActive("IMG_1.jpg"));
        assertEquals(0, json(server.handleRequest(request("/api/lab/v1/manifest", "a".repeat(64), null)))
                .getJSONObject("data").getJSONArray("files").length());
    }

    @Test public void generatedVideoThumbnailIsLabeledAndFullGalleryTotalsRepeatAcrossPages() throws Exception {
        File root = temporary.newFolder();
        write(root, "IMG_1/base.jpg", "photo");
        write(root, "VID_2/base.mp4", "video");
        File thumbnail = write(root, "VID_2/thumb.jpg", "preview");
        File liveImu = write(root, "IMG_1/imu.json", "full imu samples");
        File trashImu = write(root, "_gallery_trash/IMG_3/imu.json", "retained trash samples");
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, root);
        String token = "a".repeat(64);
        server.configureLabSync(token, () -> true, () -> "ssid:password");
        Map<String, String> params = new HashMap<>();
        params.put("limit", "1");
        boolean sawThumbnail = false;
        int pages = 0;
        while (true) {
            JSONObject data = json(server.handleRequest(request("/api/lab/v1/manifest", token, params))).getJSONObject("data");
            assertEquals(3, data.getInt("retained_auxiliary_files"));
            assertEquals(thumbnail.length() + liveImu.length() + trashImu.length(), data.getLong("retained_auxiliary_bytes"));
            assertEquals(1, data.getJSONArray("files").length());
            JSONObject entry = data.getJSONArray("files").getJSONObject(0);
            if (entry.getString("name").equals("VID_2/thumb.jpg")) {
                sawThumbnail = true;
                assertEquals("thumbnail", entry.getString("role"));
            } else assertEquals("primary", entry.getString("role"));
            assertTrue("Pagination must finish", ++pages <= 3);
            if (!data.getBoolean("has_more")) break;
            params.put("cursor", data.getString("next_cursor"));
        }
        assertTrue(sawThumbnail);
        assertEquals(3, pages);
        assertTrue(thumbnail.isFile());
        assertTrue(liveImu.isFile());
        assertTrue(trashImu.isFile());
    }

    @Test public void retainedSidecarsRemainVisibleWhenNoTransferableMediaRemain() throws Exception {
        File root = temporary.newFolder();
        File sidecar = write(root, "IMG_1/imu.json", "retained full sensor samples");
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, root);
        String token = "a".repeat(64);
        server.configureLabSync(token, () -> true, () -> "ssid:password");

        JSONObject data = json(server.handleRequest(request("/api/lab/v1/manifest", token, null))).getJSONObject("data");

        assertEquals(0, data.getJSONArray("files").length());
        assertEquals(1, data.getInt("retained_auxiliary_files"));
        assertEquals(sidecar.length(), data.getLong("retained_auxiliary_bytes"));
        assertTrue(sidecar.isFile());
    }

    @Test public void authenticatedLeaseIsBoundedRevocableAndDoesNotCountAsTransferActivity() throws Exception {
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, temporary.newFolder());
        String token = "a".repeat(64);
        server.configureLabSync(token, () -> true, () -> "ssid:password");
        assertEquals(180, json(server.handleRequest(request("/api/v3/capabilities", null, null)))
                .getJSONObject("data").getInt("lab_sync_idle_lease_max_seconds"));
        AtomicInteger activity = new AtomicInteger();
        server.setHttpActivityListener(activity::incrementAndGet);

        NanoHTTPD.Response response = server.serve(postJson("/api/lab/v1/lease", token,
                new JSONObject().put("ttl_seconds", 180)));
        assertEquals(200, response.getStatus().getRequestStatus());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6));
        assertEquals(180, json(response).getJSONObject("data").getInt("expires_in_seconds"));
        assertEquals(0, activity.get());
        assertTrue(server.hasLabSyncIdleLease());
        for (Object invalid : new Object[] {-1, 181, Long.MAX_VALUE, 1.5, "180"}) {
            assertEquals(400, server.serve(postJson("/api/lab/v1/lease", token,
                    new JSONObject().put("ttl_seconds", invalid))).getStatus().getRequestStatus());
        }
        assertEquals(401, server.serve(postJson("/api/lab/v1/lease", "b".repeat(64),
                new JSONObject().put("ttl_seconds", 180))).getStatus().getRequestStatus());
        assertEquals(0, activity.get());
        assertEquals(405, server.handleRequest(request("/api/lab/v1/lease", token, null)).getStatus().getRequestStatus());

        assertEquals(200, server.serve(postJson("/api/lab/v1/lease", token,
                new JSONObject().put("ttl_seconds", 0))).getStatus().getRequestStatus());
        assertFalse(server.hasLabSyncIdleLease());
        assertEquals(0, activity.get());
        json(server.serve(request("/api/lab/v1/manifest", token, null)));
        assertEquals(1, activity.get());
    }

    @Test public void recordingReadyReturnsExactReservationAndRequiresAuthenticatedPost() throws Exception {
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, temporary.newFolder());
        String token = "a".repeat(64);
        server.configureLabSync(token, () -> true, () -> "ssid:password");
        server.setRecordingProfileProvider(() -> recordingProfile("revision-1", "phone"), () -> false);
        JSONObject body = recordingGrant("revision-1", "grant-1", "session-1", 90);
        assertEquals(401, server.handleRequest(postJson("/api/lab/v1/recording-ready", "b".repeat(64), body))
                .getStatus().getRequestStatus());
        assertEquals(405, server.handleRequest(request("/api/lab/v1/recording-ready", token, null))
                .getStatus().getRequestStatus());
        NanoHTTPD.Response response = server.handleRequest(postJson("/api/lab/v1/recording-ready", token, body));
        assertEquals(200, response.getStatus().getRequestStatus());
        JSONObject data = json(response).getJSONObject("data");
        for (String field : new String[] {"profileRevision", "grantId", "sessionId"}) {
            assertEquals(body.getString(field), data.getString(field));
        }
        assertEquals(100L << 20, data.getLong("maxSessionBytes"));
        assertEquals(90, data.getInt("expires_in_seconds"));
        assertFalse(data.getBoolean("consumed"));
        RecordingGrant consumed = server.consumeRecordingGrant("revision-1", null);
        assertEquals("session-1", consumed.sessionId);
        assertEquals(100L << 20, consumed.maxSessionBytes);
    }

    @Test public void recordingReadyRequiresCurrentPhoneProfileAndExactIntegerLimits() throws Exception {
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, temporary.newFolder());
        String token = "a".repeat(64);
        server.configureLabSync(token, () -> true, () -> "ssid:password");
        RecordingProfile[] profile = {recordingProfile("revision-1", "local")};
        server.setRecordingProfileProvider(() -> profile[0], () -> false);
        JSONObject body = recordingGrant("revision-1", "grant-1", "session-1", 90);
        assertConflict(server.handleRequest(postJson("/api/lab/v1/recording-ready", token, body)), "profile_mismatch");
        profile[0] = recordingProfile("revision-2", "phone");
        assertConflict(server.handleRequest(postJson("/api/lab/v1/recording-ready", token, body)), "profile_mismatch");
        profile[0] = recordingProfile("revision-1", "phone");
        for (Object invalid : new Object[] {-1, AsgConstants.RECORDING_READY_MAX_SECONDS + 1, Long.MAX_VALUE, 1.5, "90"}) {
            assertEquals(400, server.handleRequest(postJson("/api/lab/v1/recording-ready", token,
                    recordingGrant("revision-1", "grant-1", "session-1", 90).put("ttlSeconds", invalid)))
                    .getStatus().getRequestStatus());
        }
        for (Object invalid : new Object[] {-1L, 0L, (64L << 20) - 1, (100L << 20) + 0.5, "104857600"}) {
            assertEquals(400, server.handleRequest(postJson("/api/lab/v1/recording-ready", token,
                    recordingGrant("revision-1", "grant-1", "session-1", 90).put("maxSessionBytes", invalid)))
                    .getStatus().getRequestStatus());
        }
        expectRecordingState("phone_not_ready", () -> server.consumeRecordingGrant("revision-1", null));
    }

    @Test public void consumedRecordingGrantCannotBeRenewedOrRevokedAfterProfileChanges() throws Exception {
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, temporary.newFolder());
        String token = "a".repeat(64);
        server.configureLabSync(token, () -> true, () -> "ssid:password");
        RecordingProfile[] profile = {recordingProfile("revision-1", "phone")};
        boolean[] busy = {false};
        server.setRecordingProfileProvider(() -> profile[0], () -> busy[0]);
        JSONObject body = recordingGrant("revision-1", "grant-1", "session-1", 90);
        assertEquals(200, server.handleRequest(postJson("/api/lab/v1/recording-ready", token, body))
                .getStatus().getRequestStatus());
        server.consumeRecordingGrant("revision-1", "session-1");
        busy[0] = true;
        profile[0] = recordingProfile("revision-2", "local");
        for (int seconds : new int[] {90, 0}) {
            assertConflict(server.handleRequest(postJson("/api/lab/v1/recording-ready", token,
                    recordingGrant("revision-1", "grant-1", "session-1", seconds))), "grant_consumed");
        }
    }

    @Test public void unusedGrantRevokeWorksAfterProfileChangeAndNeverRevokesAnotherGrant() throws Exception {
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, temporary.newFolder());
        String token = "a".repeat(64);
        server.configureLabSync(token, () -> true, () -> "ssid:password");
        RecordingProfile[] profile = {recordingProfile("revision-old", "phone")};
        server.setRecordingProfileProvider(() -> profile[0], () -> false);
        assertEquals(200, server.handleRequest(postJson("/api/lab/v1/recording-ready", token,
                recordingGrant("revision-old", "grant-old", "session-old", 90))).getStatus().getRequestStatus());
        profile[0] = recordingProfile("revision-new", "local");
        for (int attempt = 0; attempt < 2; attempt++) {
            NanoHTTPD.Response response = server.handleRequest(postJson("/api/lab/v1/recording-ready", token,
                    recordingGrant("revision-old", "grant-old", "session-old", 0)));
            assertEquals(200, response.getStatus().getRequestStatus());
            assertEquals(0, json(response).getJSONObject("data").getInt("expires_in_seconds"));
        }
        expectRecordingState("phone_not_ready", () -> server.consumeRecordingGrant("revision-old", null));
        profile[0] = recordingProfile("revision-new", "phone");
        assertEquals(200, server.handleRequest(postJson("/api/lab/v1/recording-ready", token,
                recordingGrant("revision-new", "grant-new", "session-new", 90))).getStatus().getRequestStatus());
        assertEquals(200, server.handleRequest(postJson("/api/lab/v1/recording-ready", token,
                recordingGrant("revision-old", "grant-old", "session-old", 0))).getStatus().getRequestStatus());
        assertEquals("session-new", server.consumeRecordingGrant("revision-new", null).sessionId);
    }

    @Test public void busyAndChangedQuotaRequestsLeaveOriginalReservationIntact() throws Exception {
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, temporary.newFolder());
        String token = "a".repeat(64);
        server.configureLabSync(token, () -> true, () -> "ssid:password");
        boolean[] busy = {false};
        server.setRecordingProfileProvider(() -> recordingProfile("revision-1", "phone"), () -> busy[0]);
        assertEquals(200, server.handleRequest(postJson("/api/lab/v1/recording-ready", token,
                recordingGrant("revision-1", "grant-1", "session-1", 90))).getStatus().getRequestStatus());
        assertConflict(server.handleRequest(postJson("/api/lab/v1/recording-ready", token,
                recordingGrant("revision-1", "grant-1", "session-1", 90).put("maxSessionBytes", 101L << 20))), "grant_mismatch");
        busy[0] = true;
        assertConflict(server.handleRequest(postJson("/api/lab/v1/recording-ready", token,
                recordingGrant("revision-1", "grant-2", "session-2", 90))), "recording_busy");
        RecordingGrant original = server.consumeRecordingGrant("revision-1", "session-1");
        assertEquals("grant-1", original.grantId);
        assertEquals(100L << 20, original.maxSessionBytes);
    }

    @Test public void persistentConsumedHistoryPreventsReadinessAfterServerRestart() throws Exception {
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, temporary.newFolder());
        String token = "a".repeat(64);
        server.configureLabSync(token, () -> true, () -> "ssid:password");
        server.setRecordingProfileProvider(() -> recordingProfile("revision-new", "phone"), () -> false);
        server.setConsumedRecordingGrantProvider(grant -> "session-used".equals(grant.sessionId));
        for (int seconds : new int[] {90, 0}) {
            assertConflict(server.handleRequest(postJson("/api/lab/v1/recording-ready", token,
                    recordingGrant("revision-old", "grant-new", "session-used", seconds))), "grant_consumed");
        }
        expectRecordingState("phone_not_ready", () -> server.consumeRecordingGrant("revision-new", null));
    }

    private static RecordingProfile recordingProfile(String revision, String mode) {
        return new RecordingProfile(revision, 1280, 720, 30, 10, mode, 64L << 20, 256L << 20);
    }

    private static JSONObject recordingGrant(String revision, String grant, String session, int seconds) throws Exception {
        return new JSONObject().put("profileRevision", revision).put("grantId", grant).put("sessionId", session)
                .put("maxSessionBytes", 100L << 20).put("ttlSeconds", seconds);
    }

    private void assertConflict(NanoHTTPD.Response response, String reason) throws Exception {
        assertEquals(409, response.getStatus().getRequestStatus());
        assertTrue(json(response).toString().contains(reason));
    }

    private static void expectRecordingState(String reason, Runnable action) {
        try {
            action.run();
            fail("Expected " + reason);
        } catch (IllegalStateException expected) {
            assertEquals(reason, expected.getMessage());
        }
    }

    @Test public void realPhoneAbsenceRevokesLeaseWhileUartTransitionsDoNot() throws Exception {
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, temporary.newFolder());
        LinkStateMachine link = new LinkStateMachine();
        link.serialReady();
        link.phonePresenceReported(true);
        server.observeLabPhonePresence(link);
        String token = "a".repeat(64);
        server.configureLabSync(token, () -> true, () -> "ssid:password");
        assertEquals(200, server.handleRequest(postJson("/api/lab/v1/lease", token,
                new JSONObject().put("ttl_seconds", 180))).getStatus().getRequestStatus());

        link.serialClosed();
        assertTrue(server.hasLabSyncIdleLease());
        link.serialReady();
        assertTrue(server.hasLabSyncIdleLease());
        link.phonePresenceReported(true);
        assertTrue(server.hasLabSyncIdleLease());
        link.phonePresenceReported(false);
        assertFalse(server.hasLabSyncIdleLease());
        assertEquals(401, server.handleRequest(request("/api/lab/v1/manifest", token, null)).getStatus().getRequestStatus());
        server.stopServer();
    }

    @Test public void postReceiptRequiresExactDigestAndAcknowledgesRepeatedSubmission() throws Exception {
        File root = temporary.newFolder();
        File photo = new File(root, "IMG_1.jpg");
        Files.write(photo.toPath(), "photo".getBytes(StandardCharsets.UTF_8));
        photo.setLastModified(10_000L);
        AsgCameraServer server = server(AsgConstants.PERSONAL_ASG_PACKAGE, root);
        String token = "a".repeat(64);
        server.configureLabSync(token, () -> true, () -> "ssid:password");
        JSONObject receipt = new JSONObject();
        receipt.put("receipt_id", "phone-live-1");
        receipt.put("file", "IMG_1.jpg"); receipt.put("location", "live");
        receipt.put("size", photo.length());
        receipt.put("generation", GalleryTransferProtocol.createEtag("IMG_1.jpg", photo.length(), photo.lastModified()));
        receipt.put("sha256", "0".repeat(64));
        assertEquals(409, server.handleRequest(postReceipt(token, receipt)).getStatus().getRequestStatus());
        assertTrue(photo.isFile());
        receipt.put("sha256", GalleryTransferProtocol.sha256(photo));
        JSONObject response = json(server.handleRequest(postReceipt(token, receipt))).getJSONObject("data");
        assertEquals(5, response.getLong("reclaimed_bytes"));
        assertFalse(photo.exists());
        assertTrue(json(server.handleRequest(postReceipt(token, receipt))).getJSONObject("data").getBoolean("already_reclaimed"));
    }

    private AsgCameraServer server(String packageName, File directory) {
        Context context = mock(Context.class);
        when(context.getPackageName()).thenReturn(packageName);
        ServerConfig config = mock(ServerConfig.class);
        when(config.getContext()).thenReturn(context);
        FileManager files = mock(FileManager.class);
        when(files.getDefaultPackageName()).thenReturn("camera");
        when(files.getPackageDirectory("camera")).thenReturn(directory);
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.isAllowed(anyString())).thenReturn(true);
        AsgCameraServer server = new AsgCameraServer(config, mock(NetworkProvider.class), mock(CacheManager.class),
                limiter, mock(Logger.class), files);
        server.setActiveRecordingProvider(() -> null);
        return server;
    }

    private File write(File root, String relativePath, String content) throws Exception {
        File file = new File(root, relativePath);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(10_000L));
        return file;
    }

    private NanoHTTPD.IHTTPSession request(String path, String token, Map<String, String> params) {
        NanoHTTPD.IHTTPSession request = mock(NanoHTTPD.IHTTPSession.class);
        when(request.getUri()).thenReturn(path);
        when(request.getMethod()).thenReturn(NanoHTTPD.Method.GET);
        when(request.getRemoteIpAddress()).thenReturn("phone");
        when(request.getParms()).thenReturn(params == null ? Collections.emptyMap() : params);
        Map<String, String> headers = new HashMap<>();
        if (token != null) headers.put("authorization", "Bearer " + token);
        when(request.getHeaders()).thenReturn(headers);
        when(request.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        return request;
    }

    private JSONObject json(NanoHTTPD.Response response) throws Exception {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = response.getData().read(buffer)) != -1) output.write(buffer, 0, count);
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
        finally { response.close(); }
    }

    private NanoHTTPD.IHTTPSession postReceipt(String token, JSONObject body) {
        return postJson("/api/lab/v1/receipt", token, body);
    }

    private NanoHTTPD.IHTTPSession postJson(String path, String token, JSONObject body) {
        NanoHTTPD.IHTTPSession request = request(path, token, null);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        when(request.getMethod()).thenReturn(NanoHTTPD.Method.POST);
        request.getHeaders().put("content-length", Integer.toString(bytes.length));
        when(request.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
        return request;
    }
}
