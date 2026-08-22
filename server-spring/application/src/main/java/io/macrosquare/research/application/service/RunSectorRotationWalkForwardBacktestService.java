package io.macrosquare.research.application.service;

import io.macrosquare.research.application.port.in.RunSectorRotationWalkForwardBacktestUseCase;
import io.macrosquare.research.application.port.out.LoadSectorTotalReturnHistoryPort;
import io.macrosquare.research.domain.rotation.SectorWalkForwardBacktest;
import io.macrosquare.research.domain.rotation.SectorWalkForwardBacktestPolicy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Objects;

public final class RunSectorRotationWalkForwardBacktestService
        implements RunSectorRotationWalkForwardBacktestUseCase {

    private final LoadSectorTotalReturnHistoryPort histories;
    private final SectorWalkForwardBacktestPolicy policy;
    private final Clock clock;

    public RunSectorRotationWalkForwardBacktestService(
            LoadSectorTotalReturnHistoryPort histories,
            SectorWalkForwardBacktestPolicy policy,
            Clock clock
    ) {
        this.histories = Objects.requireNonNull(histories);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public SectorWalkForwardBacktest run(int years) {
        var input = new LinkedHashMap<String, java.util.List<io.macrosquare.research.domain.rotation.SectorTotalReturnPoint>>();
        input.put(SectorWalkForwardBacktestPolicy.BENCHMARK_KEY,
                histories.load(SectorWalkForwardBacktestPolicy.BENCHMARK_KEY));
        SectorWalkForwardBacktestPolicy.SECTOR_KEYS.forEach(key -> input.put(key, histories.load(key)));
        return policy.evaluate(input, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC), years);
    }
}
