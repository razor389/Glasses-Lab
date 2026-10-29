package com.mentra.asg_client.io.server.services;

import com.mentra.asg_client.AsgConstants;
import com.mentra.asg_client.io.file.core.FileManager;
import com.mentra.asg_client.utils.GallerySyncFilter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

/**
 * Reclaims one exact, verified gallery file. Never deletes a capture tree or an unmatched sibling.
 * Receipt metadata is durable before the payload is quarantined; retries can finish interrupted work.
 */
final class VerifiedMediaReclaimer {
    static final class Entry {
        final String name;
        final String location;
        final File file;
        final long size;
        final long modified;
        final String generation;

        Entry(String name, String location, File file) {
            this.name = name;
            this.location = location;
            this.file = file;
            size = file.length();
            modified = file.lastModified();
            generation = GalleryTransferProtocol.createEtag(name, size, modified);
        }

        String key() { return location + ":" + name; }
    }

    static final class Receipt {
        final String id;
        final String name;
        final String location;
        final long size;
        final String generation;
        final String sha256;

        Receipt(String id, String name, String location, long size, String generation, String sha256) {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}") || size <= 0
                    || generation == null || generation.length() > 200
                    || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid verified-copy receipt");
            }
            validateName(name);
            validateLocation(location);
            this.id = id;
            this.name = name;
            this.location = location;
            this.size = size;
            this.generation = generation;
            this.sha256 = sha256;
        }

        Properties properties() {
            Properties values = new Properties();
            values.setProperty("receipt_id", id);
            values.setProperty("file", name);
            values.setProperty("location", location);
            values.setProperty("size", Long.toString(size));
            values.setProperty("generation", generation);
            values.setProperty("sha256", sha256);
            return values;
        }
    }

    static final class Result {
        final boolean alreadyReclaimed;
        final long reclaimedBytes;
        Result(boolean alreadyReclaimed, long reclaimedBytes) {
            this.alreadyReclaimed = alreadyReclaimed;
            this.reclaimedBytes = reclaimedBytes;
        }
    }

    static final class AuxiliaryStats {
        int files;
        long bytes;
    }

    static final class AuxiliaryCleanup {
        final List<Entry> entries;
        final String id;
        final long bytes;
        final int retainedFiles;

        AuxiliaryCleanup(List<Entry> entries, int retainedFiles) throws IOException {
            this.entries = entries;
            this.retainedFiles = retainedFiles;
            long total = 0;
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (Entry entry : entries) {
                    total = Math.addExact(total, entry.size);
                    digest.update((entry.key() + "\n" + entry.generation + "\n"
                            + GalleryTransferProtocol.sha256(entry.file) + "\n").getBytes(StandardCharsets.UTF_8));
                }
                StringBuilder value = new StringBuilder();
                for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 0xff));
                id = value.toString();
            } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
            bytes = total;
        }
    }

    /** Stale generations, active writers, and digest mismatches are retryable conflicts, never deletes. */
    static final class Conflict extends IOException {
        Conflict(String message) { super(message); }
    }

    private final File root;
    private final File trash;
    private final File journals;
    private final Predicate<String> blocked;
    private final LongSupplier clock;
    private final DirectorySync directorySync;

    interface DirectorySync { void sync(File directory) throws IOException; }

    VerifiedMediaReclaimer(File root, Predicate<String> blocked, LongSupplier clock,
            DirectorySync directorySync) throws IOException {
        this.root = root.getCanonicalFile();
        this.trash = child(this.root, FileManager.GALLERY_TRASH_DIR_NAME);
        this.journals = child(child(this.root, FileManager.GALLERY_METADATA_DIR_NAME),
                AsgConstants.LAB_SYNC_RECEIPT_DIRECTORY);
        this.blocked = blocked;
        this.clock = clock;
        this.directorySync = directorySync;
    }

    /** Complete durable pending receipts before listing. Ambiguous payloads remain held for recovery. */
    synchronized int recover() throws IOException {
        Set<String> pending = new HashSet<>();
        for (File journal : children(journals)) {
            if (!journal.getName().endsWith(".properties")) continue;
            try {
                Properties values = new Properties();
                try (FileInputStream input = new FileInputStream(journal)) { values.load(input); }
                if ("complete".equals(values.getProperty("state"))) continue;
                Receipt receipt = new Receipt(values.getProperty("receipt_id"), values.getProperty("file"),
                        values.getProperty("location"), Long.parseLong(values.getProperty("size")),
                        values.getProperty("generation"), values.getProperty("sha256"));
                if (!(receipt.id + ".properties").equals(journal.getName())) {
                    throw new IOException("Receipt journal name does not match its identity");
                }
                reclaim(receipt);
            } catch (IOException | IllegalArgumentException error) {
                pending.add(journal.getName().replaceFirst("\\.properties$", ""));
            }
        }
        for (File payload : children(journals)) {
            if (payload.getName().endsWith(".payload")) {
                String id = payload.getName().substring(0, payload.getName().length() - ".payload".length());
                pending.add(id);
            }
        }
        return pending.size() + pendingPhotoMarkers();
    }

    private int pendingPhotoMarkers() throws IOException {
        int count = countPhotoMarkers(root);
        for (File capture : children(root)) {
            if (capture.isDirectory() && !capture.getName().startsWith("_") && !capture.getName().startsWith(".")) {
                count += countPhotoMarkers(child(root, capture.getName()));
            }
        }
        for (File capture : children(trash)) {
            if (capture.isDirectory()) count += countPhotoMarkers(child(trash, capture.getName()));
        }
        return count;
    }

    private static int countPhotoMarkers(File directory) {
        int count = 0;
        for (File file : children(directory)) {
            if (file.isFile() && file.getName().endsWith(AsgConstants.PHOTO_PERSISTENCE_PENDING_SUFFIX)) count++;
        }
        return count;
    }

    synchronized List<Entry> list() throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (File candidate : children(root)) {
            if (candidate.getName().startsWith("_") || candidate.getName().startsWith(".")) continue;
            if (candidate.isDirectory()) {
                for (File nested : children(candidate)) add(entries, candidate.getName() + "/" + nested.getName(), "live");
            } else add(entries, candidate.getName(), "live");
        }
        for (File capture : children(trash)) {
            if (!capture.isDirectory()) continue;
            boolean flat = new File(capture, ".flat_layout").isFile();
            for (File candidate : children(capture)) {
                add(entries, flat ? candidate.getName() : capture.getName() + "/" + candidate.getName(), "trash");
            }
        }
        entries.sort(Comparator.comparing(Entry::key));
        return entries;
    }

    /** Non-transferable gallery artifacts remain visible in accounting without authorizing deletion. */
    synchronized AuxiliaryStats retainedAuxiliaries() throws IOException {
        AuxiliaryStats result = new AuxiliaryStats();
        for (File candidate : children(root)) {
            if (candidate.getName().startsWith("_") || candidate.getName().startsWith(".")) continue;
            if (candidate.isDirectory()) {
                File capture = child(root, candidate.getName());
                for (File nested : children(capture)) {
                    countAuxiliary(result, capture, nested.getName(), candidate.getName() + "/" + nested.getName());
                }
            } else countAuxiliary(result, root, candidate.getName(), candidate.getName());
        }
        for (File candidate : children(trash)) {
            if (!candidate.isDirectory()) continue;
            File capture = child(trash, candidate.getName());
            boolean flat = new File(capture, ".flat_layout").isFile();
            for (File nested : children(capture)) {
                String name = flat ? nested.getName() : candidate.getName() + "/" + nested.getName();
                countAuxiliary(result, capture, nested.getName(), name);
            }
        }
        return result;
    }

    static boolean isThumbnail(String name) {
        return name != null && name.startsWith("VID_") && name.contains("/")
                && new File(name).getName().equalsIgnoreCase(AsgConstants.VIDEO_THUMBNAIL_SIDECAR_NAME);
    }

    /** Explicit user cleanup applies only to known extras of already-reclaimed captures. */
    synchronized AuxiliaryCleanup previewAuxiliaryCleanup(BooleanSupplier busy) throws IOException {
        requireCleanupIdle(busy);
        Set<String> completed = new HashSet<>();
        Set<String> pending = new HashSet<>();
        for (File journal : children(journals)) {
            if (!journal.getName().endsWith(".properties")) continue;
            Properties values = new Properties();
            try (FileInputStream input = new FileInputStream(child(journals, journal.getName()))) {
                values.load(input);
            }
            String name = values.getProperty("file", "");
            String location = values.getProperty("location", "");
            if (!name.matches("(?:IMG|VID)_[A-Za-z0-9_.-]+/base\\.(?:jpg|mp4)")
                    || !("live".equals(location) || "trash".equals(location))) continue;
            String capture = name.substring(0, name.indexOf('/'));
            String key = location + ":" + capture;
            if ("complete".equals(values.getProperty("state"))) completed.add(key);
            else pending.add(key);
        }
        completed.removeAll(pending);
        List<Entry> entries = new ArrayList<>();
        for (String key : completed) {
            int split = key.indexOf(':');
            String location = key.substring(0, split);
            String capture = key.substring(split + 1);
            File directory = child("live".equals(location) ? root : trash, capture);
            if (!directory.isDirectory() || blocked.test(capture + "/base.jpg")
                    || blocked.test(capture + "/base.mp4")) continue;
            List<Entry> candidates = new ArrayList<>();
            boolean eligible = true;
            // Any primary, partial, pending marker, RAW, or unknown member retains this capture.
            for (File file : children(directory)) {
                String leaf = file.getName();
                if ("trash".equals(location) && (".flat_layout".equals(leaf) || ".ack_ids".equals(leaf))) continue;
                if (!file.isFile() || !("imu.json".equals(leaf)
                        || (capture.startsWith("VID_") && AsgConstants.VIDEO_THUMBNAIL_SIDECAR_NAME.equals(leaf)))) {
                    eligible = false;
                    break;
                }
                File checked = child(directory, leaf);
                if (clock.getAsLong() - checked.lastModified() < AsgConstants.LAB_SYNC_SETTLE_MS) {
                    eligible = false;
                    break;
                }
                candidates.add(new Entry(capture + "/" + leaf, location, checked));
            }
            if (eligible) entries.addAll(candidates);
        }
        entries.sort(Comparator.comparing(Entry::key));
        AuxiliaryCleanup preview = new AuxiliaryCleanup(entries, retainedAuxiliaries().files - entries.size());
        requireCleanupIdle(busy);
        return preview;
    }

    /** Delete only the previewed set; a changed preview requires fresh user confirmation. */
    synchronized AuxiliaryCleanup purgeAuxiliaries(String previewId, BooleanSupplier busy) throws IOException {
        if (previewId == null || !previewId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid auxiliary cleanup preview");
        }
        AuxiliaryCleanup preview = previewAuxiliaryCleanup(busy);
        if (!preview.id.equals(previewId)) throw new Conflict("Auxiliary files changed; review cleanup again");
        for (Entry entry : preview.entries) {
            requireCleanupIdle(busy);
            if (blocked.test(entry.name)) throw new Conflict("Capture became busy; review cleanup again");
            File source = resolve(entry.name, entry.location);
            requireGeneration(source, entry.name, entry.size, entry.generation);
            if (!source.delete()) throw new IOException("Auxiliary cleanup interrupted; review remaining files");
            directorySync.sync(source.getParentFile());
            cleanupEmptyParent(source.getParentFile());
        }
        return preview;
    }

    private static void requireCleanupIdle(BooleanSupplier busy) throws Conflict {
        if (busy.getAsBoolean()) throw new Conflict("Finish captures and transfers before cleaning auxiliary files");
    }

    private static void countAuxiliary(AuxiliaryStats total, File parent, String leaf, String logicalName) throws IOException {
        if (leaf.startsWith(".") || leaf.endsWith(AsgConstants.PHOTO_PERSISTENCE_PENDING_SUFFIX)) return;
        File file;
        try { file = child(parent, leaf); }
        catch (IllegalArgumentException outside) { return; }
        if (!file.isFile()) return;
        boolean auxiliary = isThumbnail(logicalName);
        if (!auxiliary) {
            try { validateName(logicalName); }
            catch (IllegalArgumentException unsupported) { auxiliary = true; }
        }
        if (auxiliary) {
            total.files++;
            total.bytes = Math.addExact(total.bytes, file.length());
        }
    }

    private void add(List<Entry> entries, String name, String location) throws IOException {
        try { entries.add(inspect(name, location)); }
        catch (IllegalArgumentException | FileNotFoundException | Conflict ignored) {
            // Non-media artifacts and in-flight captures never enter the transfer manifest.
        }
    }

    synchronized Entry inspect(String name, String location) throws IOException {
        validateName(name);
        validateLocation(location);
        File file = resolve(name, location);
        requireReady(name, file);
        return new Entry(name, location, file);
    }

    synchronized String hash(Entry entry) throws IOException {
        requireGeneration(entry.file, entry.name, entry.size, entry.generation);
        requireReady(entry.name, entry.file);
        String digest = GalleryTransferProtocol.sha256(entry.file);
        requireGeneration(entry.file, entry.name, entry.size, entry.generation);
        requireReady(entry.name, entry.file);
        return digest;
    }

    synchronized Result reclaim(Receipt receipt) throws IOException {
        File journal = child(journals, receipt.id + ".properties");
        File quarantine = child(journals, receipt.id + ".payload");
        Properties saved = null;
        if (journal.isFile()) {
            saved = new Properties();
            try (FileInputStream input = new FileInputStream(journal)) { saved.load(input); }
            for (String key : receipt.properties().stringPropertyNames()) {
                if (!receipt.properties().getProperty(key).equals(saved.getProperty(key))) {
                    throw new Conflict("Receipt ID is already used for a different file generation");
                }
            }
            if ("complete".equals(saved.getProperty("state"))) return new Result(true, 0);
            if (!"prepared".equals(saved.getProperty("state")) && !"verified".equals(saved.getProperty("state"))) {
                throw new Conflict("Receipt journal has an unknown state; payload retained");
            }
        }

        if (quarantine.exists() && saved == null) {
            throw new Conflict("Quarantined payload has no matching durable receipt; manual recovery required");
        }

        File source = resolve(receipt.name, receipt.location);
        if (!quarantine.exists()) {
            if (!source.isFile()) {
                if (saved != null && "verified".equals(saved.getProperty("state"))) {
                    // Crash after unlink but before the completion marker: the durable receipt survives.
                    writeJournal(journal, receipt, "complete");
                    return new Result(true, 0);
                }
                throw new FileNotFoundException("Receipt source is unavailable; nothing was reclaimed");
            }
            verify(source, receipt);
            writeJournal(journal, receipt, "prepared");
            // Atomic rename isolates the checked generation from any future file using the same name.
            Files.move(source.toPath(), quarantine.toPath(), StandardCopyOption.ATOMIC_MOVE);
            directorySync.sync(source.getParentFile());
            directorySync.sync(journals);
        }

        // Never restore with rename: ATOMIC_MOVE may overwrite a new file at the original path.
        // A changed payload remains quarantined, with its journal and manifest recovery count.
        verify(quarantine, receipt);
        writeJournal(journal, receipt, "verified");
        // Recheck after journal IO, including the active-recording barrier, before the final unlink.
        verify(quarantine, receipt);
        if (!quarantine.delete()) throw new IOException("Verified payload could not be reclaimed; retry the receipt");
        directorySync.sync(journals);
        writeJournal(journal, receipt, "complete");
        cleanupEmptyParent(source.getParentFile());
        return new Result(false, receipt.size);
    }

    private void verify(File file, Receipt receipt) throws IOException {
        requireReady(receipt.name, file);
        requireGeneration(file, receipt.name, receipt.size, receipt.generation);
        if (!receipt.sha256.equals(GalleryTransferProtocol.sha256(file))) throw new Conflict("SHA-256 mismatch; source retained");
        requireGeneration(file, receipt.name, receipt.size, receipt.generation);
        requireReady(receipt.name, file);
    }

    private void requireReady(String name, File file) throws IOException {
        if (!file.isFile()) throw new FileNotFoundException("Media file was not found");
        if (blocked.test(name) || file.length() <= 0
                || clock.getAsLong() - file.lastModified() < AsgConstants.LAB_SYNC_SETTLE_MS) {
            throw new Conflict("Capture is active, incomplete, or still settling");
        }
    }

    private static void requireGeneration(File file, String name, long size, String generation) throws IOException {
        if (!file.isFile() || file.length() != size
                || !generation.equals(GalleryTransferProtocol.createEtag(name, file.length(), file.lastModified()))) {
            throw new Conflict("File generation changed; source retained");
        }
    }

    private File resolve(String name, String location) throws IOException {
        if ("live".equals(location)) return child(root, name);
        String captureId = GallerySyncFilter.deriveCaptureId(name);
        File capture = child(trash, captureId);
        return child(capture, name.contains("/") ? name.substring(name.indexOf('/') + 1) : name);
    }

    private static void validateName(String name) {
        if (name == null || name.length() > 255 || !name.matches("[A-Za-z0-9][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]+)*(?:/[A-Za-z0-9][A-Za-z0-9_.-]*)?")) {
            throw new IllegalArgumentException("Invalid gallery media path");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".mp4") || lower.endsWith(".wav") || lower.endsWith(".m4a"))) {
            throw new IllegalArgumentException("Only completed photo, video, and audio files can be reclaimed");
        }
    }

    private static void validateLocation(String location) {
        if (!"live".equals(location) && !"trash".equals(location)) throw new IllegalArgumentException("Invalid media location");
    }

    private static File child(File parent, String name) throws IOException {
        File candidate = new File(parent, name);
        File canonical = candidate.getCanonicalFile();
        if (!canonical.toPath().startsWith(parent.getCanonicalFile().toPath())
                || !candidate.getAbsoluteFile().equals(canonical)) {
            throw new IllegalArgumentException("Gallery path escapes its storage or traverses a symbolic link");
        }
        return canonical;
    }

    private static File[] children(File parent) {
        File[] items = parent.listFiles();
        return items == null ? new File[0] : items;
    }

    private void writeJournal(File target, Receipt receipt, String state) throws IOException {
        File parent = target.getParentFile();
        if (!parent.isDirectory()) {
            if (!parent.mkdirs()) throw new IOException("Receipt storage is unavailable");
            directorySync.sync(parent.getParentFile());
            directorySync.sync(root);
        }
        Properties values = receipt.properties();
        values.setProperty("state", state);
        File temporary = child(parent, target.getName() + ".new");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            values.store(output, "Verified phone copy receipt; payload deletion requires matching identity");
            output.getFD().sync();
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        directorySync.sync(parent);
    }

    private void cleanupEmptyParent(File parent) {
        // Remove only genuinely empty capture folders. Preserve all unacknowledged siblings/metadata.
        if (!parent.equals(root) && !parent.equals(trash) && children(parent).length == 0) parent.delete();
    }
}
