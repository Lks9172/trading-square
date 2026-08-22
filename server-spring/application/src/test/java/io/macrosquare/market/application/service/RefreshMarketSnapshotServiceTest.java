package io.macrosquare.market.application.service;

import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.BooleanValue;
import io.macrosquare.market.application.model.AutomaticPolicyDirection;
import io.macrosquare.market.application.model.MarketReadModels.Document;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.application.model.MarketCollectionStatus;
import io.macrosquare.market.application.port.out.MarketCollectionStatusRepository;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.domain.allocation.CoreAllocationPolicy;
import io.macrosquare.market.domain.indicator.CoreDerivedIndicatorPolicy;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import io.macrosquare.market.domain.observation.MarketInputFreshnessPolicy;
import io.macrosquare.market.domain.regime.MacroRegimePolicy;
import io.macrosquare.market.domain.signal.CoreAssetSignalPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshMarketSnapshotServiceTest {

    @Test
    void retainsLastValidDerivedValueWhenFreshHistoryIsNotDeepEnough() {
        var seed = seedWithSectorMomentum(-4.69, "2026-07-19");
        var saved = new Document[1];
        var service = new RefreshMarketSnapshotService(
                () -> seed,
                snapshot -> saved[0] = snapshot,
                new EmptyRepository(),
                new CoreDerivedIndicatorPolicy(),
                new MacroRegimePolicy(),
                new CoreAssetSignalPolicy(),
                new CoreAllocationPolicy(),
                Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5)
        );

        var report = service.refresh();

        var derived = object(report.snapshot().root().fields().get("derived"));
        var xlk = object(derived.fields().get("SECTOR_XLK"));
        assertEquals(-4.69, ((NumberValue) xlk.fields().get("value")).value().doubleValue(), 0.0001);
        assertEquals("2026-07-19", ((TextValue) xlk.fields().get("date")).value());
        assertEquals(report.snapshot(), saved[0]);
    }

    @Test
    void refreshesTheEvaluationDateOfAnUnchangedButSuccessfullyRecomputedDerivedValue() {
        var indicator = new LinkedHashMap<String, StructuredValue>();
        indicator.put("name", new TextValue("us_m2_yoy"));
        indicator.put("value", new NumberValue(BigDecimal.valueOf(5)));
        indicator.put("date", new TextValue("2026-06-01"));
        indicator.put("formula", new TextValue("M2SL YoY"));
        var seed = seedWithSectorMomentum(-4.69, "2026-07-19");
        var root = new LinkedHashMap<>(seed.root().fields());
        root.put("derived", new ObjectValue(Map.of("US_M2_YOY", new ObjectValue(indicator))));
        var service = new RefreshMarketSnapshotService(
                () -> new Document(new ObjectValue(root)), snapshot -> { },
                new M2Repository(), new CoreDerivedIndicatorPolicy(), new MacroRegimePolicy(),
                new CoreAssetSignalPolicy(), new CoreAllocationPolicy(),
                Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5)
        );

        var snapshot = service.refresh().snapshot();
        var derived = object(snapshot.root().fields().get("derived"));
        var m2 = object(derived.fields().get("US_M2_YOY"));

        assertEquals(5, ((NumberValue) m2.fields().get("value")).value().doubleValue());
        assertEquals("2026-07-20", ((TextValue) m2.fields().get("date")).value());
        assertTrue(((BooleanValue) m2.fields().get("eligibleForSignals")).value());
    }

    @Test
    void appliesFreshPolicyNlpThroughTheMarketOwnedAntiCorruptionPort() {
        var seed = seedWithSectorMomentum(-4.69, "2026-07-19");
        var service = new RefreshMarketSnapshotService(
                () -> seed,
                snapshot -> { },
                new EmptyRepository(),
                new CoreDerivedIndicatorPolicy(),
                new MacroRegimePolicy(),
                new CoreAssetSignalPolicy(),
                new CoreAllocationPolicy(),
                Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5),
                () -> java.util.Optional.of(new AutomaticPolicyDirection(
                        2, 78, "Federal Reserve official-document NLP",
                        Instant.parse("2026-07-15T18:00:00Z")))
        );

        var snapshot = service.refresh().snapshot();
        var meta = object(snapshot.root().fields().get("meta"));
        var inputs = object(meta.fields().get("autoInputs"));

        assertEquals(2, ((NumberValue) inputs.fields().get("policyDirection")).value().intValue());
        assertEquals(78, ((NumberValue) inputs.fields().get("policyConfidence")).value().intValue());
        assertEquals("Federal Reserve official-document NLP",
                ((TextValue) inputs.fields().get("policySource")).value());
    }

    @Test
    void projectsSpringOwnedKrxHistoryWithExplicitNaverFinanceProvenance() {
        var seed = seedWithSectorMomentum(-4.69, "2026-07-19");
        var service = new RefreshMarketSnapshotService(
                () -> seed,
                snapshot -> { },
                new KrxRepository(),
                new CoreDerivedIndicatorPolicy(),
                new MacroRegimePolicy(),
                new CoreAssetSignalPolicy(),
                new CoreAllocationPolicy(),
                Clock.fixed(Instant.parse("2026-07-21T12:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5)
        );

        var snapshot = service.refresh().snapshot();
        var raw = object(snapshot.root().fields().get("raw"));
        var foreignRaw = object(raw.fields().get("KOSPI_FOREIGN_NET_1D"));
        assertEquals("NAVER_FINANCE", ((TextValue) foreignRaw.fields().get("source")).value());
        var derived = object(snapshot.root().fields().get("derived"));
        var foreign20 = object(derived.fields().get("KOSPI_FOREIGN_NET_20D"));
        assertEquals(20_000, ((NumberValue) foreign20.fields().get("value")).value().doubleValue(), 0.0001);
        assertEquals("2026-07-20", ((TextValue) foreign20.fields().get("date")).value());
        var foreign60 = object(derived.fields().get("KOSPI_FOREIGN_OVERSELL_30T_FLAG"));
        assertEquals(1, ((NumberValue) foreign60.fields().get("value")).value().intValue());
        var meta = object(snapshot.root().fields().get("meta"));
        var sourceFrequencies = object(meta.fields().get("sourceFrequencies"));
        assertEquals("일간 / 서버 30분 수집 / 최근 60영업일 멱등 갱신",
                ((TextValue) sourceFrequencies.fields().get("NAVER_FINANCE")).value());
        var historyGuarantee = object(meta.fields().get("historyGuarantee"));
        assertEquals("최근 60영업일 + 일일 누적",
                ((TextValue) historyGuarantee.fields().get("NAVER_FINANCE")).value());
    }

    @Test
    void preservesStaleValuesForAuditButExcludesThemFromDecisionInputs() {
        var rawPoint = new LinkedHashMap<String, StructuredValue>();
        rawPoint.put("code", new TextValue("VIXCLS"));
        rawPoint.put("value", new NumberValue(BigDecimal.valueOf(18)));
        rawPoint.put("date", new TextValue("2026-07-01"));
        rawPoint.put("source", new TextValue("FRED"));
        var derivedPoint = new LinkedHashMap<String, StructuredValue>();
        derivedPoint.put("name", new TextValue("nasdaq_disparity_200"));
        derivedPoint.put("value", new NumberValue(BigDecimal.valueOf(4.2)));
        derivedPoint.put("date", new TextValue("2026-07-01"));
        derivedPoint.put("formula", new TextValue("last-valid"));
        var root = new LinkedHashMap<String, StructuredValue>();
        root.put("timestamp", new TextValue("2026-07-01T00:00:00Z"));
        root.put("raw", new ObjectValue(Map.of("VIXCLS", new ObjectValue(rawPoint))));
        root.put("derived", new ObjectValue(Map.of("NASDAQ_DISPARITY", new ObjectValue(derivedPoint))));
        root.put("regime", new ObjectValue(Map.of()));
        root.put("signals", new ArrayValue(List.of()));
        root.put("allocation", new ObjectValue(Map.of()));
        root.put("meta", new ObjectValue(Map.of(
                "executionPlans", new ArrayValue(List.of()),
                "topdown", new ObjectValue(Map.of("summary", new TextValue("legacy"))))));
        var seed = new Document(new ObjectValue(root));
        var service = new RefreshMarketSnapshotService(
                () -> seed,
                snapshot -> { },
                new EmptyRepository(),
                new CoreDerivedIndicatorPolicy(),
                new MacroRegimePolicy(),
                new CoreAssetSignalPolicy(),
                new CoreAllocationPolicy(),
                Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5)
        );

        var snapshot = service.refresh().snapshot();
        var raw = object(snapshot.root().fields().get("raw"));
        var retainedVix = object(raw.fields().get("VIXCLS"));
        assertEquals(18, ((NumberValue) retainedVix.fields().get("value"))
                .value().intValue());
        org.junit.jupiter.api.Assertions.assertFalse(
                ((io.macrosquare.market.application.model.MarketReadModels.BooleanValue)
                        retainedVix.fields().get("eligibleForSignals")).value());
        var meta = object(snapshot.root().fields().get("meta"));
        var freshness = object(meta.fields().get("inputFreshness"));
        assertEquals(1, ((NumberValue) freshness.fields().get("rawExcluded")).value().intValue());
        assertEquals(1, ((NumberValue) freshness.fields().get("derivedExcluded")).value().intValue());
        var excludedKeys = (ArrayValue) freshness.fields().get("excludedKeys");
        assertEquals("RAW:VIXCLS", ((TextValue) excludedKeys.values().getFirst()).value());
        var planFreshness = object(meta.fields().get("executionPlanFreshness"));
        org.junit.jupiter.api.Assertions.assertFalse(
                ((io.macrosquare.market.application.model.MarketReadModels.BooleanValue)
                        planFreshness.fields().get("eligibleForExecution")).value());
        assertEquals("legacy-handoff", ((TextValue) planFreshness.fields().get("source")).value());
        var topdownFreshness = object(meta.fields().get("topdownFreshness"));
        org.junit.jupiter.api.Assertions.assertFalse(
                ((io.macrosquare.market.application.model.MarketReadModels.BooleanValue)
                        topdownFreshness.fields().get("eligibleForCurrentRanking")).value());
        var signals = (ArrayValue) snapshot.root().fields().get("signals");
        var nasdaq = object(signals.values().getFirst());
        var coverage = ((NumberValue) nasdaq.fields().get("dataCoveragePct")).value().intValue();
        org.junit.jupiter.api.Assertions.assertTrue(coverage < 100);
    }

    @Test
    void recalculatesCalendarDdaysDropsPastRowsAndAddsExpiryContext() {
        var oldEvent = new ObjectValue(Map.of(
                "date", new TextValue("2026-07-29"),
                "name", new TextValue("Old FOMC"),
                "category", new TextValue("FOMC"),
                "daysUntil", new NumberValue(BigDecimal.valueOf(9)),
                "importance", new TextValue("high")
        ));
        var futureEvent = new ObjectValue(Map.of(
                "date", new TextValue("2026-09-16"),
                "name", new TextValue("Future FOMC"),
                "category", new TextValue("FOMC"),
                "daysUntil", new NumberValue(BigDecimal.valueOf(999)),
                "importance", new TextValue("high")
        ));
        var seed = seedWithSectorMomentum(-4.69, "2026-08-05");
        var root = new LinkedHashMap<>(seed.root().fields());
        root.put("meta", new ObjectValue(Map.of("calendar", new ArrayValue(List.of(oldEvent, futureEvent)))));
        var service = new RefreshMarketSnapshotService(
                () -> new Document(new ObjectValue(root)),
                snapshot -> { },
                new EmptyRepository(),
                new CoreDerivedIndicatorPolicy(),
                new MacroRegimePolicy(),
                new CoreAssetSignalPolicy(),
                new CoreAllocationPolicy(),
                Clock.fixed(Instant.parse("2026-08-06T01:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5)
        );

        var meta = object(service.refresh().snapshot().root().fields().get("meta"));
        var calendar = (ArrayValue) meta.fields().get("calendar");
        assertFalse(calendar.values().stream().map(RefreshMarketSnapshotServiceTest::object)
                .anyMatch(row -> "Old FOMC".equals(((TextValue) row.fields().get("name")).value())));
        var future = calendar.values().stream().map(RefreshMarketSnapshotServiceTest::object)
                .filter(row -> "Future FOMC".equals(((TextValue) row.fields().get("name")).value()))
                .findFirst().orElseThrow();
        assertEquals(41, ((NumberValue) future.fields().get("daysUntil")).value().intValue());
        assertTrue(calendar.values().stream().map(RefreshMarketSnapshotServiceTest::object)
                .anyMatch(row -> "US_OPEX".equals(((TextValue) row.fields().get("category")).value())));
        var methodology = object(meta.fields().get("calendarMethodology"));
        assertFalse(((io.macrosquare.market.application.model.MarketReadModels.BooleanValue)
                methodology.fields().get("directionalSignal")).value());
    }

    @Test
    void exposesCollectorGapsAsOperationalEvidenceWithoutTurningThemIntoScores() {
        var completedAt = Instant.parse("2026-08-06T00:10:00Z");
        var status = new MarketCollectionStatus(
                MarketDataSource.SENTIMENT,
                MarketCollectionStatus.State.DEGRADED,
                completedAt.minusSeconds(4),
                completedAt,
                2,
                2,
                List.of("NAAIM_EXPOSURE"),
                "PROVIDER_POLICY_UNAVAILABLE");
        var statuses = new MarketCollectionStatusRepository() {
            @Override public void save(MarketCollectionStatus ignored) { }
            @Override public Map<MarketDataSource, MarketCollectionStatus> loadLatest() {
                return Map.of(MarketDataSource.SENTIMENT, status);
            }
        };
        var service = new RefreshMarketSnapshotService(
                () -> seedWithSectorMomentum(-4.69, "2026-08-05"),
                snapshot -> { },
                new EmptyRepository(),
                new CoreDerivedIndicatorPolicy(),
                new MacroRegimePolicy(),
                new CoreAssetSignalPolicy(),
                new CoreAllocationPolicy(),
                Clock.fixed(Instant.parse("2026-08-06T00:20:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5),
                java.util.Optional::empty,
                new MarketInputFreshnessPolicy(),
                null,
                context -> null,
                statuses
        );

        var meta = object(service.refresh().snapshot().root().fields().get("meta"));
        var health = object(meta.fields().get("collectionHealth"));
        assertEquals("LIMITED", ((TextValue) health.fields().get("status")).value());
        assertFalse(((io.macrosquare.market.application.model.MarketReadModels.BooleanValue)
                health.fields().get("usedForInvestmentScores")).value());
        var source = object(object(health.fields().get("sources")).fields().get("SENTIMENT"));
        assertEquals("LIMITED", ((TextValue) source.fields().get("status")).value());
        assertEquals(10, ((NumberValue) source.fields().get("ageMinutes")).value().intValue());
        var failures = (ArrayValue) source.fields().get("failureKeys");
        assertEquals("NAAIM_EXPOSURE", ((TextValue) failures.values().getFirst()).value());
    }

    private static Document seedWithSectorMomentum(double value, String date) {
        var indicator = new LinkedHashMap<String, StructuredValue>();
        indicator.put("name", new TextValue("sector_xlk"));
        indicator.put("value", new NumberValue(BigDecimal.valueOf(value)));
        indicator.put("date", new TextValue(date));
        indicator.put("formula", new TextValue("last-valid handoff"));

        var derived = new LinkedHashMap<String, StructuredValue>();
        derived.put("SECTOR_XLK", new ObjectValue(indicator));
        var root = new LinkedHashMap<String, StructuredValue>();
        root.put("timestamp", new TextValue("2026-07-19T00:00:00Z"));
        root.put("raw", new ObjectValue(Map.of()));
        root.put("derived", new ObjectValue(derived));
        root.put("regime", new ObjectValue(Map.of()));
        root.put("signals", new ArrayValue(List.of()));
        root.put("allocation", new ObjectValue(Map.of()));
        root.put("meta", new ObjectValue(Map.of()));
        return new Document(new ObjectValue(root));
    }

    private static ObjectValue object(StructuredValue value) {
        return (ObjectValue) value;
    }

    private static final class EmptyRepository implements MarketObservationRepository {
        @Override public int save(List<MarketObservation> observations) { return observations.size(); }
        @Override public List<MarketObservation> loadLatest(MarketDataSource source) { return List.of(); }
        @Override public List<MarketObservation> loadHistory(MarketDataSource source, String key) { return List.of(); }
    }

    private static final class KrxRepository implements MarketObservationRepository {
        @Override public int save(List<MarketObservation> observations) { return observations.size(); }

        @Override
        public List<MarketObservation> loadLatest(MarketDataSource source) {
            if (source != MarketDataSource.KRX) return List.of();
            return List.of(observation("KOSPI_FOREIGN_NET_1D", 1_000, LocalDate.parse("2026-07-20")));
        }

        @Override
        public List<MarketObservation> loadHistory(MarketDataSource source, String key) {
            if (source != MarketDataSource.KRX) return List.of();
            var values = new java.util.ArrayList<MarketObservation>();
            for (var index = 0; index < 60; index++) {
                var value = switch (key) {
                    case "KOSPI_FOREIGN_NET_1D" -> index < 40 ? -10_000d : 1_000d;
                    case "KOSPI_INDIVIDUAL_NET_1D" -> index < 40 ? 10_000d : -1_000d;
                    case "KOSPI_INSTITUTION_NET_1D", "KOSPI_PENSION_NET_1D" -> 0d;
                    default -> throw new IllegalArgumentException(key);
                };
                values.add(observation(key, value, LocalDate.parse("2026-05-22").plusDays(index)));
            }
            return List.copyOf(values);
        }

        private static MarketObservation observation(String key, double value, LocalDate date) {
            return new MarketObservation(key, "NAVER_FINANCE:KOSPI", value, date, MarketDataSource.KRX);
        }
    }

    private static final class M2Repository implements MarketObservationRepository {
        @Override public int save(List<MarketObservation> observations) { return observations.size(); }

        @Override
        public List<MarketObservation> loadLatest(MarketDataSource source) {
            return source == MarketDataSource.FRED
                    ? List.of(new MarketObservation(
                            "M2SL", "M2SL", 105, LocalDate.parse("2026-06-01"), source))
                    : List.of();
        }

        @Override
        public List<MarketObservation> loadHistory(MarketDataSource source, String key) {
            if (source != MarketDataSource.FRED || !"M2SL".equals(key)) return List.of();
            return List.of(
                    new MarketObservation("M2SL", "M2SL", 100,
                            LocalDate.parse("2025-06-01"), source),
                    new MarketObservation("M2SL", "M2SL", 105,
                            LocalDate.parse("2026-06-01"), source)
            );
        }
    }
}
