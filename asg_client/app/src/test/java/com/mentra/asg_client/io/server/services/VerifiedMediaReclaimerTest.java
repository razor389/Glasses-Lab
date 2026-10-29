package com.mentra.asg_client.io.server.services;

import static org.junit.Assert.*;

import com.mentra.asg_client.AsgConstants;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class VerifiedMediaReclaimerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File root;
    private VerifiedMediaReclaimer reclaimer;

    @Before public void setup() throws Exception {
        root = temporary.newFolder("gallery");
        reclaimer = new VerifiedMediaReclaimer(root, name -> false, () -> 100_000L, directory -> {});
    }

    @Test public void reclaimsOnlyExactVerifiedFileAndRemovesEmptyCaptureDirectory() throws Exception {
        File source = write("VID_1/base.mp4", "my video");
        File other = write("VID_10/base.mp4", "another video");
        VerifiedMediaReclaimer.Receipt receipt = receipt("receipt-1", "VID_1/base.mp4", "live");
        VerifiedMediaReclaimer.Result result = reclaimer.reclaim(receipt);
        assertFalse(result.alreadyReclaimed);
        assertEquals(8, result.reclaimedBytes);
        assertFalse(source.exists());
        assertFalse(source.getParentFile().exists());
        assertTrue(other.isFile());
    }

    @Test public void wrongHashAndGenerationLeaveOriginalUntouched() throws Exception {
        File source = write("IMG_1.jpg", "photo");
        VerifiedMediaReclaimer.Receipt original = receipt("receipt-1", "IMG_1.jpg", "live");
        expectConflict(() -> reclaimer.reclaim(new VerifiedMediaReclaimer.Receipt(original.id, original.name,
                original.location, original.size, original.generation, "0".repeat(64))));
        assertTrue(source.isFile());
        source.setLastModified(20_000);
        expectConflict(() -> reclaimer.reclaim(original));
        assertTrue(source.isFile());
    }

    @Test public void duplicateReceiptAfterRestartNeverDeletesReplacementGeneration() throws Exception {
        write("IMG_1.jpg", "photo");
        VerifiedMediaReclaimer.Receipt receipt = receipt("same-receipt", "IMG_1.jpg", "live");
        reclaimer.reclaim(receipt);
        File replacement = write("IMG_1.jpg", "new photo");
        reclaimer = new VerifiedMediaReclaimer(root, name -> false, () -> 100_000L, directory -> {});
        assertTrue(reclaimer.reclaim(receipt).alreadyReclaimed);
        assertEquals("new photo", new String(Files.readAllBytes(replacement.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void receiptIdCannotBeReusedForAnotherPayload() throws Exception {
        write("IMG_1.jpg", "first");
        write("IMG_2.jpg", "second");
        reclaimer.reclaim(receipt("receipt-1", "IMG_1.jpg", "live"));
        expectConflict(() -> reclaimer.reclaim(receipt("receipt-1", "IMG_2.jpg", "live")));
        assertTrue(new File(root, "IMG_2.jpg").isFile());
    }

    @Test public void activeAndSettlingFilesCannotBeListedOrReclaimed() throws Exception {
        write("VID_active/base.mp4", "active");
        VerifiedMediaReclaimer.Receipt receipt = receipt("receipt-1", "VID_active/base.mp4", "live");
        reclaimer = new VerifiedMediaReclaimer(root, name -> true, () -> 100_000L, directory -> {});
        assertTrue(reclaimer.list().isEmpty());
        expectConflict(() -> reclaimer.reclaim(receipt));
        reclaimer = new VerifiedMediaReclaimer(root, name -> false, () -> 11_000L, directory -> {});
        assertTrue(reclaimer.list().isEmpty());
        expectConflict(() -> reclaimer.reclaim(receipt));
    }

    @Test public void trashedFileReceiptDoesNotTouchLiveFileOrOtherTrashMembers() throws Exception {
        write("VID_1/base.mp4", "old");
        GalleryTrashManager trash = new GalleryTrashManager(root);
        trash.trashCapture("VID_1", "legacy-ack");
        File live = write("VID_1/base.mp4", "new");
        File sibling = write("_gallery_trash/VID_1/imu.json", "metadata");
        VerifiedMediaReclaimer.Receipt receipt = receipt("receipt-1", "VID_1/base.mp4", "trash");
        reclaimer.reclaim(receipt);
        assertTrue(live.isFile());
        assertTrue(sibling.isFile());
        assertFalse(new File(root, "_gallery_trash/VID_1/base.mp4").exists());
    }

    @Test public void flatTrashUsesOriginalCaptureIdentity() throws Exception {
        write("IMG_1.jpg", "photo");
        new GalleryTrashManager(root).trashCapture("IMG_1", "legacy-ack");
        assertEquals("IMG_1.jpg", reclaimer.list().get(0).name);
        reclaimer.reclaim(receipt("receipt-1", "IMG_1.jpg", "trash"));
        assertFalse(new File(root, "_gallery_trash/IMG_1/IMG_1.jpg").exists());
    }

    @Test public void retainedAuxiliaryTotalsIncludeLiveAndTrashArtifactsWithoutDeletingThem() throws Exception {
        File[] retained = {
                write("IMG_1/imu.json", "live imu"),
                write("IMG_1/base.dng", "raw sensor data"),
                write("VID_2/thumb.jpg", "video preview"),
                write("VID_2/base.mp4.partial", "unfinished"),
                write("_gallery_trash/IMG_3/imu.json", "trash imu"),
                write("_gallery_trash/VID_4/thumb.jpg", "trash preview"),
                write("_gallery_trash/IMG_5/IMG_5.raw", "legacy raw")
        };
        write("_gallery_trash/IMG_5/.flat_layout", "");
        write("IMG_1/base.jpg", "photo");
        write("IMG_1/bracket_0.jpg", "bracket photo");
        write("VID_2/base.mp4", "video");
        write("IMG_6.jpg", "flat photo");
        write("IMG_1/.photo-persistence.pending", "pending marker");
        write(".IMG_6.photo-persistence.pending", "flat pending marker");
        write("_sdk_pending/IMG_7.jpg", "transient photo");
        write("_gallery_metadata/verified_receipts/old.properties", "receipt metadata");
        write("_gallery_metadata/verified_receipts/held.payload", "held recovery payload");
        long expectedBytes = 0;
        for (File file : retained) expectedBytes += file.length();

        VerifiedMediaReclaimer.AuxiliaryStats totals = reclaimer.retainedAuxiliaries();

        assertEquals(retained.length, totals.files);
        assertEquals(expectedBytes, totals.bytes);
        for (File file : retained) assertTrue(file.isFile());
        assertTrue(new File(root, "_gallery_metadata/verified_receipts/held.payload").isFile());
        assertFalse(VerifiedMediaReclaimer.isThumbnail("IMG_1/thumb.jpg"));
        assertFalse(VerifiedMediaReclaimer.isThumbnail("thumb.jpg"));
        assertTrue(VerifiedMediaReclaimer.isThumbnail("VID_2/thumb.jpg"));
    }

    @Test public void primaryReceiptRetainsAndAccountsForUnverifiedSidecar() throws Exception {
        File primary = write("IMG_1/base.jpg", "photo");
        File sidecar = write("IMG_1/imu.json", "full sensor samples");
        reclaimer.reclaim(receipt("receipt-1", "IMG_1/base.jpg", "live"));

        assertFalse(primary.exists());
        assertTrue(sidecar.isFile());
        assertTrue(reclaimer.list().isEmpty());
        assertEquals(1, reclaimer.retainedAuxiliaries().files);
        assertEquals(sidecar.length(), reclaimer.retainedAuxiliaries().bytes);
        try { receipt("receipt-sidecar", "IMG_1/imu.json", "live"); fail("Sidecar has no media receipt authority"); }
        catch (IllegalArgumentException expected) {}
        assertTrue(sidecar.isFile());
    }

    @Test public void rejectTraversalHiddenStoragePartialFilesAndUnexpectedLocations() throws Exception {
        String[] paths = {"../outside.jpg", "VID_1/../IMG_1.jpg", "/IMG_1.jpg", "VID_1\\base.mp4",
                "_sdk_pending/photo.jpg", "_gallery_metadata/photo.jpg", "VID_1/base.mp4.part", "VID_1/imu.json", ".hidden.jpg"};
        for (String path : paths) {
            try { reclaimer.inspect(path, "live"); fail(path); }
            catch (IllegalArgumentException expected) {}
        }
        try { reclaimer.inspect("IMG_1.jpg", "other"); fail(); }
        catch (IllegalArgumentException expected) {}
    }

    @Test public void interruptedQuarantineFinishesIdempotentlyAfterProcessRestart() throws Exception {
        File source = write("IMG_1.jpg", "photo");
        VerifiedMediaReclaimer.Receipt receipt = receipt("receipt-1", "IMG_1.jpg", "live");
        File journalDirectory = journalDirectory();
        persistJournal(receipt, "prepared");
        Files.move(source.toPath(), new File(journalDirectory, "receipt-1.payload").toPath());
        reclaimer = new VerifiedMediaReclaimer(root, name -> false, () -> 100_000L, directory -> {});
        assertEquals(5, reclaimer.reclaim(receipt).reclaimedBytes);
        assertTrue(reclaimer.reclaim(receipt).alreadyReclaimed);
    }

    @Test public void interruptedCompletionDoesNotRequirePayloadToExist() throws Exception {
        write("IMG_1.jpg", "photo");
        VerifiedMediaReclaimer.Receipt receipt = receipt("receipt-1", "IMG_1.jpg", "live");
        persistJournal(receipt, "verified");
        assertTrue(new File(root, receipt.name).delete());
        assertTrue(reclaimer.reclaim(receipt).alreadyReclaimed);
    }

    @Test public void modifiedQuarantineRemainsHeldWithoutReplacingNewSource() throws Exception {
        File source = write("IMG_1.jpg", "photo");
        VerifiedMediaReclaimer.Receipt receipt = receipt("receipt-1", "IMG_1.jpg", "live");
        persistJournal(receipt, "prepared");
        File payload = new File(journalDirectory(), "receipt-1.payload");
        Files.move(source.toPath(), payload.toPath());
        Files.write(payload.toPath(), "changed bytes".getBytes(StandardCharsets.UTF_8));
        payload.setLastModified(10_000L);
        write("IMG_1.jpg", "new capture");
        expectConflict(() -> reclaimer.reclaim(receipt));
        assertTrue(source.isFile());
        assertEquals("new capture", new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8));
        assertEquals("changed bytes", new String(Files.readAllBytes(payload.toPath()), StandardCharsets.UTF_8));
        assertEquals(1, reclaimer.recover());
    }

    @Test public void mutationBetweenFirstHashAndRenameCannotDeleteChangedPayload() throws Exception {
        File source = write("IMG_1.jpg", "photo");
        VerifiedMediaReclaimer.Receipt receipt = receipt("receipt-1", "IMG_1.jpg", "live");
        AtomicInteger calls = new AtomicInteger();
        reclaimer = new VerifiedMediaReclaimer(root, name -> {
            if (calls.incrementAndGet() == 2) {
                try { Files.write(source.toPath(), "other".getBytes(StandardCharsets.UTF_8)); source.setLastModified(10_000); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
            return false;
        }, () -> 100_000L, directory -> {});
        expectConflict(() -> reclaimer.reclaim(receipt));
        assertFalse(source.exists());
        File payload = new File(journalDirectory(), receipt.id + ".payload");
        assertEquals("other", new String(Files.readAllBytes(payload.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void manifestRecoveryFindsInterruptedReceiptWithoutPhoneRememberingIt() throws Exception {
        File source = write("IMG_1.jpg", "photo");
        VerifiedMediaReclaimer.Receipt receipt = receipt("receipt-1", "IMG_1.jpg", "live");
        persistJournal(receipt, "prepared");
        File payload = new File(journalDirectory(), receipt.id + ".payload");
        Files.move(source.toPath(), payload.toPath());
        reclaimer = new VerifiedMediaReclaimer(root, name -> false, () -> 100_000L, directory -> {});
        assertEquals(0, reclaimer.recover());
        assertTrue(reclaimer.list().isEmpty());
        assertFalse(payload.exists());
        assertTrue(reclaimer.reclaim(receipt).alreadyReclaimed);
    }

    @Test public void orphanQuarantineCannotBeAssignedToAnArbitraryReceipt() throws Exception {
        File source = write("IMG_1.jpg", "photo");
        VerifiedMediaReclaimer.Receipt receipt = receipt("receipt-1", "IMG_1.jpg", "live");
        journalDirectory().mkdirs();
        File payload = new File(journalDirectory(), receipt.id + ".payload");
        Files.move(source.toPath(), payload.toPath());
        expectConflict(() -> reclaimer.reclaim(receipt));
        assertFalse(source.exists());
        assertTrue(payload.exists());
        assertEquals(1, reclaimer.recover());
    }

    @Test public void failedDirectoryDurabilityBarrierCannotMoveOrDeleteOriginal() throws Exception {
        File source = write("IMG_1.jpg", "photo");
        VerifiedMediaReclaimer.Receipt receipt = receipt("receipt-1", "IMG_1.jpg", "live");
        reclaimer = new VerifiedMediaReclaimer(root, name -> false, () -> 100_000L,
                directory -> { throw new java.io.IOException("directory sync failed"); });
        try { reclaimer.reclaim(receipt); fail(); }
        catch (java.io.IOException expected) {}
        assertTrue(source.isFile());
    }

    @Test public void symbolicLinksCannotExposeOrReclaimOutsideMedia() throws Exception {
        File outside = temporary.newFile("outside.jpg");
        Files.write(outside.toPath(), "unrelated".getBytes(StandardCharsets.UTF_8));
        File alias = new File(root, "IMG_alias.jpg");
        try { Files.createSymbolicLink(alias.toPath(), outside.toPath()); }
        catch (java.io.IOException | UnsupportedOperationException error) {
            org.junit.Assume.assumeNoException("Host cannot create test symbolic links", error);
        }
        try { reclaimer.inspect(alias.getName(), "live"); fail(); }
        catch (IllegalArgumentException expected) {}
        assertTrue(outside.isFile());
    }

    private File journalDirectory() {
        return new File(root, "_gallery_metadata/" + AsgConstants.LAB_SYNC_RECEIPT_DIRECTORY);
    }

    private void persistJournal(VerifiedMediaReclaimer.Receipt receipt, String state) throws Exception {
        File directory = journalDirectory();
        directory.mkdirs();
        Properties values = receipt.properties();
        values.setProperty("state", state);
        try (FileOutputStream output = new FileOutputStream(new File(directory, receipt.id + ".properties"))) {
            values.store(output, "test interrupted state");
        }
    }

    private File write(String path, String content) throws Exception {
        File file = new File(root, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(10_000L));
        return file;
    }

    private VerifiedMediaReclaimer.Receipt receipt(String id, String path, String location) throws Exception {
        VerifiedMediaReclaimer.Entry entry = reclaimer.inspect(path, location);
        return new VerifiedMediaReclaimer.Receipt(id, path, location, entry.size, entry.generation, reclaimer.hash(entry));
    }

    private interface Checked { void run() throws Exception; }
    private static void expectConflict(Checked action) throws Exception {
        try { action.run(); fail("Expected source-protecting conflict"); }
        catch (VerifiedMediaReclaimer.Conflict expected) {}
    }
}
