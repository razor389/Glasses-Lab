package com.mentra.asg_client.io.file.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.mentra.asg_client.AsgConstants;
import com.mentra.asg_client.io.file.core.FileManager.FileMetadata;
import com.mentra.asg_client.logging.Logger;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FileManagerCleanupTest {

    private File baseDirectory;
    private FileManagerImpl fileManager;
    private File packageDirectory;

    @Before
    public void setUp() throws Exception {
        baseDirectory = Files.createTempDirectory("file-manager-cleanup-test").toFile();
        fileManager = new FileManagerImpl(baseDirectory, mock(Logger.class));
        packageDirectory = fileManager.getDefaultMediaDirectory();
    }

    @After
    public void tearDown() throws Exception {
        deleteRecursively(baseDirectory);
    }

    @Test
    public void sdkPendingCleanupDoesNotTouchLiveOrGalleryManagedFiles() throws Exception {
        File live = write("IMG_live/base.jpg");
        File trash = write(FileManager.GALLERY_TRASH_DIR_NAME + "/IMG_trash/base.jpg");
        File metadata = write(FileManager.GALLERY_METADATA_DIR_NAME + "/ack.json");
        File pending = write(FileManager.SDK_PENDING_DIR_NAME + "/IMG_pending/base.jpg");

        age(live, trash, metadata, pending);

        assertEquals(
                1, fileManager.cleanupOldSdkPendingFiles(fileManager.getDefaultPackageName(), 1));
        assertFalse(pending.exists());
        assertTrue(live.isFile());
        assertTrue(trash.isFile());
        assertTrue(metadata.isFile());
    }

    @Test
    public void legacyCleanupNeverDescendsIntoTrashOrMetadata() throws Exception {
        File live = write("IMG_live/base.jpg");
        File trash = write(FileManager.GALLERY_TRASH_DIR_NAME + "/IMG_trash/base.jpg");
        File metadata = write(FileManager.GALLERY_METADATA_DIR_NAME + "/ack.json");

        age(live, trash, metadata);

        assertEquals(1, fileManager.cleanupOldFiles(fileManager.getDefaultPackageName(), 1));
        assertFalse(live.exists());
        assertTrue(trash.isFile());
        assertTrue(metadata.isFile());
    }

    @Test
    public void listingHidesOnlyVideoCaptureThumbnailSidecars() throws Exception {
        File video = write("VID_video/base.mp4");
        File sidecar = write("VID_video/" + AsgConstants.VIDEO_THUMBNAIL_SIDECAR_NAME);
        File mixedCaseSidecar = write("VID_video/THUMB.JPG");
        File regularThumb = write("documents/" + AsgConstants.VIDEO_THUMBNAIL_SIDECAR_NAME);

        List<FileMetadata> files = fileManager.listFiles(fileManager.getDefaultPackageName());

        assertTrue(
                files.stream()
                        .anyMatch(file -> file.getFilePath().equals(video.getAbsolutePath())));
        assertTrue(
                files.stream()
                        .anyMatch(
                                file -> file.getFilePath().equals(regularThumb.getAbsolutePath())));
        assertFalse(
                files.stream()
                        .anyMatch(file -> file.getFilePath().equals(sidecar.getAbsolutePath())));
        assertFalse(
                files.stream()
                        .anyMatch(
                                file ->
                                        file.getFilePath()
                                                .equals(mixedCaseSidecar.getAbsolutePath())));
    }

    @Test
    public void startupCleanupDoesNotTreatVideoThumbnailAsPrimaryMedia() throws Exception {
        File orphanBase = Files.createTempDirectory("file-manager-orphan-test").toFile();
        try {
            File capture =
                    new File(orphanBase, "com.mentra.asg_client.camera/VID_orphaned_thumbnail");
            assertTrue(capture.mkdirs());
            write(new File(capture, AsgConstants.VIDEO_THUMBNAIL_SIDECAR_NAME));

            new FileManagerImpl(orphanBase, mock(Logger.class), false);

            assertFalse(capture.exists());
        } finally {
            deleteRecursively(orphanBase);
        }
    }

    @Test
    public void personalStartupPreservesEveryNonemptyCaptureWithoutAPrimary() throws Exception {
        File[] retained = {
            write("IMG_imu_only/imu.json"),
            write("IMG_raw_only/base.dng"),
            write("IMG_other_raw/base.raw"),
            write("IMG_bracket_only/ev-2.jpg"),
            write("VID_partial_only/base.mp4.partial"),
            write("VID_corrupt_only/base.mp4"),
            write("VID_thumbnail_only/" + AsgConstants.VIDEO_THUMBNAIL_SIDECAR_NAME),
            write("IMG_marker_only/" + AsgConstants.PHOTO_PERSISTENCE_PENDING_SUFFIX),
            write("BUFFER_partial_only/unfinished.part")
        };
        age(retained);

        new FileManagerImpl(baseDirectory, mock(Logger.class), true);
        new FileManagerImpl(baseDirectory, mock(Logger.class), true);

        for (File file : retained) {
            assertTrue("Startup must preserve " + file, file.isFile());
            assertEquals("data", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void personalStartupRemovesOnlyEmptyCaptureDirectories() throws Exception {
        File empty = new File(packageDirectory, "IMG_empty");
        File emptyBuffer = new File(packageDirectory, "BUFFER_empty");
        File retained = write("VID_nonempty/imu.json");
        assertTrue(empty.mkdirs());
        assertTrue(emptyBuffer.mkdirs());

        new FileManagerImpl(baseDirectory, mock(Logger.class), true);

        assertFalse(empty.exists());
        assertFalse(emptyBuffer.exists());
        assertTrue(retained.isFile());
    }

    @Test
    public void defaultStartupPolicyMatchesApplicationPackage() throws Exception {
        File retained = write("IMG_default_policy/imu.json");

        new FileManagerImpl(baseDirectory, mock(Logger.class));

        assertEquals(AsgConstants.PERSONAL_ASG_PACKAGE.equals(com.mentra.asg_client.BuildConfig.APPLICATION_ID),
                retained.isFile());
    }

    private File write(String relativePath) throws Exception {
        File file = new File(packageDirectory, relativePath);
        assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory());
        return write(file);
    }

    private static File write(File file) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("data".getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static void age(File... files) {
        for (File file : files) {
            assertTrue(file.setLastModified(1));
        }
    }

    private static void deleteRecursively(File file) throws Exception {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(file.toPath());
    }
}
