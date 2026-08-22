package io.macrosquare.market.adapter.out.persistence;

import io.macrosquare.market.application.port.out.MarketObservationPersistenceException;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Spring-owned atomic persistence for latest observations and bounded histories. */
public final class FileMarketObservationRepository implements MarketObservationRepository {

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Path directory;
    private final Path historyDirectory;
    private final int maximumHistoryPoints;
    private final long maximumFileBytes;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public FileMarketObservationRepository(
            ObjectMapper objectMapper,
            Clock clock,
            Path directory,
            int maximumHistoryPoints,
            long maximumFileBytes
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.directory = absolute(directory);
        this.historyDirectory = this.directory.resolve("history").normalize();
        if (maximumHistoryPoints <= 0) throw new IllegalArgumentException("maximumHistoryPoints must be positive");
        if (maximumFileBytes <= 0) throw new IllegalArgumentException("maximumFileBytes must be positive");
        this.maximumHistoryPoints = maximumHistoryPoints;
        this.maximumFileBytes = maximumFileBytes;
    }

    @Override
    public int save(List<MarketObservation> observations) {
        if (observations == null || observations.isEmpty()) return 0;
        var grouped = new EnumMap<MarketDataSource, List<MarketObservation>>(MarketDataSource.class);
        observations.forEach(item -> grouped.computeIfAbsent(item.source(), ignored -> new ArrayList<>()).add(item));
        var persisted = 0;
        for (var entry : grouped.entrySet()) {
            var key = entry.getKey().name();
            var lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock(true));
            lock.lock();
            try {
                try {
                    Files.createDirectories(directory);
                    Files.createDirectories(historyDirectory);
                    var latest = new LinkedHashMap<String, MarketObservation>();
                    loadLatest(entry.getKey()).forEach(item -> latest.put(item.key(), item));
                    var byKey = new LinkedHashMap<String, List<MarketObservation>>();
                    for (var observation : entry.getValue()) {
                        var current = latest.get(observation.key());
                        if (current == null || !observation.observationDate().isBefore(current.observationDate())) {
                            latest.put(observation.key(), observation);
                        }
                        byKey.computeIfAbsent(observation.key(), ignored -> new ArrayList<>()).add(observation);
                    }
                    for (var values : byKey.values()) saveHistory(values);
                    writeLatest(entry.getKey(), latest.values().stream()
                            .sorted(Comparator.comparing(MarketObservation::key)).toList());
                    persisted += entry.getValue().size();
                } catch (Exception error) {
                    throw new MarketObservationPersistenceException(
                            "Unable to persist " + entry.getKey() + " market observations", error);
                }
            } finally {
                lock.unlock();
            }
        }
        return persisted;
    }

    @Override
    public List<MarketObservation> loadLatest(MarketDataSource source) {
        var path = latestPath(source);
        if (!Files.isRegularFile(path)) return List.of();
        try {
            var root = readTree(path);
            var array = root.get("observations");
            if (array == null || !array.isArray()) {
                throw new IllegalArgumentException("latest observation file has no observations array");
            }
            var result = new ArrayList<MarketObservation>(array.size());
            for (var node : array) result.add(observation(node, source));
            return List.copyOf(result);
        } catch (Exception error) {
            throw new MarketObservationPersistenceException(
                    "Unable to read latest " + source + " market observations", error);
        }
    }

    @Override
    public List<MarketObservation> loadHistory(MarketDataSource source, String key) {
        Objects.requireNonNull(source, "source");
        var path = historyPath(source, key);
        if (!Files.isRegularFile(path)) return List.of();
        var lock = locks.computeIfAbsent(source.name(), ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            try {
                var root = readTree(path);
                if (!root.isArray()) throw new IllegalArgumentException("market history file must be an array");
                var result = new ArrayList<MarketObservation>(root.size());
                for (var node : root) {
                    var item = observation(node, source);
                    if (!item.key().equals(key)) throw new IllegalArgumentException("market history identity mismatch");
                    result.add(item);
                }
                return result.stream().sorted(Comparator.comparing(MarketObservation::observationDate)).toList();
            } catch (Exception error) {
                throw new MarketObservationPersistenceException(
                        "Unable to read " + source + " market history", error);
            }
        } finally {
            lock.unlock();
        }
    }

    private void saveHistory(List<MarketObservation> observations) throws IOException {
        if (observations.isEmpty()) return;
        var first = observations.getFirst();
        var path = historyPath(first.source(), first.key());
        var byDate = new LinkedHashMap<LocalDate, MarketObservation>();
        if (Files.isRegularFile(path)) {
            var root = readTree(path);
            if (!root.isArray()) throw new IllegalArgumentException("market history file must be an array");
            for (var node : root) {
                var item = observation(node, first.source());
                if (!item.key().equals(first.key())) {
                    throw new IllegalArgumentException("market history identity mismatch");
                }
                byDate.put(item.observationDate(), item);
            }
        }
        for (var observation : observations) {
            if (observation.source() != first.source() || !observation.key().equals(first.key())) {
                throw new IllegalArgumentException("history batch identity mismatch");
            }
            byDate.put(observation.observationDate(), observation);
        }
        var retained = byDate.values().stream()
                .sorted(Comparator.comparing(MarketObservation::observationDate))
                .skip(Math.max(0, byDate.size() - maximumHistoryPoints))
                .toList();
        var array = objectMapper.createArrayNode();
        retained.forEach(item -> array.add(observationNode(item)));
        atomicWrite(path, objectMapper.writeValueAsBytes(array));
    }

    private void writeLatest(MarketDataSource source, List<MarketObservation> observations) throws IOException {
        var root = objectMapper.createObjectNode();
        root.put("version", 1);
        root.put("updatedAt", clock.instant().toString());
        var array = root.putArray("observations");
        observations.forEach(item -> array.add(observationNode(item)));
        atomicWrite(latestPath(source), objectMapper.writeValueAsBytes(root));
    }

    private JsonNode observationNode(MarketObservation item) {
        var node = objectMapper.createObjectNode();
        node.put("key", item.key());
        node.put("providerCode", item.providerCode());
        node.put("value", item.value());
        node.put("observationDate", item.observationDate().toString());
        node.put("source", item.source().name());
        return node;
    }

    private static MarketObservation observation(JsonNode node, MarketDataSource expectedSource) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("observation must be an object");
        var key = text(node, "key");
        var providerCode = text(node, "providerCode");
        var valueNode = node.get("value");
        if (valueNode == null || !valueNode.isNumber()) throw new IllegalArgumentException("value must be numeric");
        var date = LocalDate.parse(text(node, "observationDate"));
        var source = MarketDataSource.valueOf(text(node, "source"));
        if (source != expectedSource) throw new IllegalArgumentException("observation source mismatch");
        return new MarketObservation(key, providerCode, valueNode.asDouble(), date, source);
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank text");
        }
        return value.stringValue();
    }

    private JsonNode readTree(Path path) throws IOException {
        var size = Files.size(path);
        if (size > maximumFileBytes) throw new IllegalArgumentException("market observation file exceeds its bound");
        var bytes = Files.readAllBytes(path);
        if (bytes.length > maximumFileBytes) throw new IllegalArgumentException("market observation file exceeds its bound");
        var root = objectMapper.readTree(bytes);
        if (root == null) throw new IllegalArgumentException("market observation file is empty");
        return root;
    }

    private void atomicWrite(Path target, byte[] bytes) throws IOException {
        if (bytes.length > maximumFileBytes) throw new IllegalArgumentException("market observation file exceeds its bound");
        Files.createDirectories(target.getParent());
        var temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (var channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            )) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectory(target.getParent());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void forceDirectory(Path directory) {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception ignored) {
            // The file itself is already fsynced; some filesystems do not permit directory channels.
        }
    }

    private Path latestPath(MarketDataSource source) {
        return directory.resolve(source.name().toLowerCase(Locale.ROOT) + "-latest.json").normalize();
    }

    private Path historyPath(MarketDataSource source, String observationKey) {
        if (observationKey == null) throw new IllegalArgumentException("market observation key is invalid");
        var key = observationKey.toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9_.=-]+")) throw new IllegalArgumentException("market observation key is invalid");
        var file = source.name().toLowerCase(Locale.ROOT) + "-" + key + ".json";
        var path = historyDirectory.resolve(file).normalize();
        if (!path.startsWith(historyDirectory)) throw new IllegalArgumentException("market history path escapes directory");
        return path;
    }

    private static Path absolute(Path path) {
        Objects.requireNonNull(path, "directory");
        if (!path.isAbsolute()) throw new IllegalArgumentException("directory must be absolute");
        return path.normalize();
    }
}
