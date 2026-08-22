package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.NarrativeSnapshotMetadata;
import io.macrosquare.research.application.model.NarrativeThemeDefinition;
import io.macrosquare.research.application.model.NarrativeThemeView;
import io.macrosquare.research.application.model.ResearchSnapshot;
import io.macrosquare.research.application.port.in.NarrativeThemeNotFoundException;
import io.macrosquare.research.application.port.in.QueryNarrativesUseCase;
import io.macrosquare.research.application.port.out.LoadResearchSnapshotPort;
import io.macrosquare.research.application.port.out.NarrativeSourceRepository;
import io.macrosquare.research.application.port.out.ResearchSnapshotUnavailableException;
import io.macrosquare.research.domain.narrative.NarrativeEvidence;
import io.macrosquare.research.domain.narrative.NarrativeHeatPolicy;
import io.macrosquare.research.domain.narrative.NarrativeSourceObservation;
import io.macrosquare.research.domain.narrative.NarrativeSourcePolicy;
import io.macrosquare.research.domain.narrative.NarrativeTheme;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

public final class QueryNarrativesService implements QueryNarrativesUseCase {

    private final LoadResearchSnapshotPort snapshotPort;
    private final NarrativeHeatPolicy narrativePolicy;
    private final NarrativeThemeCatalog catalog;
    private final NarrativeSourceRepository sourceRepository;
    private final NarrativeSourcePolicy sourcePolicy;
    private final NarrativeSourceCatalog sourceCatalog;
    private final Clock clock;

    public QueryNarrativesService(
            LoadResearchSnapshotPort snapshotPort,
            NarrativeHeatPolicy narrativePolicy,
            NarrativeThemeCatalog catalog
    ) {
        this(
                snapshotPort, narrativePolicy, catalog,
                new NarrativeSourceRepository() {
                    @Override
                    public int save(List<io.macrosquare.research.domain.narrative.NarrativeSourceReading> readings) {
                        return 0;
                    }

                    @Override
                    public List<NarrativeSourceObservation> loadSince(LocalDate since) {
                        return List.of();
                    }
                },
                new NarrativeSourcePolicy(), new NarrativeSourceCatalog(), Clock.systemUTC());
    }

    public QueryNarrativesService(
            LoadResearchSnapshotPort snapshotPort,
            NarrativeHeatPolicy narrativePolicy,
            NarrativeThemeCatalog catalog,
            NarrativeSourceRepository sourceRepository,
            NarrativeSourcePolicy sourcePolicy,
            NarrativeSourceCatalog sourceCatalog,
            Clock clock
    ) {
        this.snapshotPort = Objects.requireNonNull(snapshotPort);
        this.narrativePolicy = Objects.requireNonNull(narrativePolicy);
        this.catalog = Objects.requireNonNull(catalog);
        this.sourceRepository = Objects.requireNonNull(sourceRepository);
        this.sourcePolicy = Objects.requireNonNull(sourcePolicy);
        this.sourceCatalog = Objects.requireNonNull(sourceCatalog);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public List<NarrativeThemeDefinition> listDefinitions() {
        return catalog.definitions();
    }

    @Override
    public List<NarrativeThemeView> getOverview() {
        var snapshot = snapshotPort.loadLatest();
        var observations = loadSourceObservations();
        return catalog.definitions().stream()
                .map(definition -> buildView(snapshot, definition, observations))
                .toList();
    }

    @Override
    public NarrativeThemeView getTheme(String themeId) {
        var theme = parseTheme(themeId);
        return buildView(snapshotPort.loadLatest(), catalog.definition(theme), loadSourceObservations());
    }

    private NarrativeThemeView buildView(
            ResearchSnapshot snapshot,
            NarrativeThemeDefinition definition,
            List<NarrativeSourceObservation> sourceObservations
    ) {
        var theme = definition.theme();
        var legacyState = snapshot.legacyNarratives().get(theme);
        var metadata = snapshot.narrativeMetadata().get(theme);
        if (legacyState == null || metadata == null) {
            throw new ResearchSnapshotUnavailableException("Legacy snapshot is missing narrative data for " + theme.id());
        }
        requireCompatibleDefinition(definition, metadata);
        var sourceAssessment = sourcePolicy.assess(
                theme,
                sourceCatalog.definitions(),
                sourceObservations,
                legacyState.externalSignals(),
                clock.instant()
        );
        var actualState = narrativePolicy.evaluate(
                theme,
                new NarrativeEvidence(
                        snapshot.rawValues(),
                        snapshot.derivedValues(),
                        snapshot.assetSignals(),
                        snapshot.manualEvidence(),
                        sourceAssessment.signals()
                )
        );
        return new NarrativeThemeView(
                definition,
                metadata.generatedAt(),
                actualState,
                metadata.trend(),
                metadata.heatDelta7d(),
                metadata.heatDelta30d(),
                metadata.heatHistory(),
                sourceAssessment
        );
    }

    private List<NarrativeSourceObservation> loadSourceObservations() {
        var since = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(45);
        return sourceRepository.loadSince(since);
    }

    private static void requireCompatibleDefinition(
            NarrativeThemeDefinition expected,
            NarrativeSnapshotMetadata metadata
    ) {
        if (!expected.equals(metadata.definition())) {
            throw new ResearchSnapshotUnavailableException(
                    "Legacy narrative definition drifted for " + expected.theme().id()
            );
        }
    }

    private static NarrativeTheme parseTheme(String themeId) {
        if (themeId == null || themeId.isBlank()) throw new NarrativeThemeNotFoundException();
        try {
            return NarrativeTheme.fromId(themeId.trim());
        } catch (IllegalArgumentException error) {
            throw new NarrativeThemeNotFoundException();
        }
    }
}
