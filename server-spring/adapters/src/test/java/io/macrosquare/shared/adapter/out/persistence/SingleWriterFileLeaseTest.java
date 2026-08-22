package io.macrosquare.shared.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SingleWriterFileLeaseTest {

    @TempDir
    Path directory;

    @Test
    void rejectsASecondWriterAndReleasesTheLeaseOnClose() {
        try (var first = new SingleWriterFileLease(directory)) {
            assertThrows(IllegalStateException.class, () -> new SingleWriterFileLease(directory));
        }

        assertDoesNotThrow(() -> {
            try (var ignored = new SingleWriterFileLease(directory)) {
                // lease is reusable after orderly shutdown
            }
        });
    }
}
