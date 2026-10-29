package com.mentra.asg_client.camera.lifecycle;

import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PhotoPersistenceRegistryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void captureStaysBlockedAcrossDeferredPersistenceAndMetadata() throws Exception {
        File photo = photo("IMG_deferred/base.jpg");
        try (PhotoPersistenceRegistry.Lease capture = PhotoPersistenceRegistry.begin(photo.getPath())) {
            PhotoPersistenceRegistry.Lease deferred = PhotoPersistenceRegistry.begin(photo.getPath());
            assertTrue(PhotoPersistenceRegistry.isCaptureActive("IMG_deferred/base.jpg"));
            assertTrue(PhotoPersistenceRegistry.hasPendingWrite(photo));
            capture.complete();
            capture.close(); // RAM callback completed, but the file writer still owns this capture.
            assertTrue(PhotoPersistenceRegistry.isCaptureActive("IMG_deferred/base.jpg"));
            assertTrue(PhotoPersistenceRegistry.hasPendingWrite(photo));
            deferred.complete();
            deferred.close();
            deferred.close();
            assertFalse(PhotoPersistenceRegistry.isCaptureActive("IMG_deferred/base.jpg"));
            assertFalse(PhotoPersistenceRegistry.hasPendingWrite(photo));
        }
    }

    @Test public void failedPhaseLeavesPersistentMarkerWithoutBlockingOtherCaptures() throws Exception {
        File photo = photo("IMG_failed/base.jpg");
        try (PhotoPersistenceRegistry.Lease capture = PhotoPersistenceRegistry.begin(photo.getPath())) {
            try (PhotoPersistenceRegistry.Lease writer = PhotoPersistenceRegistry.begin(photo.getPath())) {
                // Simulated write failure: writer never calls complete.
            }
            capture.complete();
        }
        assertFalse(PhotoPersistenceRegistry.isCaptureActive("IMG_failed/base.jpg"));
        assertTrue(PhotoPersistenceRegistry.hasPendingWrite(photo));
        assertFalse(PhotoPersistenceRegistry.isCaptureActive("IMG_unrelated/base.jpg"));
        assertFalse(PhotoPersistenceRegistry.hasPendingWrite(photo("IMG_unrelated/base.jpg")));
        // A new phase after process-state loss cannot erase evidence of an earlier failed write.
        try (PhotoPersistenceRegistry.Lease later = PhotoPersistenceRegistry.begin(photo.getPath())) {
            later.complete();
        }
        assertTrue(PhotoPersistenceRegistry.hasPendingWrite(photo));
    }

    @Test public void legacyBracketAndModernCaptureFilesShareCorrectBarrier() throws Exception {
        File legacy = photo("IMG_legacy_ev0.jpg");
        try (PhotoPersistenceRegistry.Lease lease = PhotoPersistenceRegistry.begin(legacy.getPath())) {
            assertTrue(PhotoPersistenceRegistry.isCaptureActive("IMG_legacy_ev-2.jpg"));
            assertTrue(PhotoPersistenceRegistry.hasPendingWrite(photo("IMG_legacy.jpg")));
            assertFalse(PhotoPersistenceRegistry.hasPendingWrite(photo("IMG_legacy2.jpg")));
            lease.complete();
        }
        assertFalse(PhotoPersistenceRegistry.hasPendingWrite(legacy));
    }

    @Test public void queuedCancellationNeverRunsWriterAndReleasesActiveLease() throws Exception {
        File photo = photo("IMG_cancelled/base.jpg");
        List<Runnable> queue = new ArrayList<>();
        Future<Boolean> write = PhotoPersistenceRegistry.submit(queue::add, photo.getPath(), () -> {
            fail("Cancelled writer ran");
            return true;
        });
        assertTrue(PhotoPersistenceRegistry.isCaptureActive("IMG_cancelled/base.jpg"));
        assertTrue(write.cancel(false));
        assertFalse(PhotoPersistenceRegistry.isCaptureActive("IMG_cancelled/base.jpg"));
        queue.get(0).run();
        assertTrue(PhotoPersistenceRegistry.hasPendingWrite(photo));
    }

    @Test public void runningWriterCannotLoseBarrierToNonInterruptingCancellation() throws Exception {
        File photo = photo("IMG_running/base.jpg");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> write = PhotoPersistenceRegistry.submit(worker, photo.getPath(), () -> {
                started.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
                return true;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(write.cancel(false));
            assertTrue(PhotoPersistenceRegistry.isCaptureActive("IMG_running/base.jpg"));
            finish.countDown();
            assertTrue(write.get(5, TimeUnit.SECONDS));
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(PhotoPersistenceRegistry.isCaptureActive("IMG_running/base.jpg"));
            assertFalse(PhotoPersistenceRegistry.hasPendingWrite(photo));
        } finally { finish.countDown(); worker.shutdownNow(); }
    }

    @Test public void executorRejectionRetainsMarkerAndReleasesActiveLease() throws Exception {
        File photo = photo("IMG_rejected/base.jpg");
        try {
            PhotoPersistenceRegistry.submit(task -> { throw new IllegalStateException("rejected"); }, photo.getPath(), () -> true);
            fail("Expected rejection");
        } catch (IllegalStateException expected) {}
        assertFalse(PhotoPersistenceRegistry.isCaptureActive("IMG_rejected/base.jpg"));
        assertTrue(PhotoPersistenceRegistry.hasPendingWrite(photo));
    }

    private File photo(String relativePath) { return new File(temporary.getRoot(), relativePath); }
}
