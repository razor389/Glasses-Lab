package com.mentra.asg_client.io.server.services;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;

/** Persist receipt/quarantine namespace changes before claiming permanent reclamation succeeded. */
public final class AndroidDirectorySync {
    private AndroidDirectorySync() {}

    public static void sync(File directory) throws IOException {
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) {
                throw new IOException("Receipt persistence target is not a directory");
            }
            Os.fsync(descriptor);
        } catch (ErrnoException error) {
            throw new IOException("Directory persistence failed; source cleanup must be retried", error);
        } finally {
            if (descriptor != null) {
                try { Os.close(descriptor); }
                catch (ErrnoException error) { throw new IOException("Could not close directory handle", error); }
            }
        }
    }
}
