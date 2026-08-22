package io.macrosquare.execution.adapter.out.persistence;

import io.macrosquare.execution.application.port.out.InvestmentExecutionPersistenceException;
import io.macrosquare.execution.application.port.out.InvestmentPlanRepository;
import io.macrosquare.execution.application.port.out.TradeLogRepository;
import io.macrosquare.execution.application.port.out.TrancheRepository;
import io.macrosquare.execution.domain.model.InvestmentHorizon;
import io.macrosquare.execution.domain.model.InvestmentPlan;
import io.macrosquare.execution.domain.model.TradeLogEntry;
import io.macrosquare.execution.domain.model.TradeLogKind;
import io.macrosquare.execution.domain.model.TradeLogValue;
import io.macrosquare.execution.domain.model.TrancheEntry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * Spring-owned file persistence with bounded legacy import and crash-safe replacement.
 * The legacy tree is never written and can be mounted read-only during cutover.
 */
public final class FileInvestmentExecutionAdapter
        implements InvestmentPlanRepository, TradeLogRepository, TrancheRepository {

    private static final int MAX_PLAN_BYTES = 256 * 1024;
    private static final int MAX_TRANCHE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TRADE_LOG_BYTES = 32 * 1024 * 1024;
    private static final int MAX_TRADE_LOG_LINE_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Path dataDirectory;
    private final Path legacyDataDirectory;
    private final boolean importLegacyOnFirstRead;
    private final Lock planLock = new ReentrantLock();
    private final Lock trancheLock = new ReentrantLock();
    private final Lock tradeLogLock = new ReentrantLock();

    public FileInvestmentExecutionAdapter(
            ObjectMapper objectMapper,
            Clock clock,
            Path dataDirectory,
            Path legacyDataDirectory,
            boolean importLegacyOnFirstRead
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.dataDirectory = absolute(dataDirectory, "dataDirectory");
        this.legacyDataDirectory = absolute(legacyDataDirectory, "legacyDataDirectory");
        this.importLegacyOnFirstRead = importLegacyOnFirstRead;
    }

    @Override
    public Optional<InvestmentPlan> load() {
        planLock.lock();
        try {
            importIfNecessary(planPath(), legacyPlanPath(), MAX_PLAN_BYTES);
            if (!Files.exists(planPath())) return Optional.empty();
            return Optional.of(readPlan(planPath()));
        } finally {
            planLock.unlock();
        }
    }

    @Override
    public InvestmentPlan save(InvestmentPlan plan) {
        Objects.requireNonNull(plan, "plan");
        planLock.lock();
        try {
            writeJsonAtomically(planPath(), planDocument(plan), MAX_PLAN_BYTES);
            return plan;
        } finally {
            planLock.unlock();
        }
    }

    @Override
    public PlanMutation updateAtomically(
            InvestmentPlan initialPlan,
            UnaryOperator<InvestmentPlan> mutation
    ) {
        Objects.requireNonNull(initialPlan, "initialPlan");
        Objects.requireNonNull(mutation, "mutation");
        planLock.lock();
        try {
            importIfNecessary(planPath(), legacyPlanPath(), MAX_PLAN_BYTES);
            var before = Files.exists(planPath()) ? readPlan(planPath()) : initialPlan;
            var after = Objects.requireNonNull(mutation.apply(before), "mutation result");
            writeJsonAtomically(planPath(), planDocument(after), MAX_PLAN_BYTES);
            return new PlanMutation(before, after);
        } finally {
            planLock.unlock();
        }
    }

    @Override
    public void append(TradeLogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        tradeLogLock.lock();
        try {
            importIfNecessary(tradeLogPath(), legacyTradeLogPath(), MAX_TRADE_LOG_BYTES);
            Files.createDirectories(tradeLogPath().getParent());
            var serialized = objectMapper.writeValueAsBytes(tradeLogDocument(entry));
            if (serialized.length > MAX_TRADE_LOG_LINE_BYTES) {
                throw new IllegalArgumentException("trade log entry is too large");
            }
            var currentSize = Files.exists(tradeLogPath()) ? Files.size(tradeLogPath()) : 0L;
            if (currentSize + serialized.length + 1L > MAX_TRADE_LOG_BYTES) {
                throw new InvestmentExecutionPersistenceException("Trade log reached its configured size bound");
            }
            try (var channel = FileChannel.open(
                    tradeLogPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            )) {
                var buffer = ByteBuffer.allocate(serialized.length + 1);
                buffer.put(serialized).put((byte) '\n').flip();
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            forceDirectory(tradeLogPath().getParent());
        } catch (InvestmentExecutionPersistenceException | IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw persistenceFailure("Unable to append the trade log", error);
        } finally {
            tradeLogLock.unlock();
        }
    }

    @Override
    public List<TradeLogEntry> recent(int limit) {
        if (limit <= 0) return List.of();
        tradeLogLock.lock();
        try {
            importIfNecessary(tradeLogPath(), legacyTradeLogPath(), MAX_TRADE_LOG_BYTES);
            if (!Files.exists(tradeLogPath())) return List.of();
            requireBoundedFile(tradeLogPath(), MAX_TRADE_LOG_BYTES);
            var lines = Files.readAllLines(tradeLogPath(), StandardCharsets.UTF_8);
            var start = Math.max(0, lines.size() - limit);
            var result = new ArrayList<TradeLogEntry>();
            for (var index = start; index < lines.size(); index++) {
                var line = lines.get(index);
                if (line.isBlank() || line.getBytes(StandardCharsets.UTF_8).length > MAX_TRADE_LOG_LINE_BYTES) continue;
                try {
                    result.add(parseTradeLog(objectMapper.readTree(line)));
                } catch (RuntimeException ignored) {
                    // Preserve the legacy contract: malformed historical lines are skipped.
                }
            }
            return List.copyOf(result);
        } catch (InvestmentExecutionPersistenceException error) {
            throw error;
        } catch (Exception error) {
            throw persistenceFailure("Unable to read the trade log", error);
        } finally {
            tradeLogLock.unlock();
        }
    }

    @Override
    public List<TrancheEntry> append(TrancheEntry entry) {
        Objects.requireNonNull(entry, "entry");
        trancheLock.lock();
        try {
            var entries = new ArrayList<>(readTranchesLocked());
            entries.add(entry);
            writeJsonAtomically(tranchePath(), trancheDocument(entries), MAX_TRANCHE_BYTES);
            return List.copyOf(entries);
        } finally {
            trancheLock.unlock();
        }
    }

    @Override
    public List<TrancheEntry> findAll() {
        trancheLock.lock();
        try {
            return readTranchesLocked();
        } finally {
            trancheLock.unlock();
        }
    }

    @Override
    public List<TrancheEntry> clearAsset(String asset) {
        trancheLock.lock();
        try {
            var remaining = readTranchesLocked().stream().filter(entry -> !entry.asset().equals(asset)).toList();
            writeJsonAtomically(tranchePath(), trancheDocument(remaining), MAX_TRANCHE_BYTES);
            return remaining;
        } finally {
            trancheLock.unlock();
        }
    }

    private List<TrancheEntry> readTranchesLocked() {
        try {
            importIfNecessary(tranchePath(), legacyTranchePath(), MAX_TRANCHE_BYTES);
            if (!Files.exists(tranchePath())) return List.of();
            requireBoundedFile(tranchePath(), MAX_TRANCHE_BYTES);
            JsonNode root;
            try (var input = Files.newInputStream(tranchePath())) {
                root = objectMapper.readTree(input);
            }
            if (!root.isArray()) throw new IllegalArgumentException("tranche state must be an array");
            var result = new ArrayList<TrancheEntry>();
            for (var node : root) result.add(parseTranche(node));
            return List.copyOf(result);
        } catch (InvestmentExecutionPersistenceException error) {
            throw error;
        } catch (Exception error) {
            throw persistenceFailure("Unable to read tranche state", error);
        }
    }

    private InvestmentPlan readPlan(Path path) {
        try {
            requireBoundedFile(path, MAX_PLAN_BYTES);
            JsonNode root;
            try (var input = Files.newInputStream(path)) {
                root = objectMapper.readTree(input);
            }
            if (!root.isObject()) throw new IllegalArgumentException("investment plan must be an object");
            var defaults = InvestmentPlan.defaults(clock.instant());
            return new InvestmentPlan(
                    root.has("horizon") ? InvestmentHorizon.from(text(root, "horizon", false)) : defaults.horizon(),
                    decimal(root, "targetReturnAnnualPct", defaults.targetReturnAnnualPct()),
                    decimal(root, "maxDrawdownTolerancePct", defaults.maxDrawdownTolerancePct()),
                    integer(root, "rebalanceIntervalDays", defaults.rebalanceIntervalDays()),
                    decimal(root, "leverageMaxPct", defaults.leverageMaxPct()),
                    decimal(root, "profitTakeTargetPct", defaults.profitTakeTargetPct()),
                    decimal(root, "stopLossPct", defaults.stopLossPct()),
                    longValue(root, "monthlyDCA_KRW", defaults.monthlyDcaKrw()),
                    allocation(root.get("currentHoldings")),
                    nullableLong(root.get("totalCapitalKRW")),
                    nullableDouble(root.get("totalCapitalUSD")),
                    allocation(root.get("currentHoldingsUSD")),
                    nullableDate(root.get("accountStartDate")),
                    nullableDouble(root.get("startingCapitalUSD")),
                    nullableLong(root.get("startingCapitalKRW")),
                    nullableDouble(root.get("investmentExperienceYears")),
                    nullableText(root.get("accountType")),
                    nullableText(root.get("notes")),
                    root.has("updatedAt") ? Instant.parse(text(root, "updatedAt", false)) : defaults.updatedAt()
            );
        } catch (InvestmentExecutionPersistenceException error) {
            throw error;
        } catch (Exception error) {
            throw persistenceFailure("Unable to read the investment plan", error);
        }
    }

    private void importIfNecessary(Path target, Path legacy, long maximumBytes) {
        if (!importLegacyOnFirstRead || Files.exists(target) || !Files.exists(legacy)) return;
        try {
            requireBoundedFile(legacy, maximumBytes);
            Files.createDirectories(target.getParent());
            var temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".import-", ".tmp");
            try {
                try (var source = FileChannel.open(legacy, StandardOpenOption.READ);
                     var destination = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    var position = 0L;
                    while (position < source.size()) {
                        var transferred = source.transferTo(position, source.size() - position, destination);
                        if (transferred <= 0) {
                            throw new IOException("Legacy execution data copy made no progress");
                        }
                        position += transferred;
                    }
                    destination.force(true);
                }
                moveAtomically(temporary, target);
                forceDirectory(target.getParent());
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception error) {
            throw persistenceFailure("Unable to import legacy execution data", error);
        }
    }

    private void writeJsonAtomically(Path target, Object value, int maximumBytes) {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".tmp-", ".json");
            var bytes = objectMapper.writeValueAsBytes(value);
            if (bytes.length > maximumBytes) {
                throw new IllegalArgumentException("Execution state exceeds its configured size bound");
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            moveAtomically(temporary, target);
            temporary = null;
            forceDirectory(target.getParent());
        } catch (Exception error) {
            throw persistenceFailure("Unable to persist execution data", error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forceDirectory(Path directory) {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception ignored) {
            // Some filesystems do not allow opening directories; the file itself is already forced.
        }
    }

    private static LinkedHashMap<String, Object> planDocument(InvestmentPlan plan) {
        var value = new LinkedHashMap<String, Object>();
        value.put("schemaVersion", 1);
        value.put("horizon", plan.horizon().value());
        value.put("targetReturnAnnualPct", plan.targetReturnAnnualPct());
        value.put("maxDrawdownTolerancePct", plan.maxDrawdownTolerancePct());
        value.put("rebalanceIntervalDays", plan.rebalanceIntervalDays());
        value.put("leverageMaxPct", plan.leverageMaxPct());
        value.put("profitTakeTargetPct", plan.profitTakeTargetPct());
        value.put("stopLossPct", plan.stopLossPct());
        value.put("monthlyDCA_KRW", plan.monthlyDcaKrw());
        putIfNotNull(value, "currentHoldings", plan.currentHoldings());
        putIfNotNull(value, "totalCapitalKRW", plan.totalCapitalKrw());
        putIfNotNull(value, "totalCapitalUSD", plan.totalCapitalUsd());
        putIfNotNull(value, "currentHoldingsUSD", plan.currentHoldingsUsd());
        putIfNotNull(value, "accountStartDate", plan.accountStartDate() == null ? null : plan.accountStartDate().toString());
        putIfNotNull(value, "startingCapitalUSD", plan.startingCapitalUsd());
        putIfNotNull(value, "startingCapitalKRW", plan.startingCapitalKrw());
        putIfNotNull(value, "investmentExperienceYears", plan.investmentExperienceYears());
        putIfNotNull(value, "accountType", plan.accountType());
        putIfNotNull(value, "notes", plan.notes());
        value.put("updatedAt", plan.updatedAt().toString());
        return value;
    }

    private static List<LinkedHashMap<String, Object>> trancheDocument(List<TrancheEntry> entries) {
        return entries.stream().map(FileInvestmentExecutionAdapter::trancheDocument).toList();
    }

    private static LinkedHashMap<String, Object> trancheDocument(TrancheEntry entry) {
        var value = new LinkedHashMap<String, Object>();
        value.put("schemaVersion", 1);
        value.put("asset", entry.asset());
        value.put("stage", entry.stage());
        value.put("executedAt", entry.executedAt().toString());
        value.put("priceAtEntry", entry.priceAtEntry());
        value.put("regimeAtEntry", entry.regimeAtEntry());
        putIfNotNull(value, "weightPct", entry.weightPct());
        return value;
    }

    private static LinkedHashMap<String, Object> tradeLogDocument(TradeLogEntry entry) {
        var value = new LinkedHashMap<String, Object>();
        value.put("schemaVersion", 1);
        value.put("ts", entry.timestamp().toString());
        value.put("kind", entry.kind().value());
        putIfNotNull(value, "asset", entry.asset());
        putIfNotNull(value, "from", entry.from());
        putIfNotNull(value, "to", entry.to());
        putIfNotNull(value, "notes", entry.notes());
        putIfNotNull(value, "againstSystemRecommendation", entry.againstSystemRecommendation());
        if (!entry.context().isEmpty()) value.put("context", structuredObject(entry.context()));
        return value;
    }

    private static TrancheEntry parseTranche(JsonNode node) {
        if (!node.isObject()) throw new IllegalArgumentException("tranche entry must be an object");
        return new TrancheEntry(
                text(node, "asset", false),
                integer(node, "stage", 0),
                Instant.parse(text(node, "executedAt", false)),
                nullableDouble(node.get("priceAtEntry")),
                nullableText(node.get("regimeAtEntry")),
                nullableDouble(node.get("weightPct"))
        );
    }

    private static TradeLogEntry parseTradeLog(JsonNode node) {
        if (!node.isObject()) throw new IllegalArgumentException("trade log entry must be an object");
        var context = new LinkedHashMap<String, TradeLogValue>();
        var contextNode = node.get("context");
        if (contextNode != null && contextNode.isObject()) {
            contextNode.properties().forEach(entry -> context.put(entry.getKey(), structuredValue(entry.getValue(), 0)));
        }
        return new TradeLogEntry(
                Instant.parse(text(node, "ts", false)),
                TradeLogKind.from(text(node, "kind", false)),
                nullableText(node.get("asset")),
                nullableText(node.get("from")),
                nullableText(node.get("to")),
                nullableText(node.get("notes")),
                nullableBoolean(node.get("againstSystemRecommendation")),
                context
        );
    }

    private static TradeLogValue structuredValue(JsonNode node, int depth) {
        if (depth > 8) throw new IllegalArgumentException("trade log context is too deep");
        if (node == null || node.isNull()) return TradeLogValue.NullValue.INSTANCE;
        if (node.isString()) return new TradeLogValue.TextValue(node.stringValue());
        if (node.isBoolean()) return new TradeLogValue.BooleanValue(node.booleanValue());
        if (node.isIntegralNumber()) return new TradeLogValue.NumberValue(node.longValue());
        if (node.isNumber()) return new TradeLogValue.NumberValue(node.decimalValue());
        if (node.isArray()) {
            var values = new ArrayList<TradeLogValue>();
            node.forEach(value -> values.add(structuredValue(value, depth + 1)));
            return new TradeLogValue.ArrayValue(values);
        }
        if (node.isObject()) {
            var values = new LinkedHashMap<String, TradeLogValue>();
            node.properties().forEach(entry -> values.put(entry.getKey(), structuredValue(entry.getValue(), depth + 1)));
            return new TradeLogValue.ObjectValue(values);
        }
        throw new IllegalArgumentException("unsupported trade log context value");
    }

    private static Object structuredObject(Map<String, TradeLogValue> values) {
        var result = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> result.put(key, structuredValue(value)));
        return result;
    }

    private static Object structuredValue(TradeLogValue value) {
        return switch (value) {
            case TradeLogValue.TextValue text -> text.value();
            case TradeLogValue.NumberValue number -> number.value();
            case TradeLogValue.BooleanValue bool -> bool.value();
            case TradeLogValue.NullValue ignored -> null;
            case TradeLogValue.ArrayValue array -> array.values().stream()
                    .map(FileInvestmentExecutionAdapter::structuredValue).toList();
            case TradeLogValue.ObjectValue object -> structuredObject(object.fields());
        };
    }

    private static Map<String, Double> allocation(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) throw new IllegalArgumentException("allocation must be an object");
        var result = new LinkedHashMap<String, Double>();
        node.properties().forEach(entry -> {
            if (!entry.getValue().isNumber()) throw new IllegalArgumentException("allocation value must be numeric");
            result.put(entry.getKey(), entry.getValue().doubleValue());
        });
        return Collections.unmodifiableMap(result);
    }

    private static String text(JsonNode object, String field, boolean nullable) {
        var node = object.get(field);
        if (node == null || node.isNull()) {
            if (nullable) return null;
            throw new IllegalArgumentException(field + " is required");
        }
        if (!node.isString()) throw new IllegalArgumentException(field + " must be text");
        return node.stringValue();
    }

    private static String nullableText(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isString()) throw new IllegalArgumentException("value must be text");
        return node.stringValue();
    }

    private static Boolean nullableBoolean(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isBoolean()) throw new IllegalArgumentException("value must be boolean");
        return node.booleanValue();
    }

    private static double decimal(JsonNode object, String field, double fallback) {
        var node = object.get(field);
        if (node == null || node.isNull()) return fallback;
        if (!node.isNumber()) throw new IllegalArgumentException(field + " must be numeric");
        return node.doubleValue();
    }

    private static int integer(JsonNode object, String field, int fallback) {
        var node = object.get(field);
        if (node == null || node.isNull()) return fallback;
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw new IllegalArgumentException(field + " must be an integer");
        return node.intValue();
    }

    private static long longValue(JsonNode object, String field, long fallback) {
        var node = object.get(field);
        if (node == null || node.isNull()) return fallback;
        if (!node.isIntegralNumber() || !node.canConvertToLong()) throw new IllegalArgumentException(field + " must be an integer");
        return node.longValue();
    }

    private static Long nullableLong(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToLong()) throw new IllegalArgumentException("value must be an integer");
        return node.longValue();
    }

    private static Double nullableDouble(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isNumber()) throw new IllegalArgumentException("value must be numeric");
        return node.doubleValue();
    }

    private static LocalDate nullableDate(JsonNode node) {
        var value = nullableText(node);
        return value == null ? null : LocalDate.parse(value);
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static void requireBoundedFile(Path path, long maximumBytes) throws IOException {
        var size = Files.size(path);
        if (size > maximumBytes) throw new IllegalArgumentException("execution data file exceeds the configured bound");
    }

    private Path planPath() {
        return dataDirectory.resolve("investment/plan.json");
    }

    private Path tradeLogPath() {
        return dataDirectory.resolve("investment/trade-log.jsonl");
    }

    private Path tranchePath() {
        return dataDirectory.resolve("execution/tranche-state.json");
    }

    private Path legacyPlanPath() {
        return legacyDataDirectory.resolve("investment/plan.json");
    }

    private Path legacyTradeLogPath() {
        return legacyDataDirectory.resolve("investment/trade-log.jsonl");
    }

    private Path legacyTranchePath() {
        return legacyDataDirectory.resolve("execution/tranche-state.json");
    }

    private static Path absolute(Path value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
        return value.normalize();
    }

    private static InvestmentExecutionPersistenceException persistenceFailure(String message, Throwable cause) {
        return new InvestmentExecutionPersistenceException(message, cause);
    }
}
