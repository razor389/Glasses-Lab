package com.mentra.asg_client.io.server.services;

import com.mentra.asg_client.AsgConstants;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** In-memory authority granted by the paired phone over BLE for one local hotspot reservation. */
final class LabSyncSession {
    private final LongSupplier clock;
    private String token;
    private String credentials;
    private long lastUse;
    private long idleLeaseUntil;
    private BooleanSupplier hotspotEnabled;
    private Supplier<String> hotspotCredentials;
    private RecordingGrant recordingGrant;
    private long recordingReadyUntil;
    private String lastConsumedGrantId;
    private String lastConsumedSessionId;
    private String lastRevokedGrantId;

    LabSyncSession(LongSupplier clock) { this.clock = clock; }

    synchronized void configure(String value, BooleanSupplier enabled, Supplier<String> currentCredentials) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sync_token must contain 64 lowercase hexadecimal characters");
        }
        revokeRecordingGrant();
        token = value;
        credentials = null;
        lastUse = clock.getAsLong();
        idleLeaseUntil = 0;
        hotspotEnabled = enabled;
        hotspotCredentials = currentCredentials;
    }

    synchronized void clear() {
        revokeRecordingGrant();
        token = null;
        credentials = null;
        hotspotEnabled = null;
        hotspotCredentials = null;
        idleLeaseUntil = 0;
    }

    synchronized boolean authorize(String header) {
        String current = sessionCredentials();
        if (current == null) return false;
        if (header == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.US_ASCII),
                header.getBytes(StandardCharsets.US_ASCII))) return false;
        credentials = current;
        lastUse = clock.getAsLong();
        return true;
    }

    /** Only an authenticated renewal extends a lease; idle-monitor reads never refresh it. */
    synchronized boolean renewIdleLease(String header, int seconds) {
        if (seconds < 0 || seconds > AsgConstants.LAB_SYNC_IDLE_LEASE_MAX_SECONDS) {
            throw new IllegalArgumentException("Idle lease duration is out of range");
        }
        if (!authorize(header)) return false;
        idleLeaseUntil = seconds == 0 ? 0 : clock.getAsLong() + seconds * 1000L;
        return true;
    }

    synchronized boolean hasIdleLease() {
        return sessionCredentials() != null && credentials != null && clock.getAsLong() < idleLeaseUntil;
    }

    /** All renewal authority comes from an authenticated request on the actual local Wi-Fi link. */
    synchronized void renewRecordingGrant(String header, RecordingGrant grant, int seconds) {
        if (seconds < 0 || seconds > AsgConstants.RECORDING_READY_MAX_SECONDS) {
            throw new IllegalArgumentException("Recording readiness duration is out of range");
        }
        if (!authorize(header)) throw new IllegalStateException("phone_not_ready");
        if (grant.grantId.equals(lastConsumedGrantId) || grant.sessionId.equals(lastConsumedSessionId)) {
            throw new IllegalStateException("grant_consumed");
        }
        if (recordingGrant != null && recordingGrant.grantId.equals(grant.grantId)
                && !recordingGrant.sameAs(grant)) throw new IllegalStateException("grant_mismatch");
        if (seconds == 0) {
            if (recordingGrant != null && recordingGrant.grantId.equals(grant.grantId)) revokeRecordingGrant();
            return;
        }
        if (grant.grantId.equals(lastRevokedGrantId)) throw new IllegalStateException("grant_expired");
        recordingGrant = grant;
        recordingReadyUntil = clock.getAsLong() + seconds * 1000L;
    }

    /** Consume exactly once. Token/reservation loss and expiry never permit a local fallback. */
    synchronized RecordingGrant consumeRecordingGrant(String revision, String requestedSessionId) {
        if (sessionCredentials() == null || credentials == null) throw new IllegalStateException("phone_not_ready");
        if (recordingGrant == null) throw new IllegalStateException("phone_not_ready");
        if (recordingGrant.grantId.equals(lastConsumedGrantId)
                || recordingGrant.sessionId.equals(lastConsumedSessionId)) throw new IllegalStateException("grant_consumed");
        if (clock.getAsLong() >= recordingReadyUntil) throw new IllegalStateException("grant_expired");
        if (!recordingGrant.profileRevision.equals(revision)) throw new IllegalStateException("profile_mismatch");
        if (requestedSessionId != null && !requestedSessionId.equals(recordingGrant.sessionId)) {
            throw new IllegalStateException("grant_mismatch");
        }
        lastConsumedGrantId = recordingGrant.grantId;
        lastConsumedSessionId = recordingGrant.sessionId;
        return recordingGrant;
    }

    synchronized boolean isRecordingGrantConsumed(RecordingGrant grant) {
        return grant.grantId.equals(lastConsumedGrantId) || grant.sessionId.equals(lastConsumedSessionId);
    }

    /** Revoked unused authority is distinct from a consumed recording reservation. */
    synchronized void revokeRecordingGrant() {
        if (recordingGrant != null) {
            lastRevokedGrantId = recordingGrant.grantId;
        }
        recordingGrant = null;
        recordingReadyUntil = 0;
    }

    private String sessionCredentials() {
        if (token == null) return null;
        if (!hotspotEnabled.getAsBoolean()
                || clock.getAsLong() - lastUse > AsgConstants.LAB_SYNC_TOKEN_IDLE_MS) {
            clear();
            return null;
        }
        String current = hotspotCredentials.get();
        if (current == null || current.isEmpty()) return null;
        if (credentials != null && !credentials.equals(current)) {
            clear();
            return null;
        }
        return current;
    }
}
