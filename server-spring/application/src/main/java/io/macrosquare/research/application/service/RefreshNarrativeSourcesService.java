package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.NarrativeSourceRefreshReport;
import io.macrosquare.research.application.port.in.RefreshNarrativeSourcesUseCase;
import io.macrosquare.research.application.port.out.CollectNarrativeSourcesPort;
import io.macrosquare.research.application.port.out.NarrativeSourceRepository;
import io.macrosquare.research.domain.narrative.NarrativeSourceStatus;

import java.time.Clock;
import java.util.Objects;

public final class RefreshNarrativeSourcesService implements RefreshNarrativeSourcesUseCase {

    private final NarrativeThemeCatalog themes;
    private final CollectNarrativeSourcesPort collector;
    private final NarrativeSourceRepository repository;
    private final Clock clock;

    public RefreshNarrativeSourcesService(
            NarrativeThemeCatalog themes,
            CollectNarrativeSourcesPort collector,
            NarrativeSourceRepository repository,
            Clock clock
    ) {
        this.themes = Objects.requireNonNull(themes);
        this.collector = Objects.requireNonNull(collector);
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public NarrativeSourceRefreshReport refresh() {
        var startedAt = clock.instant();
        var readings = collector.collect(themes.definitions());
        var persisted = repository.save(readings);
        return new NarrativeSourceRefreshReport(
                startedAt,
                clock.instant(),
                readings.size(),
                persisted,
                (int) readings.stream().filter(value -> value.status() == NarrativeSourceStatus.AVAILABLE).count(),
                (int) readings.stream().filter(value -> value.status() == NarrativeSourceStatus.MISSING).count(),
                (int) readings.stream().filter(value -> value.status() == NarrativeSourceStatus.FAILED).count()
        );
    }
}
