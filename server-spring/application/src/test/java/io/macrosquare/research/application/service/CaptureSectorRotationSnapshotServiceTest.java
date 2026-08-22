package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.CurrentSectorRotationAssessment;
import io.macrosquare.research.application.model.CurrentSectorRotationAssessment.CurrentSectorProfile;
import io.macrosquare.research.application.model.CurrentSectorRotationAssessment.CurrentRevisionBreadth;
import io.macrosquare.research.application.port.out.LoadSectorRotationPriceWindowPort;
import io.macrosquare.research.application.port.out.SectorRotationValidationRepository;
import io.macrosquare.research.domain.rotation.PendingSectorRotationWindow;
import io.macrosquare.research.domain.rotation.SectorClassification;
import io.macrosquare.research.domain.rotation.SectorRotationCompositeSnapshot;
import io.macrosquare.research.domain.rotation.SectorRotationForwardWindow;
import io.macrosquare.research.domain.rotation.SectorRotationHorizon;
import io.macrosquare.research.domain.rotation.SectorRotationItem;
import io.macrosquare.research.domain.rotation.SectorRotationLabel;
import io.macrosquare.research.domain.rotation.SectorRotationOutcome;
import io.macrosquare.research.domain.rotation.SectorRotationRegime;
import io.macrosquare.research.domain.rotation.SectorRotationState;
import io.macrosquare.research.domain.rotation.SectorRotationView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureSectorRotationSnapshotServiceTest {

    @Test
    void capturesOneImmutableRunPerCompletedCommonSessionWithSourceDates() {
        var repository = new FakeRepository();
        var asOf = LocalDate.parse("2026-08-07");
        var prices = new LoadSectorRotationPriceWindowPort() {
            @Override public Optional<LocalDate> latestCompletedCommonDate(Instant calculatedAt) {
                return Optional.of(asOf);
            }
            @Override public Optional<SectorRotationForwardWindow> loadForwardWindow(
                    LocalDate startOn, int tradingSessions) { return Optional.empty(); }
        };
        var outcomes = new EvaluateSectorRotationOutcomesService(repository, prices);
        var service = new CaptureSectorRotationSnapshotService(repository, prices, outcomes);
        var assessment = assessment(asOf);

        assertTrue(service.capture(assessment));
        assertFalse(service.capture(assessment));
        assertEquals(asOf.plusDays(1), repository.snapshot.asOfDate());
        assertEquals(asOf, repository.snapshot.priceAnchorOn());
        assertEquals(11, repository.snapshot.items().size());
        assertEquals(asOf.minusDays(1), repository.snapshot.oldestMacroObservedOn());
        assertEquals(asOf, repository.snapshot.latestMacroObservedOn());
        assertEquals(asOf, repository.snapshot.items().getFirst().latestMomentumObservedOn());
        assertEquals(asOf.plusDays(1), repository.snapshot.items().getFirst().revisionObservedOn());
    }

    @Test
    void skipsPartialCurrentCoverageInsteadOfWritingOrThrowingAnInvalidRun() {
        var repository = new FakeRepository();
        var asOf = LocalDate.parse("2026-08-07");
        var prices = new LoadSectorRotationPriceWindowPort() {
            @Override public Optional<LocalDate> latestCompletedCommonDate(Instant calculatedAt) {
                return Optional.of(asOf);
            }
            @Override public Optional<SectorRotationForwardWindow> loadForwardWindow(
                    LocalDate startOn, int tradingSessions) { return Optional.empty(); }
        };
        var service = new CaptureSectorRotationSnapshotService(
                repository, prices, new EvaluateSectorRotationOutcomesService(repository, prices));

        assertFalse(service.capture(assessment(asOf, 10)));
        assertNull(repository.snapshot);
    }

    private static CurrentSectorRotationAssessment assessment(LocalDate asOf) {
        return assessment(asOf, 11);
    }

    private static CurrentSectorRotationAssessment assessment(LocalDate asOf, int sectorCount) {
        var items = new ArrayList<SectorRotationItem>();
        var profiles = new LinkedHashMap<String, CurrentSectorProfile>();
        var keys = List.of("XLK", "XLF", "XLE", "XLV", "XLI", "XLY", "XLC", "XLB", "XLRE", "XLU", "XLP");
        for (var key : keys.subList(0, sectorCount)) {
            var sectorKey = "SECTOR_" + key;
            var item = new SectorRotationItem(
                    sectorKey, key, SectorClassification.CYCLICAL, 70, 71, 72, 73,
                    60, null, null, 45, SectorRotationState.IMPROVING,
                    SectorRotationLabel.ROTATION_IN, SectorRotationHorizon.ONE_TO_THREE_MONTHS,
                    "test", List.of());
            items.add(item);
            var revision = new CurrentRevisionBreadth(
                    asOf.plusDays(1), asOf.minusDays(14), asOf.plusDays(1),
                    40, 35, 88, 60, 20, 75);
            profiles.put(sectorKey, new CurrentSectorProfile(
                    sectorKey, key, "CYCLICAL", 1d, 2d, 70, 70, 70, 70, 50,
                    70, 30, 75, "BUY", 60, 75, revision, null, null, item));
        }
        var view = new SectorRotationView(
                SectorRotationRegime.MID_GROWTH, 80, Map.of(SectorRotationRegime.MID_GROWTH, 80),
                "test", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), items);
        var rawDates = Map.of(
                "T10Y2Y", asOf.minusDays(1), "WTI", asOf, "DXY", asOf,
                "STLFSI4", asOf.minusDays(1), "BAMLH0A0HYM2", asOf.minusDays(1));
        var derivedDates = new LinkedHashMap<String, LocalDate>();
        derivedDates.put("LIQUIDITY_DIRECTION", asOf);
        derivedDates.put("REAL_YIELD", asOf.minusDays(1));
        for (var key : List.of("XLK", "XLF", "XLE", "XLV", "XLI", "XLY", "XLC", "XLB", "XLRE", "XLU", "XLP")) {
            derivedDates.put("SECTOR_RS_SHORT_" + key, asOf);
            derivedDates.put("SECTOR_RS_" + key, asOf);
            derivedDates.put("SECTOR_MOMENTUM_SCORE_" + key, asOf);
            derivedDates.put("SECTOR_ABSOLUTE_TREND_" + key, asOf);
        }
        return new CurrentSectorRotationAssessment(
                "2026-08-08T01:00:00Z", view, profiles, sectorCount, sectorCount, 11,
                rawDates, derivedDates);
    }

    private static final class FakeRepository implements SectorRotationValidationRepository {
        private SectorRotationCompositeSnapshot snapshot;
        @Override public boolean append(SectorRotationCompositeSnapshot value) {
            if (snapshot != null && snapshot.runId().equals(value.runId())) return false;
            snapshot = value;
            return true;
        }
        @Override public List<PendingSectorRotationWindow> loadPendingWindows(int limit) { return List.of(); }
        @Override public int appendOutcomes(List<SectorRotationOutcome> outcomes) { return 0; }
    }
}
