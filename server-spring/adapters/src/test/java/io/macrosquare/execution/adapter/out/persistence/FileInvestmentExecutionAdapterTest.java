package io.macrosquare.execution.adapter.out.persistence;

import io.macrosquare.execution.application.port.out.InvestmentExecutionPersistenceException;
import io.macrosquare.execution.domain.model.InvestmentHorizon;
import io.macrosquare.execution.domain.model.InvestmentPlan;
import io.macrosquare.execution.domain.model.TradeLogEntry;
import io.macrosquare.execution.domain.model.TradeLogKind;
import io.macrosquare.execution.domain.model.TradeLogValue;
import io.macrosquare.execution.domain.model.TrancheEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileInvestmentExecutionAdapterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path root;

    @Test
    void importsLegacyFilesOnceWithoutMutatingTheReadOnlySource() throws Exception {
        var legacy = root.resolve("legacy");
        var target = root.resolve("spring");
        Files.createDirectories(legacy.resolve("investment"));
        Files.createDirectories(legacy.resolve("execution"));
        Files.writeString(legacy.resolve("investment/plan.json"), """
                {"horizon":"long","targetReturnAnnualPct":15,"updatedAt":"2026-07-19T00:00:00.000Z"}
                """);
        Files.writeString(legacy.resolve("investment/trade-log.jsonl"), """
                {"ts":"2026-07-19T00:00:00.000Z","kind":"observation","context":{"nested":{"ok":true}}}
                """);
        Files.writeString(legacy.resolve("execution/tranche-state.json"), """
                [{"asset":"NASDAQ","stage":1,"executedAt":"2026-07-19T00:00:00.000Z","priceAtEntry":25000,"regimeAtEntry":"RISK_ON","weightPct":30}]
                """);
        var before = digestTree(legacy);
        var adapter = adapter(target, legacy, true);

        assertEquals(InvestmentHorizon.LONG, adapter.load().orElseThrow().horizon());
        assertEquals(1, adapter.recent(200).size());
        assertEquals(1, adapter.findAll().size());
        assertEquals(before, digestTree(legacy));
        assertTrue(Files.exists(target.resolve("investment/plan.json")));
        assertTrue(Files.exists(target.resolve("execution/tranche-state.json")));
    }

    @Test
    void atomicallyPersistsPlanTranchesAndNestedTradeContext() throws Exception {
        var target = root.resolve("spring");
        var adapter = adapter(target, root.resolve("missing-legacy"), true);
        var plan = InvestmentPlan.defaults(Instant.parse("2026-07-20T00:00:00Z"));
        adapter.save(plan);
        adapter.append(new TrancheEntry(
                "GOLD", 1, Instant.parse("2026-07-20T00:00:00Z"), 3_400d, "NEUTRAL", 30d
        ));
        adapter.append(new TradeLogEntry(
                Instant.parse("2026-07-20T00:00:00Z"),
                TradeLogKind.USER_ACTION,
                "GOLD",
                null,
                "BUY",
                "first",
                false,
                Map.of("nested", new TradeLogValue.ObjectValue(Map.of(
                        "score", new TradeLogValue.NumberValue(75L)
                )))
        ));

        assertEquals(plan, adapter.load().orElseThrow());
        assertEquals(1, adapter.findAll().size());
        assertEquals(1, adapter.recent(10).size());
        var json = new ObjectMapper().readTree(Files.readString(target.resolve("investment/trade-log.jsonl")));
        assertEquals(75, json.get("context").get("nested").get("score").intValue());
        try (var files = Files.walk(target)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
    }

    @Test
    void serializesConcurrentTrancheUpdatesWithoutLostWrites() throws Exception {
        var adapter = adapter(root.resolve("spring"), root.resolve("legacy"), false);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = IntStream.range(0, 40).mapToObj(index -> (java.util.concurrent.Callable<Void>) () -> {
                adapter.append(new TrancheEntry(
                        index % 2 == 0 ? "NASDAQ" : "GOLD",
                        index % 5 + 1,
                        Instant.parse("2026-07-20T00:00:00Z").plusMillis(index),
                        100d + index,
                        null,
                        null
                ));
                return null;
            }).toList();
            for (var future : executor.invokeAll(tasks)) future.get();
        }
        assertEquals(40, adapter.findAll().size());
    }

    @Test
    void serializesConcurrentPlanMutationsWithoutLostFields() throws Exception {
        var adapter = adapter(root.resolve("spring"), root.resolve("legacy"), false);
        var initial = InvestmentPlan.defaults(Instant.parse("2026-07-20T00:00:00Z"));
        adapter.save(initial);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = IntStream.range(0, 40).mapToObj(index -> (java.util.concurrent.Callable<Void>) () -> {
                adapter.updateAtomically(initial, current -> withTargetReturn(
                        current,
                        current.targetReturnAnnualPct() + 1
                ));
                return null;
            }).toList();
            for (var future : executor.invokeAll(tasks)) future.get();
        }

        assertEquals(52d, adapter.load().orElseThrow().targetReturnAnnualPct());
    }

    @Test
    void failsClosedOnCorruptSpringOwnedState() throws Exception {
        var target = root.resolve("spring");
        Files.createDirectories(target.resolve("investment"));
        Files.writeString(target.resolve("investment/plan.json"), "{bad-json");
        assertThrows(InvestmentExecutionPersistenceException.class,
                () -> adapter(target, root.resolve("legacy"), true).load());
    }

    private static FileInvestmentExecutionAdapter adapter(Path target, Path legacy, boolean importLegacy) {
        return new FileInvestmentExecutionAdapter(new ObjectMapper(), CLOCK, target, legacy.toAbsolutePath(), importLegacy);
    }

    private static InvestmentPlan withTargetReturn(InvestmentPlan current, double targetReturn) {
        return new InvestmentPlan(
                current.horizon(),
                targetReturn,
                current.maxDrawdownTolerancePct(),
                current.rebalanceIntervalDays(),
                current.leverageMaxPct(),
                current.profitTakeTargetPct(),
                current.stopLossPct(),
                current.monthlyDcaKrw(),
                current.currentHoldings(),
                current.totalCapitalKrw(),
                current.totalCapitalUsd(),
                current.currentHoldingsUsd(),
                current.accountStartDate(),
                current.startingCapitalUsd(),
                current.startingCapitalKrw(),
                current.investmentExperienceYears(),
                current.accountType(),
                current.notes(),
                current.updatedAt()
        );
    }

    private static String digestTree(Path directory) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var files = Files.walk(directory)) {
            for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                digest.update(directory.relativize(file).toString().getBytes());
                digest.update(Files.readAllBytes(file));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
