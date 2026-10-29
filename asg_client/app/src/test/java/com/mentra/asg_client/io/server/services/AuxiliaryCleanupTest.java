package com.mentra.asg_client.io.server.services;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AuxiliaryCleanupTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File root;
    private VerifiedMediaReclaimer media;

    @Before public void setup() throws Exception {
        root = temporary.newFolder("gallery");
        media = new VerifiedMediaReclaimer(root, name -> false, () -> 100_000L, directory -> {});
    }

    @Test public void deletesOnlyConfirmedExtrasAndKeepsReceiptsAndUnsyncedMedia() throws Exception {
        completedVideo("VID_done");
        File unsynced = write("IMG_unsynced/base.jpg", "original");
        File metadata = write("IMG_unsynced/imu.json", "samples");
        VerifiedMediaReclaimer.AuxiliaryCleanup preview = media.previewAuxiliaryCleanup(() -> false);
        assertEquals(2, preview.entries.size());
        assertEquals(1, preview.retainedFiles);
        VerifiedMediaReclaimer.AuxiliaryCleanup deleted = media.purgeAuxiliaries(preview.id, () -> false);
        assertEquals(preview.bytes, deleted.bytes);
        assertFalse(new File(root, "VID_done").exists());
        assertTrue(unsynced.isFile());
        assertTrue(metadata.isFile());
        assertTrue(new File(root, "_gallery_metadata/verified_receipts/VID_done.properties").isFile());
        assertEquals(0, media.previewAuxiliaryCleanup(() -> false).entries.size());
    }

    @Test public void pendingPartialUnknownAndUnsyncedCapturesAreNeverPurged() throws Exception {
        completedVideo("VID_pending");
        completedVideo("VID_partial");
        completedVideo("VID_raw");
        completedVideo("VID_replaced");
        write("VID_pending/.photo-persistence.pending", "unfinished");
        write("VID_partial/base.mp4.partial", "recoverable video");
        write("VID_raw/frame.dng", "raw photo");
        write("VID_replaced/base.mp4", "new original");
        write("VID_no_receipt/imu.json", "original samples");
        assertTrue(media.previewAuxiliaryCleanup(() -> false).entries.isEmpty());
        for (String capture : new String[] {"VID_pending", "VID_partial", "VID_raw", "VID_replaced", "VID_no_receipt"}) {
            assertTrue(new File(root, capture + "/imu.json").exists());
        }
    }

    @Test public void changedBytesWithSameSizeAndTimestampInvalidateConfirmation() throws Exception {
        completedVideo("VID_done");
        String id = media.previewAuxiliaryCleanup(() -> false).id;
        write("VID_done/imu.json", "changed"); // same length as "samples"
        conflict(() -> media.purgeAuxiliaries(id, () -> false));
        assertTrue(new File(root, "VID_done/thumb.jpg").exists());
        assertTrue(new File(root, "VID_done/imu.json").exists());
    }

    @Test public void newEligibleCaptureRequiresAnotherPreview() throws Exception {
        completedVideo("VID_first");
        String id = media.previewAuxiliaryCleanup(() -> false).id;
        completedVideo("VID_second");
        conflict(() -> media.purgeAuxiliaries(id, () -> false));
        assertEquals(4, media.previewAuxiliaryCleanup(() -> false).entries.size());
    }

    @Test public void activeCaptureBlocksPreviewAndCommitAndCanInterruptRemainingDeletes() throws Exception {
        completedVideo("VID_done");
        conflict(() -> media.previewAuxiliaryCleanup(() -> true));
        String id = media.previewAuxiliaryCleanup(() -> false).id;
        conflict(() -> media.purgeAuxiliaries(id, () -> true));
        File first = new File(root, "VID_done/imu.json");
        conflict(() -> media.purgeAuxiliaries(id, () -> !first.exists()));
        assertFalse(first.exists());
        assertTrue(new File(root, "VID_done/thumb.jpg").exists());
        assertEquals(1, media.previewAuxiliaryCleanup(() -> false).entries.size());
    }

    @Test public void cleanupFailureCanBeReviewedAndRetriedWithoutDeletingOtherFiles() throws Exception {
        completedVideo("VID_done");
        AtomicBoolean fail = new AtomicBoolean(true);
        media = new VerifiedMediaReclaimer(root, name -> false, () -> 100_000L, directory -> {
            if (fail.getAndSet(false)) throw new IOException("sync failed");
        });
        String id = media.previewAuxiliaryCleanup(() -> false).id;
        try { media.purgeAuxiliaries(id, () -> false); fail("Expected failure"); }
        catch (IOException expected) { assertEquals("sync failed", expected.getMessage()); }
        VerifiedMediaReclaimer.AuxiliaryCleanup remaining = media.previewAuxiliaryCleanup(() -> false);
        assertEquals(1, remaining.entries.size());
        media.purgeAuxiliaries(remaining.id, () -> false);
        assertFalse(new File(root, "VID_done").exists());
    }

    @Test public void perCaptureBarrierAndRecentWritesRemainProtected() throws Exception {
        completedVideo("VID_done");
        media = new VerifiedMediaReclaimer(root, name -> name.startsWith("VID_done/"), () -> 100_000L, directory -> {});
        assertTrue(media.previewAuxiliaryCleanup(() -> false).entries.isEmpty());
        media = new VerifiedMediaReclaimer(root, name -> false, () -> 11_000L, directory -> {});
        assertTrue(media.previewAuxiliaryCleanup(() -> false).entries.isEmpty());
    }

    @Test public void trashCleanupCannotTouchLiveReplacementOrUnverifiedTrash() throws Exception {
        write("VID_old/base.mp4", "video");
        write("VID_old/imu.json", "samples");
        new GalleryTrashManager(root).trashCapture("VID_old", "legacy-ack");
        VerifiedMediaReclaimer.Entry entry = media.inspect("VID_old/base.mp4", "trash");
        media.reclaim(new VerifiedMediaReclaimer.Receipt("old-trash", entry.name, "trash", entry.size,
                entry.generation, media.hash(entry)));
        File live = write("VID_old/imu.json", "new unsynced samples");
        File other = write("_gallery_trash/VID_other/imu.json", "unverified");
        VerifiedMediaReclaimer.AuxiliaryCleanup preview = media.previewAuxiliaryCleanup(() -> false);
        assertEquals(1, preview.entries.size());
        assertEquals("trash", preview.entries.get(0).location);
        media.purgeAuxiliaries(preview.id, () -> false);
        assertTrue(live.isFile());
        assertTrue(other.isFile());
        assertFalse(new File(root, "_gallery_trash/VID_old/imu.json").exists());
    }

    private void completedVideo(String name) throws Exception {
        write(name + "/base.mp4", "video");
        write(name + "/imu.json", "samples");
        write(name + "/thumb.jpg", "preview");
        VerifiedMediaReclaimer.Entry entry = media.inspect(name + "/base.mp4", "live");
        media.reclaim(new VerifiedMediaReclaimer.Receipt(name, entry.name, "live", entry.size,
                entry.generation, media.hash(entry)));
    }

    private File write(String name, String content) throws Exception {
        File file = new File(root, name);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(10_000L));
        return file;
    }

    private interface Operation { void run() throws Exception; }
    private static void conflict(Operation action) throws Exception {
        try { action.run(); fail("Expected conflict"); }
        catch (VerifiedMediaReclaimer.Conflict expected) { /* originals remain protected */ }
    }
}
