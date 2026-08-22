package io.macrosquare.research.application.port.in;

import io.macrosquare.research.domain.rotation.SectorWalkForwardBacktest;

public interface RunSectorRotationWalkForwardBacktestUseCase {
    SectorWalkForwardBacktest run(int years);
}
