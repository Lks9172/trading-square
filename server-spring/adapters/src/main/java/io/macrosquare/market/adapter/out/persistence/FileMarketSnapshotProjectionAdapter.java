package io.macrosquare.market.adapter.out.persistence;

import io.macrosquare.market.adapter.out.json.MarketReadJsonMapper;
import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.BooleanValue;
import io.macrosquare.market.application.model.MarketReadModels.Document;
import io.macrosquare.market.application.model.MarketReadModels.NullValue;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;
import io.macrosquare.market.application.port.out.MarketReadUnavailableException;
import io.macrosquare.market.application.port.out.SaveMarketSnapshotProjectionPort;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Crash-safe Spring-owned snapshot store with a strictly read-only first-start seed. */
public final class FileMarketSnapshotProjectionAdapter
        implements LoadMarketSnapshotProjectionPort, SaveMarketSnapshotProjectionPort {

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Path snapshotFile;
    private final Path seedFile;
    private final long maximumFileBytes;
    private final ReentrantLock lock = new ReentrantLock(true);

    public FileMarketSnapshotProjectionAdapter(
            ObjectMapper objectMapper,
            Clock clock,
            Path snapshotFile,
            Path seedFile,
            long maximumFileBytes
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.snapshotFile = absolute(snapshotFile, "snapshotFile");
        this.seedFile = absolute(seedFile, "seedFile");
        if (maximumFileBytes <= 0) throw new IllegalArgumentException("maximumFileBytes must be positive");
        this.maximumFileBytes = maximumFileBytes;
    }

    @Override
    public Document loadCurrentOrSeed() {
        lock.lock();
        try {
            var selected = Files.isRegularFile(snapshotFile) ? snapshotFile : seedFile;
            try {
                var bytes = boundedRead(selected);
                var root = objectMapper.readTree(bytes);
                var value = root != null && root.isObject() && root.has("value") ? root.get("value") : root;
                return MarketReadJsonMapper.mapSnapshot(value);
            } catch (MarketReadUnavailableException error) {
                throw error;
            } catch (Exception error) {
                throw new MarketReadUnavailableException("Unable to load the Spring market snapshot projection", error);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void save(Document snapshot) {
        Objects.requireNonNull(snapshot);
        lock.lock();
        try {
            try {
                var envelope = objectMapper.createObjectNode();
                envelope.put("key", "latest-system-snapshot-default-v1");
                envelope.put("updatedAt", clock.instant().toString());
                envelope.set("value", objectMapper.valueToTree(plain(snapshot.root())));
                var bytes = objectMapper.writeValueAsBytes(envelope);
                atomicWrite(bytes);
            } catch (Exception error) {
                throw new MarketReadUnavailableException("Unable to persist the Spring market snapshot projection", error);
            }
        } finally {
            lock.unlock();
        }
    }

    private byte[] boundedRead(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("snapshot projection is unavailable");
        var size = Files.size(path);
        if (size <= 0 || size > maximumFileBytes) throw new IllegalArgumentException("snapshot exceeds its bound");
        var bytes = Files.readAllBytes(path);
        if (bytes.length > maximumFileBytes) throw new IllegalArgumentException("snapshot exceeds its bound");
        return bytes;
    }

    private void atomicWrite(byte[] bytes) throws IOException {
        if (bytes.length > maximumFileBytes) throw new IllegalArgumentException("snapshot exceeds its bound");
        Files.createDirectories(snapshotFile.getParent());
        var temporary = snapshotFile.resolveSibling(snapshotFile.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, snapshotFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, snapshotFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Object plain(StructuredValue value) {
        return switch (value) {
            case NullValue ignored -> null;
            case TextValue text -> text.value();
            case NumberValue number -> number.value();
            case BooleanValue bool -> bool.value();
            case ArrayValue array -> {
                var values = new ArrayList<>(array.values().size());
                array.values().forEach(item -> values.add(plain(item)));
                yield values;
            }
            case ObjectValue object -> {
                var fields = new LinkedHashMap<String, Object>(object.fields().size());
                object.fields().forEach((key, item) -> fields.put(key, plain(item)));
                yield fields;
            }
        };
    }

    private static Path absolute(Path path, String field) {
        Objects.requireNonNull(path, field);
        if (!path.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
        return path.normalize();
    }
}
