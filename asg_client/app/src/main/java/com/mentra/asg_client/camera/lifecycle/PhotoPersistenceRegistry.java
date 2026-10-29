package com.mentra.asg_client.camera.lifecycle;

import com.mentra.asg_client.AsgConstants;
import com.mentra.asg_client.BuildConfig;
import com.mentra.asg_client.utils.GallerySyncFilter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-capture source-deletion barrier, including deferred writes and interrupted persistence. */
public final class PhotoPersistenceRegistry {
    private static final Map<String, State> active = new HashMap<>();

    private PhotoPersistenceRegistry() {}

    private static final class State {
        final String captureId;
        final File marker;
        int leases;
        boolean failed;
        boolean markerStarted;

        State(String captureId, File marker) {
            this.captureId = captureId;
            this.marker = marker;
        }
    }

    /** A successful owner must call complete before close; any failed phase retains the marker. */
    public static final class Lease implements AutoCloseable {
        private final State state;
        private boolean completed;
        private boolean closed;

        private Lease(State state) { this.state = state; }

        /** All mutations owned by this phase, including metadata, have finished successfully. */
        public void complete() {
            synchronized (PhotoPersistenceRegistry.class) {
                if (state == null) return;
                if (!closed) completed = true;
            }
        }

        /** Idempotent; releasing one phase cannot release another writer's protection. */
        @Override public void close() {
            synchronized (PhotoPersistenceRegistry.class) {
                if (closed) return;
                closed = true;
                if (state == null) return;
                state.failed |= !completed;
                state.leases--;
                if (state.leases == 0) {
                    active.remove(state.marker.getPath());
                    if (!state.failed && state.markerStarted && state.marker.exists() && !state.marker.delete()) {
                        // Retain the barrier if cleanup fails; never crash a completed camera job.
                        state.failed = true;
                    }
                }
            }
        }
    }

    /** Acquire before exposing a final JPEG name or queuing a task that can write that capture. */
    public static Lease begin(String absoluteFilePath) { return acquire(absoluteFilePath, true); }

    /** Personal-fork writers use durable markers; stock camera behavior remains unchanged. */
    public static Lease beginForBuild(String absoluteFilePath) {
        return personalBuild() ? begin(absoluteFilePath) : new Lease(null);
    }

    /** Reserve an entire burst before exposure; the first actual writer creates its disk marker. */
    public static Lease beginCaptureForBuild(String absoluteFilePath) {
        return personalBuild() ? acquire(absoluteFilePath, false) : new Lease(null);
    }

    /** Join a caller's fork capture; standalone metadata/scratch-file helpers stay unchanged. */
    static synchronized Lease joinForBuild(String absoluteFilePath) {
        if (!personalBuild()) return new Lease(null);
        try {
            File file = new File(absoluteFilePath).getCanonicalFile();
            if (!active.containsKey(marker(file).getPath())) return new Lease(null);
            return acquire(absoluteFilePath, true);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot join photo persistence barrier", error);
        }
    }

    private static synchronized Lease acquire(String absoluteFilePath, boolean writing) {
        try {
            File file = new File(absoluteFilePath).getCanonicalFile();
            File marker = marker(file);
            State state = active.get(marker.getPath());
            if (state == null) {
                state = new State(captureId(file), marker);
                state.failed = marker.exists();
            }
            if (writing && !state.markerStarted) {
                File parent = marker.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create photo storage");
                // A process death leaves this marker behind. Neither age nor a later successful
                // phase proves that all earlier capture members were completed.
                boolean created = marker.createNewFile();
                if (created) {
                    try (FileOutputStream output = new FileOutputStream(marker)) {
                        output.write(("Photo persistence pending: " + file.getName() + "\n").getBytes(StandardCharsets.UTF_8));
                        output.getFD().sync();
                    }
                }
                state.failed |= !created;
                state.markerStarted = true;
            }
            active.put(marker.getPath(), state);
            state.leases++;
            return new Lease(state);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot establish photo persistence barrier", error);
        }
    }

    /** Uses the original logical gallery name, so it remains valid while a file is quarantined. */
    public static synchronized boolean isCaptureActive(String relativeMediaName) {
        String id = GallerySyncFilter.deriveCaptureId(relativeMediaName);
        for (State state : active.values()) if (state.captureId.equals(id)) return true;
        return false;
    }

    /** Checks durable incomplete-write state after process restart; errors fail closed. */
    public static boolean hasPendingWrite(File mediaFile) {
        try { return marker(mediaFile.getCanonicalFile()).exists(); }
        catch (IOException | RuntimeException error) { return true; }
    }

    /**
     * Registers protection before executor handoff. Cancellation before start releases the lease;
     * cancellation after start keeps protection until the callable actually exits.
     */
    public static Future<Boolean> submit(Executor executor, String filePath, Callable<Boolean> writer) {
        Lease lease = begin(filePath);
        AtomicBoolean started = new AtomicBoolean();
        Object startGate = new Object();
        FutureTask<Boolean> task = new FutureTask<Boolean>(() -> {
            boolean success = Boolean.TRUE.equals(writer.call());
            if (success) lease.complete();
            return success;
        }) {
            @Override public void run() {
                synchronized (startGate) { started.set(true); }
                try { super.run(); }
                finally { lease.close(); }
            }
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                synchronized (startGate) {
                    // CapturedPhoto.cancelPersistence(false) promises that successful cancellation
                    // means no write is running. FutureTask's default alone does not promise this.
                    if (!mayInterruptIfRunning && started.get()) return false;
                    return super.cancel(mayInterruptIfRunning);
                }
            }
            @Override protected void done() {
                if (isCancelled() && !started.get()) lease.close();
            }
        };
        try { executor.execute(task); }
        catch (RuntimeException error) { lease.close(); throw error; }
        return task;
    }

    /** Keep the stock deferred executor path unchanged outside the separately installed fork. */
    public static Future<Boolean> submitForBuild(ExecutorService executor, String filePath, Callable<Boolean> writer) {
        return personalBuild() ? submit(executor, filePath, writer) : executor.submit(writer);
    }

    private static boolean personalBuild() {
        return AsgConstants.PERSONAL_ASG_PACKAGE.equals(BuildConfig.APPLICATION_ID);
    }

    private static String captureId(File file) {
        File parent = file.getParentFile();
        if (parent != null && (parent.getName().startsWith("IMG_") || parent.getName().startsWith("VID_"))) {
            return parent.getName();
        }
        return GallerySyncFilter.deriveCaptureId(file.getName());
    }

    private static File marker(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent == null) throw new IOException("Photo output has no parent");
        String id = captureId(file);
        String name = parent.getName().equals(id) ? AsgConstants.PHOTO_PERSISTENCE_PENDING_SUFFIX
                : "." + id + AsgConstants.PHOTO_PERSISTENCE_PENDING_SUFFIX;
        return new File(parent, name);
    }
}
