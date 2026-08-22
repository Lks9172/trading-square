package io.macrosquare.research.application.service;

import io.macrosquare.research.application.port.out.LoadSectorRotationPriceWindowPort;
import io.macrosquare.research.application.port.out.SectorRotationValidationRepository;
import io.macrosquare.research.domain.rotation.PendingSectorRotationWindow;
import io.macrosquare.research.domain.rotation.SectorRotationCompositeSnapshot;
import io.macrosquare.research.domain.rotation.SectorRotationForwardWindow;
import io.macrosquare.research.domain.rotation.SectorRotationOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvaluateSectorRotationOutcomesServiceTest {

    @Test
    void writesNothingUntilTheExactForwardWindowExists() {
        var runId = UUID.randomUUID();
        var repository = new FakeRepository(new PendingSectorRotationWindow(
                runId, LocalDate.parse("2026-01-31"), LocalDate.parse("2026-01-30"), 21));
        var unavailable = new EvaluateSectorRotationOutcomesService(repository,
                LoadSectorRotationPriceWindowPort.unavailable());
        assertEquals(0, unavailable.evaluate(10));

        var returns = new LinkedHashMap<String, Double>();
        for (var key : List.of("XLK", "XLF", "XLE", "XLV", "XLI", "XLY", "XLC", "XLB", "XLRE", "XLU", "XLP")) {
            returns.put("SECTOR_" + key, 5d);
        }
        var prices = new LoadSectorRotationPriceWindowPort() {
            @Override public Optional<LocalDate> latestCompletedCommonDate(Instant calculatedAt) {
                return Optional.empty();
            }
            @Override public Optional<SectorRotationForwardWindow> loadForwardWindow(
                    LocalDate startOn, int tradingSessions) {
                return Optional.of(new SectorRotationForwardWindow(
                        startOn, startOn.plusDays(30), tradingSessions, 3d, 5d, returns));
            }
        };
        assertEquals(11, new EvaluateSectorRotationOutcomesService(repository, prices).evaluate(10));
        assertEquals(11, repository.outcomes.size());
        assertEquals(2d, repository.outcomes.getFirst().benchmarkExcessReturnPct());
    }

    private static final class FakeRepository implements SectorRotationValidationRepository {
        private final PendingSectorRotationWindow pending;
        private List<SectorRotationOutcome> outcomes = List.of();
        private FakeRepository(PendingSectorRotationWindow pending) { this.pending = pending; }
        @Override public boolean append(SectorRotationCompositeSnapshot snapshot) { return false; }
        @Override public List<PendingSectorRotationWindow> loadPendingWindows(int limit) { return List.of(pending); }
        @Override public int appendOutcomes(List<SectorRotationOutcome> values) {
            outcomes = List.copyOf(values);
            return values.size();
        }
    }
}
