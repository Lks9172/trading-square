package io.macrosquare.shared.adapter.out.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Process lease for file-backed command/read stores.
 *
 * <p>The current storage model deliberately supports one writer. Failing startup
 * is safer than allowing a second JVM to rely on process-local locks and corrupt
 * the shared volume.</p>
 */
public final class SingleWriterFileLease implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    public SingleWriterFileLease(Path directory) {
        var normalized = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        try {
            Files.createDirectories(normalized);
            this.channel = FileChannel.open(
                    normalized.resolve(".macrosquare-writer.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );
            try {
                this.lock = channel.tryLock();
            } catch (OverlappingFileLockException error) {
                channel.close();
                throw new IllegalStateException("Spring data directory already has an active writer", error);
            }
            if (lock == null) {
                channel.close();
                throw new IllegalStateException("Spring data directory already has an active writer");
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to acquire Spring data writer lease", error);
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // Channel close below is the final lease release mechanism.
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Shutdown is already in progress; no recovery action remains.
        }
    }
}
