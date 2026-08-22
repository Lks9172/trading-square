package io.macrosquare.company.adapter.out.research;

import io.macrosquare.company.application.model.CompanySectorAssessment;
import io.macrosquare.company.application.port.out.LoadCompanySectorAssessmentPort;
import io.macrosquare.research.application.model.ResearchCatalogModels.RotationSector;
import io.macrosquare.research.application.model.ResearchCatalogModels.Sector;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorScore;
import io.macrosquare.research.application.port.in.QueryResearchCatalogUseCase;

import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Outer-layer anti-corruption adapter. It translates the research catalog read
 * model into company-owned scalar evidence without leaking research domain
 * objects into the company domain.
 */
public final class ResearchCatalogCompanySectorAssessmentAdapter
        implements LoadCompanySectorAssessmentPort {

    private final QueryResearchCatalogUseCase researchCatalog;

    public ResearchCatalogCompanySectorAssessmentAdapter(QueryResearchCatalogUseCase researchCatalog) {
        this.researchCatalog = Objects.requireNonNull(researchCatalog);
    }

    @Override
    public Optional<CompanySectorAssessment> load(String ticker) {
        var normalized = normalize(ticker);
        // The query use case owns the current-market overlay. Reading the raw
        // catalog here used to leak persisted rotation seeds into a company
        // verdict even when the sector page already showed a newer ranking.
        var catalog = researchCatalog.listSectors();
        var sector = catalog.sectors().stream()
                .filter(value -> value.tickers().stream().anyMatch(normalized::equalsIgnoreCase))
                .findFirst();
        if (sector.isEmpty()) return Optional.empty();

        var catalogSector = sector.orElseThrow();
        var ranking = catalog.sectors().stream()
                .filter(value -> sectorRotationScore(value) != null)
                .sorted(Comparator
                        .comparingInt((Sector value) -> sectorRotationScore(value))
                        .reversed()
                        .thenComparing(Sector::sectorKey))
                .toList();
        var rank = ranking.indexOf(catalogSector) + 1;
        var universeSize = ranking.size();
        var percentile = rank < 1 || universeSize < 1
                ? null
                : universeSize == 1
                ? 100
                : (int) Math.round((universeSize - rank) * 100.0 / (universeSize - 1));
        var rotation = catalogSector.rotation();
        var topSector = catalogSector.sectorSummary() == null
                ? null
                : catalogSector.sectorSummary().topSector();

        return Optional.of(toAssessment(
                catalogSector.id(),
                catalogSector.label(),
                catalogSector.sectorKey(),
                rotation,
                topSector,
                rank < 1 ? null : rank,
                universeSize < 1 ? null : universeSize,
                percentile
        ));
    }

    private static CompanySectorAssessment toAssessment(
            String sectorId,
            String label,
            String sectorKey,
            RotationSector rotation,
            SectorScore score,
            Integer rotationRank,
            Integer rotationUniverseSize,
            Integer rotationPercentile
    ) {
        var classification = rotation != null
                ? rotation.classification()
                : score != null && score.classification() != null
                ? score.classification()
                : "unknown";
        var reasons = rotation != null
                ? rotation.reasons()
                : score == null ? java.util.List.<String>of() : score.rotationReasons();
        var referenceRevision = rotation == null ? null : rotation.earningsRevisionScore();
        var rawFlow = rotation == null ? null : rotation.flowScore();
        var officialFlow = rotation != null && rotation.fundFlowObservedOn() != null;
        // The date is the provenance gate. Compatibility projections can still
        // contain an older style-flow score, but must never be relabelled as an
        // observed ETF creation/redemption flow.
        var independentFlow = officialFlow ? rawFlow : null;
        var proxyFlow = officialFlow ? null : rawFlow;
        return new CompanySectorAssessment(
                sectorId,
                label,
                sectorKey,
                classification,
                score == null ? null : score.buyScore(),
                score == null ? null : score.qualityScore(),
                score == null ? null : score.appealScore(),
                score == null ? null : score.crowdingScore(),
                rotation == null ? null : rotation.valuationScore(),
                null,
                referenceRevision,
                rotation == null ? score == null ? null : score.rotationScore() : rotation.rotationScore(),
                rotationRank,
                rotationUniverseSize,
                rotationPercentile,
                rotation == null ? null : rotation.macroFitScore(),
                rotation == null ? null : rotation.relativeStrengthScore(),
                rotation == null ? null : rotation.fundamentalScore(),
                independentFlow,
                proxyFlow,
                score == null || score.stance() == null ? "neutral" : score.stance(),
                rotation == null ? score == null ? "UNKNOWN" : score.rotationState() : rotation.state(),
                rotation == null ? score == null ? "" : score.rotationLabel() : rotation.rotationLabel(),
                rotation == null ? "" : rotation.expectedLeadershipWindow(),
                rotation == null ? "" : rotation.expectedLeadershipMessage(),
                reasons
        );
    }

    private static Integer sectorRotationScore(Sector value) {
        if (value.rotation() != null) return value.rotation().rotationScore();
        return value.sectorSummary() == null ? null : value.sectorSummary().averageRotationScore();
    }

    private static String normalize(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        return ticker.trim().toUpperCase(Locale.ROOT);
    }
}
