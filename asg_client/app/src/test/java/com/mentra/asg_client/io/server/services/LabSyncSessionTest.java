package com.mentra.asg_client.io.server.services;

import static org.junit.Assert.*;
import com.mentra.asg_client.AsgConstants;
import org.junit.Test;

public class LabSyncSessionTest {
    @Test public void tokenMustBeProvisionedAndExact() {
        LabSyncSession session = new LabSyncSession(() -> 1L);
        assertFalse(session.authorize("Bearer " + "a".repeat(64)));
        session.configure("a".repeat(64), () -> true, () -> "ssid:password");
        assertFalse(session.authorize(null));
        assertFalse(session.authorize("Bearer " + "b".repeat(64)));
        assertTrue(session.authorize("Bearer " + "a".repeat(64)));
        session.clear();
        assertFalse(session.authorize("Bearer " + "a".repeat(64)));
    }

    @Test public void hotspotReservationChangeAndStopInvalidateToken() {
        boolean[] enabled = {true};
        String[] credentials = {"ssid:first"};
        String token = "c".repeat(64);
        LabSyncSession session = new LabSyncSession(() -> 1L);
        session.configure(token, () -> enabled[0], () -> credentials[0]);
        assertTrue(session.authorize("Bearer " + token));
        credentials[0] = "ssid:second";
        assertFalse(session.authorize("Bearer " + token));
        session.configure(token, () -> enabled[0], () -> credentials[0]);
        enabled[0] = false;
        assertFalse(session.authorize("Bearer " + token));
        enabled[0] = true;
        assertFalse(session.authorize("Bearer " + token));
    }

    @Test public void inactivityExpiresWhileAuthorizedActivityRenews() {
        long[] now = {1};
        String token = "d".repeat(64);
        LabSyncSession session = new LabSyncSession(() -> now[0]);
        session.configure(token, () -> true, () -> "ssid:password");
        now[0] += AsgConstants.LAB_SYNC_TOKEN_IDLE_MS - 1;
        assertTrue(session.authorize("Bearer " + token));
        now[0] += AsgConstants.LAB_SYNC_TOKEN_IDLE_MS - 1;
        assertTrue(session.authorize("Bearer " + token));
        now[0] += AsgConstants.LAB_SYNC_TOKEN_IDLE_MS + 1;
        assertFalse(session.authorize("Bearer " + token));
    }

    @Test public void malformedTokensCannotAuthorizePermanentCleanup() {
        LabSyncSession session = new LabSyncSession(() -> 1L);
        for (String value : new String[] {"", "short", "A".repeat(64), "x".repeat(64), "a".repeat(65)}) {
            try { session.configure(value, () -> true, () -> "ssid:password"); fail(); }
            catch (IllegalArgumentException expected) {}
        }
    }

    @Test public void idleLeaseExpiresDespitePollingAndUnrelatedAuthorizedRequests() {
        long[] now = {1};
        String header = "Bearer " + "a".repeat(64);
        LabSyncSession session = new LabSyncSession(() -> now[0]);
        session.configure("a".repeat(64), () -> true, () -> "ssid:password");
        assertFalse(session.hasIdleLease());
        assertTrue(session.renewIdleLease(header, 180));
        for (int second = 1; second < 180; second++) {
            now[0] = 1 + second * 1000L;
            assertTrue(session.hasIdleLease());
            assertTrue(session.authorize(header));
        }
        now[0] = 180_001;
        assertFalse(session.hasIdleLease());
        assertTrue(session.authorize(header));
    }

    @Test public void idleLeaseRenewalRevokeAndInvalidAuthorityAreBounded() {
        long[] now = {1};
        String header = "Bearer " + "a".repeat(64);
        LabSyncSession session = new LabSyncSession(() -> now[0]);
        session.configure("a".repeat(64), () -> true, () -> "ssid:password");
        assertTrue(session.renewIdleLease(header, 180));
        now[0] += 60_000;
        assertTrue(session.renewIdleLease(header, 180));
        now[0] += 179_999;
        assertTrue(session.hasIdleLease());
        assertFalse(session.renewIdleLease("Bearer " + "b".repeat(64), 180));
        now[0]++;
        assertFalse(session.hasIdleLease());
        assertTrue(session.renewIdleLease(header, 180));
        assertTrue(session.renewIdleLease(header, 0));
        assertFalse(session.hasIdleLease());
        for (int seconds : new int[] {-1, 181, Integer.MAX_VALUE}) {
            try { session.renewIdleLease(header, seconds); fail(); }
            catch (IllegalArgumentException expected) {}
        }
        assertFalse(session.hasIdleLease());
    }

    @Test public void recordingGrantIsConsumedOnceForEitherAppOrButton() {
        LabSyncSession session = recordingSession(() -> 1L);
        RecordingGrant grant = grant("revision-1", "grant-1", "session-1", 100L << 20);
        session.renewRecordingGrant(header(), grant, 90);

        // A button does not know the reserved session ID; it must consume the same reservation.
        assertSame(grant, session.consumeRecordingGrant("revision-1", null));
        assertTrue(session.isRecordingGrantConsumed(grant));
        expectState("grant_consumed", () -> session.consumeRecordingGrant("revision-1", "session-1"));
        for (int seconds : new int[] {0, 90}) {
            expectState("grant_consumed", () -> session.renewRecordingGrant(header(), grant, seconds));
            expectState("grant_consumed", () -> session.renewRecordingGrant(header(),
                    grant("revision-1", "grant-new", "session-1", 100L << 20), seconds));
            expectState("grant_consumed", () -> session.renewRecordingGrant(header(),
                    grant("revision-1", "grant-1", "session-new", 100L << 20), seconds));
        }
    }

    @Test public void renewalCannotChangeTheReservedQuotaProfileOrSession() {
        LabSyncSession session = recordingSession(() -> 1L);
        RecordingGrant original = grant("revision-1", "grant-1", "session-1", 100L << 20);
        session.renewRecordingGrant(header(), original, 90);
        for (RecordingGrant changed : new RecordingGrant[] {
                grant("revision-1", "grant-1", "session-1", 101L << 20),
                grant("revision-2", "grant-1", "session-1", 100L << 20),
                grant("revision-1", "grant-1", "session-2", 100L << 20)}) {
            expectState("grant_mismatch", () -> session.renewRecordingGrant(header(), changed, 90));
        }
        expectState("profile_mismatch", () -> session.consumeRecordingGrant("revision-2", "session-1"));
        expectState("grant_mismatch", () -> session.consumeRecordingGrant("revision-1", "session-2"));
        assertSame(original, session.consumeRecordingGrant("revision-1", "session-1"));
    }

    @Test public void unusedRevocationIsIdempotentAndCannotRevokeAnotherGrant() {
        LabSyncSession session = recordingSession(() -> 1L);
        RecordingGrant old = grant("revision-old", "grant-old", "session-old", 100L << 20);
        RecordingGrant current = grant("revision-current", "grant-current", "session-current", 100L << 20);
        session.renewRecordingGrant(header(), old, 90);
        session.renewRecordingGrant(header(), old, 0);
        session.renewRecordingGrant(header(), old, 0);
        assertFalse(session.isRecordingGrantConsumed(old));
        expectState("phone_not_ready", () -> session.consumeRecordingGrant("revision-old", null));
        expectState("grant_expired", () -> session.renewRecordingGrant(header(), old, 90));
        session.renewRecordingGrant(header(), current, 90);
        session.renewRecordingGrant(header(), old, 0);
        assertSame(current, session.consumeRecordingGrant("revision-current", "session-current"));
    }

    @Test public void onlyAuthorizedGrantRenewalExtendsRecordingReadiness() {
        long[] now = {1};
        LabSyncSession session = recordingSession(() -> now[0]);
        RecordingGrant grant = grant("revision-1", "grant-1", "session-1", 100L << 20);
        session.renewRecordingGrant(header(), grant, 90);
        now[0] += 60_000;
        session.renewRecordingGrant(header(), grant, 90);
        now[0] += 89_999;
        assertTrue(session.authorize(header()));
        expectState("phone_not_ready", () -> session.renewRecordingGrant(
                "Bearer " + "b".repeat(64), grant, 90));
        now[0]++;
        expectState("grant_expired", () -> session.consumeRecordingGrant("revision-1", "session-1"));
        assertFalse(session.isRecordingGrantConsumed(grant));
    }

    @Test public void grantCannotOutliveTokenOrHotspotReservation() {
        for (String failure : new String[] {"token", "disabled", "credentials", "idle", "clear"}) {
            long[] now = {1};
            boolean[] enabled = {true};
            String[] credentials = {"ssid:first"};
            LabSyncSession session = new LabSyncSession(() -> now[0]);
            session.configure("a".repeat(64), () -> enabled[0], () -> credentials[0]);
            RecordingGrant grant = grant("revision-1", "grant-1", "session-1", 100L << 20);
            session.renewRecordingGrant(header(), grant, 90);
            switch (failure) {
                case "token": session.configure("b".repeat(64), () -> true, () -> "ssid:first"); break;
                case "disabled": enabled[0] = false; break;
                case "credentials": credentials[0] = "ssid:second"; break;
                case "idle": now[0] += AsgConstants.LAB_SYNC_TOKEN_IDLE_MS + 1; break;
                case "clear": session.clear(); break;
                default: fail(failure);
            }
            expectState("phone_not_ready", () -> session.consumeRecordingGrant("revision-1", "session-1"));
            assertFalse(session.isRecordingGrantConsumed(grant));
        }
    }

    @Test public void recordingReadinessRejectsInvalidDurationsWithoutReplacingAuthority() {
        LabSyncSession session = recordingSession(() -> 1L);
        RecordingGrant original = grant("revision-1", "grant-1", "session-1", 100L << 20);
        session.renewRecordingGrant(header(), original, 90);
        for (int seconds : new int[] {-1, AsgConstants.RECORDING_READY_MAX_SECONDS + 1, Integer.MAX_VALUE}) {
            try {
                session.renewRecordingGrant(header(), grant("revision-2", "grant-2", "session-2", 100L << 20), seconds);
                fail("Invalid readiness duration was accepted");
            } catch (IllegalArgumentException expected) {}
        }
        assertSame(original, session.consumeRecordingGrant("revision-1", "session-1"));
    }

    private static LabSyncSession recordingSession(java.util.function.LongSupplier clock) {
        LabSyncSession session = new LabSyncSession(clock);
        session.configure("a".repeat(64), () -> true, () -> "ssid:password");
        return session;
    }

    private static String header() { return "Bearer " + "a".repeat(64); }

    private static RecordingGrant grant(String revision, String id, String session, long bytes) {
        return new RecordingGrant(revision, id, session, bytes);
    }

    private static void expectState(String message, Runnable action) {
        try {
            action.run();
            fail("Expected " + message);
        } catch (IllegalStateException expected) {
            assertEquals(message, expected.getMessage());
        }
    }

    @Test public void idleLeaseCannotSurviveAuthorityResetOrReservationChange() {
        boolean[] enabled = {true};
        String[] credentials = {"ssid:first"};
        String header = "Bearer " + "a".repeat(64);
        LabSyncSession session = new LabSyncSession(() -> 1L);
        session.configure("a".repeat(64), () -> enabled[0], () -> credentials[0]);
        assertTrue(session.renewIdleLease(header, 180));
        credentials[0] = "ssid:second";
        assertFalse(session.hasIdleLease());
        assertFalse(session.authorize(header));
        session.configure("a".repeat(64), () -> enabled[0], () -> credentials[0]);
        assertTrue(session.renewIdleLease(header, 180));
        enabled[0] = false;
        assertFalse(session.hasIdleLease());
        enabled[0] = true;
        assertFalse(session.authorize(header));
        session.configure("a".repeat(64), () -> enabled[0], () -> credentials[0]);
        assertTrue(session.renewIdleLease(header, 180));
        session.clear();
        assertFalse(session.hasIdleLease());
        session.configure("a".repeat(64), () -> true, () -> "ssid:first");
        assertTrue(session.renewIdleLease(header, 180));
        session.configure("b".repeat(64), () -> true, () -> "ssid:first");
        assertFalse(session.hasIdleLease());
        assertFalse(session.renewIdleLease(header, 180));
    }
}
