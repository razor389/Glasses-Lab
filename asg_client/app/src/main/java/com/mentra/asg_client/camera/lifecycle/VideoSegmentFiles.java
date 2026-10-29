package com.mentra.asg_client.camera.lifecycle;

import com.mentra.asg_client.AsgConstants;
import com.mentra.asg_client.io.file.core.FileManager;
import com.mentra.asg_client.io.server.services.AndroidDirectorySync;
import com.mentra.asg_client.settings.SegmentedVideoOptions;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable segment identities and bounded disk accounting. Only a verified, closed file is renamed
 * from .partial to .mp4; a process death cannot make an unfinished recording eligible for sync.
 * Session membership lives in the capture directory name, so no unarchived sidecars accumulate.
 */
public final class VideoSegmentFiles {
    private static final Set<String> pendingPublications = ConcurrentHashMap.newKeySet();
    public final SegmentedVideoOptions options;
    private final File root;
    private final String prefix;
    private long finalizedBytes;
    private int finalizedCount;
    private final DirectorySync directorySync;

    /** Injectable durability barrier for deterministic filesystem failure tests. */
    public interface DirectorySync { void sync(File directory) throws IOException; }

    /** Decoded membership of one segment, independent of the running recording process. */
    public static final class Identity {
        public final String sessionId;
        public final int index;
        Identity(String sessionId, int index) { this.sessionId = sessionId; this.index = index; }
    }

    /** Make the first capture path, without opening a recorder or exposing a ready media file. */
    public static File firstFile(File root, String requestId) throws IOException {
        validateRequestId(requestId);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(requestId.getBytes(StandardCharsets.UTF_8));
        String nonce = Long.toString(System.currentTimeMillis()) + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 8);
        File directory = new File(root, "VID_" + nonce + "_SEG_" + encoded + "_0");
        if (!directory.mkdir()) throw new IOException("Cannot create recording segment directory");
        return new File(directory, "base.mp4.partial");
    }

    /** Session IDs are losslessly encoded, not sanitized or truncated. */
    public static void validateRequestId(String requestId) {
        if (requestId == null || requestId.isEmpty()
                || requestId.getBytes(StandardCharsets.UTF_8).length > AsgConstants.VIDEO_SEGMENT_REQUEST_ID_MAX_BYTES) {
            throw new IllegalArgumentException("Segmented requestId must contain 1–128 UTF-8 bytes");
        }
    }

    /** Returns null for legacy or malformed capture names. */
    public static Identity identity(String captureName) {
        if (captureName == null || !captureName.startsWith("VID_")) return null;
        int marker = captureName.indexOf("_SEG_");
        int indexAt = captureName.lastIndexOf('_');
        if (marker < 0 || indexAt <= marker + 5) return null;
        try {
            int index = Integer.parseInt(captureName.substring(indexAt + 1));
            String session = new String(Base64.getUrlDecoder()
                    .decode(captureName.substring(marker + 5, indexAt)), StandardCharsets.UTF_8);
            validateRequestId(session);
            return index < 0 ? null : new Identity(session, index);
        } catch (IllegalArgumentException invalid) { return null; }
    }

    /** Keep a renamed file hidden until its directory entry is durable. */
    public static boolean isPublicationPending(String captureId) {
        return pendingPublications.contains(captureId);
    }

    /** Open accounting around a first segment created by {@link #firstFile}. */
    public VideoSegmentFiles(File first, SegmentedVideoOptions options) throws IOException {
        this(first, options, AndroidDirectorySync::sync);
    }

    public VideoSegmentFiles(File first, SegmentedVideoOptions options, DirectorySync directorySync) throws IOException {
        File directory = first.getParentFile();
        Identity identity = directory == null ? null : identity(directory.getName());
        if (identity == null || identity.index != 0) throw new IOException("Invalid initial segment path");
        this.root = directory.getParentFile();
        this.prefix = directory.getName().substring(0, directory.getName().lastIndexOf('_') + 1);
        this.options = options;
        this.directorySync = directorySync;
        directorySync.sync(directory);
        directorySync.sync(root);
    }

    /** Preallocate only a new empty capture directory; never replace existing recordings. */
    public File nextFile(int index) throws IOException {
        File directory = new File(root, prefix + index);
        if (!directory.mkdir()) throw new IOException("Segment path already exists or cannot be created");
        directorySync.sync(root);
        return new File(directory, "base.mp4.partial");
    }

    /** Count current and completed payloads still retained on the glasses, including partials. */
    public long backlogBytes() {
        long bytes = 0;
        File[] directories = root.listFiles();
        if (directories == null) return Long.MAX_VALUE;
        for (File directory : directories) {
            if (identity(directory.getName()) == null) continue;
            File[] files = directory.listFiles();
            if (files == null) return Long.MAX_VALUE;
            for (File file : files) bytes += file.length();
        }
        // A durable receipt may have moved media into quarantine but not reclaimed it yet.
        // Count every held payload conservatively, including interrupted earlier sessions.
        File journals = new File(new File(root,
                FileManager.GALLERY_METADATA_DIR_NAME),
                AsgConstants.LAB_SYNC_RECEIPT_DIRECTORY);
        File[] held = journals.listFiles();
        if (held != null) for (File file : held) {
            if (file.getName().endsWith(".payload")) bytes += file.length();
        }
        return bytes;
    }

    public long totalBytes(File current, File next) {
        return finalizedBytes + (current == null ? 0 : current.length())
                + (next == null ? 0 : next.length());
    }

    /** Space for a whole following segment, plus buffered encoder output, must exist up front. */
    public String nextFileStopReason(File current, long availableBytes) {
        long required = options.segmentMaxBytes + AsgConstants.VIDEO_SEGMENT_STOP_HEADROOM_BYTES;
        if (availableBytes < required) return "storage_low";
        if (backlogBytes() > options.maxBacklogBytes - required) return "backlog_full";
        if (totalBytes(current, null) > options.maxSessionBytes - required) return "session_byte_limit";
        return null;
    }

    /** Caller verifies media tracks before this durable ready transition. */
    public File publishVerified(File partial) throws IOException {
        Identity identity = identity(partial.getParentFile().getName());
        if (identity == null || identity.index != finalizedCount || partial.length() <= 0
                || !partial.getParentFile().getName().equals(prefix + finalizedCount)
                || !partial.getName().equals("base.mp4.partial")) {
            throw new IOException("Unexpected or empty recording segment");
        }
        try (FileOutputStream output = new FileOutputStream(partial, true)) { output.getFD().sync(); }
        directorySync.sync(partial.getParentFile());
        File ready = new File(partial.getParentFile(), "base.mp4");
        if (ready.exists()) throw new IOException("Ready segment already exists");
        String captureId = partial.getParentFile().getName();
        pendingPublications.add(captureId);
        boolean moved = false;
        try {
            Files.move(partial.toPath(), ready.toPath(), StandardCopyOption.ATOMIC_MOVE);
            moved = true;
            directorySync.sync(ready.getParentFile());
            finalizedBytes += ready.length();
            finalizedCount++;
            pendingPublications.remove(captureId);
        } catch (IOException error) {
            // Preserve the exact payload, and restore the hidden suffix when possible. If the
            // filesystem also refuses rollback, the process-local barrier remains closed. After
            // restart a surviving .mp4 is still a fully verified, fsynced payload; the cached
            // terminal error prevents the phone from claiming the session was completed.
            if (moved) {
                try {
                    Files.move(ready.toPath(), partial.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    directorySync.sync(partial.getParentFile());
                } catch (IOException rollbackFailure) { error.addSuppressed(rollbackFailure); }
            }
            throw error;
        }
        return ready;
    }

    public int finalizedCount() { return finalizedCount; }
}
