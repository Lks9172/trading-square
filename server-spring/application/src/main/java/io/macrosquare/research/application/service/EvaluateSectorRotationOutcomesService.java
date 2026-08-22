package io.macrosquare.research.application.service;

import io.macrosquare.research.application.port.in.EvaluateSectorRotationOutcomesUseCase;
import io.macrosquare.research.application.port.out.LoadSectorRotationPriceWindowPort;
import io.macrosquare.research.application.port.out.SectorRotationValidationRepository;
import io.macrosquare.research.domain.rotation.SectorRotationOutcome;

import java.util.ArrayList;
import java.util.Objects;

/** Materializes outcomes only after the requested future trading window exists. */
public final class EvaluateSectorRotationOutcomesService implements EvaluateSectorRotationOutcomesUseCase {

    private final SectorRotationValidationRepository repository;
    private final LoadSectorRotationPriceWindowPort prices;

    public EvaluateSectorRotationOutcomesService(
            SectorRotationValidationRepository repository,
            LoadSectorRotationPriceWindowPort prices
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.prices = Objects.requireNonNull(prices);
    }

    @Override
    public int evaluate(int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        var written = 0;
        for (var pending : repository.loadPendingWindows(limit)) {
            var window = prices.loadForwardWindow(pending.priceAnchorOn(), pending.tradingSessions());
            if (window.isEmpty()) continue;
            var value = window.orElseThrow();
            var outcomes = new ArrayList<SectorRotationOutcome>();
            value.sectorReturnsPct().forEach((sectorKey, sectorReturn) -> outcomes.add(new SectorRotationOutcome(
                    pending.runId(), sectorKey, pending.tradingSessions(), value.startOn(), value.endOn(),
                    sectorReturn, value.benchmarkReturnPct(), value.universeEqualWeightReturnPct())));
            written += repository.appendOutcomes(outcomes);
        }
        return written;
    }
}
