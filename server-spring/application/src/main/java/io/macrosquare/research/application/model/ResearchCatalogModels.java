package io.macrosquare.research.application.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transport-neutral projections used by the research catalog query side.
 *
 * <p>These records intentionally model the complete legacy read contract while the
 * underlying calculations are migrated incrementally. They contain no HTTP or JSON
 * concerns and can later be populated directly from Spring-owned domain policies.</p>
 */
public final class ResearchCatalogModels {

    private ResearchCatalogModels() {
    }

    public record ThemeCatalog(List<Theme> themes) {
        public ThemeCatalog {
            themes = List.copyOf(themes);
        }
    }

    public record Theme(
            String id,
            String theme,
            String description,
            List<String> tickers,
            List<String> sectorKeys,
            SectorSummary sectorSummary
    ) {
        public Theme {
            tickers = List.copyOf(tickers);
            sectorKeys = List.copyOf(sectorKeys);
        }
    }

    public record SectorCatalog(List<Sector> sectors, RotationSummary rotation) {
        public SectorCatalog {
            sectors = List.copyOf(sectors);
        }
    }

    public record Sector(
            String id,
            String label,
            String description,
            String sectorKey,
            List<String> tickers,
            SectorSummary sectorSummary,
            RotationSector rotation,
            DensitySummary densitySummary,
            List<RelatedTheme> relatedThemes
    ) {
        public Sector {
            tickers = List.copyOf(tickers);
            relatedThemes = List.copyOf(relatedThemes);
        }
    }

    public record SectorSummary(
            Integer averageBuyScore,
            Integer averageBottomScore,
            Integer averageBottomFailureRiskScore,
            Integer averageVolumeConfirmationScore,
            Integer averageAppealScore,
            Integer averageCrowdingScore,
            Integer averageQualityScore,
            Integer averageRotationScore,
            SectorScore topSector
    ) {
    }

    public record SectorScore(
            String key,
            String label,
            String classification,
            Double momentumScore,
            Integer qualityScore,
            Integer policySupport,
            Integer structuralDemand,
            Integer supplyTightness,
            Integer marketConcentration,
            Integer appealScore,
            Integer crowdingScore,
            Integer buyScore,
            String buyLabel,
            String stance,
            Integer rotationScore,
            String rotationState,
            String rotationLabel,
            List<String> rotationReasons,
            String bottomState,
            Integer bottomScore,
            Integer bottomFailureRiskScore,
            String actionLabel,
            String failureSummary,
            Integer averageVolumeConfirmationScore,
            boolean averageVolumeConfirmationScorePresent,
            Double buyScoreDelta7d,
            Double buyScoreDelta30d,
            List<Integer> buyScoreTrend,
            boolean trendPresent
    ) {
        public SectorScore {
            rotationReasons = List.copyOf(rotationReasons);
            buyScoreTrend = immutableNullableList(buyScoreTrend);
        }
    }

    public record ThemeDetail(
            ThemeDefinition theme,
            List<CompanyItem> items,
            List<SectorScore> sectorScores,
            SectorSummary sectorSummary,
            String sortKey,
            String companySortKey
    ) {
        public ThemeDetail {
            items = List.copyOf(items);
            sectorScores = List.copyOf(sectorScores);
        }
    }

    public record ThemeDefinition(
            String id,
            String theme,
            String description,
            List<String> tickers,
            List<String> sectorKeys
    ) {
        public ThemeDefinition {
            tickers = List.copyOf(tickers);
            sectorKeys = List.copyOf(sectorKeys);
        }
    }

    public record SectorDetail(
            SectorDefinition sector,
            String sortKey,
            List<RelatedTheme> relatedThemes,
            List<SectorScore> sectorScores,
            SectorSummary sectorSummary,
            RotationSector rotation,
            RotationSummary rotationSummary,
            DensitySummary densitySummary,
            List<CompanyItem> items
    ) {
        public SectorDetail {
            relatedThemes = List.copyOf(relatedThemes);
            sectorScores = List.copyOf(sectorScores);
            items = List.copyOf(items);
        }
    }

    public record SectorDefinition(
            String id,
            String label,
            String description,
            String sectorKey,
            List<String> tickers
    ) {
        public SectorDefinition {
            tickers = List.copyOf(tickers);
        }
    }

    public record CompanyItem(
            String ticker,
            String name,
            Number marketCap,
            Integer totalScore,
            Integer buyScore,
            String buyLabel,
            Integer appealScore,
            Integer crowdingScore,
            Number revenueGrowthYoY,
            Number operatingMargin,
            Number evToSales,
            String sectorKey,
            Integer bottomScore,
            Integer priceBottomScore,
            Integer volumeConfirmationScore,
            Integer failureRiskScore,
            String bottomState,
            Integer confirmedBottomScore,
            String confirmedBottomState,
            int rank,
            String error
    ) {
    }

    public record RotationSector(
            String key,
            String label,
            String classification,
            int rotationScore,
            int macroFitScore,
            int relativeStrengthScore,
            int fundamentalScore,
            Integer valuationScore,
            Integer earningsRevisionScore,
            String earningsRevisionObservedOn,
            Integer earningsRevisionCoveragePct,
            Integer earningsRevisionUpPct,
            Integer earningsRevisionDownPct,
            Integer flowScore,
            String fundFlowObservedOn,
            Double fundFlow1dUsd,
            Double fundFlow5dUsd,
            Double fundFlow20dUsd,
            Double fundFlow5dPct,
            Double fundFlow20dPct,
            Integer priceBreadthScore,
            String priceBreadthObservedOn,
            Integer priceBreadthCoveragePct,
            Integer aboveMa20Pct,
            Integer aboveMa50Pct,
            Integer aboveMa200Pct,
            int crowdingReliefScore,
            String state,
            String rotationLabel,
            String expectedLeadershipWindow,
            String expectedLeadershipMessage,
            List<String> reasons
    ) {
        public RotationSector(
                String key,
                String label,
                String classification,
                int rotationScore,
                int macroFitScore,
                int relativeStrengthScore,
                int fundamentalScore,
                Integer valuationScore,
                Integer earningsRevisionScore,
                Integer flowScore,
                int crowdingReliefScore,
                String state,
                String rotationLabel,
                String expectedLeadershipWindow,
                String expectedLeadershipMessage,
                List<String> reasons
        ) {
            this(key, label, classification, rotationScore, macroFitScore,
                    relativeStrengthScore, fundamentalScore, valuationScore,
                    earningsRevisionScore, null, null, null, null, flowScore,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    crowdingReliefScore, state, rotationLabel,
                    expectedLeadershipWindow, expectedLeadershipMessage, reasons);
        }

        public RotationSector {
            reasons = List.copyOf(reasons);
        }
    }

    public record RotationSummary(
            String regime,
            int confidence,
            Map<String, Integer> regimeScores,
            String summary,
            List<String> favoredNext,
            List<String> fadingNext,
            List<RotationCandidate> currentLeaders,
            List<RotationCandidate> nextCandidates,
            List<RotationCandidate> secondaryCandidates,
            List<RotationCandidate> fadingCandidates,
            String calculatedAt,
            boolean currentMarketOverlay,
            String methodology
    ) {
        public RotationSummary(
                String regime,
                int confidence,
                String summary,
                List<String> favoredNext,
                List<String> fadingNext,
                List<RotationCandidate> currentLeaders,
                List<RotationCandidate> nextCandidates,
                List<RotationCandidate> secondaryCandidates,
                List<RotationCandidate> fadingCandidates
        ) {
            this(regime, confidence, Map.of(), summary, favoredNext, fadingNext, currentLeaders,
                    nextCandidates, secondaryCandidates, fadingCandidates, null, false,
                    "persisted-reference");
        }

        public RotationSummary {
            regimeScores = Collections.unmodifiableMap(new LinkedHashMap<>(
                    regimeScores == null ? Map.of() : regimeScores));
            favoredNext = List.copyOf(favoredNext);
            fadingNext = List.copyOf(fadingNext);
            currentLeaders = List.copyOf(currentLeaders);
            nextCandidates = List.copyOf(nextCandidates);
            secondaryCandidates = List.copyOf(secondaryCandidates);
            fadingCandidates = List.copyOf(fadingCandidates);
            methodology = methodology == null ? "" : methodology;
        }
    }

    public record RotationCandidate(
            String label,
            String sectorKey,
            int rotationScore,
            String state,
            String rotationLabel,
            String expectedLeadershipWindow,
            String expectedLeadershipMessage,
            String note,
            String confirmationState,
            Integer confirmationScore,
            Integer confirmationCoveragePct,
            String confirmationLabel,
            List<String> confirmationReasons,
            List<String> invalidationSignals
    ) {
        public RotationCandidate(
                String label,
                String sectorKey,
                int rotationScore,
                String state,
                String rotationLabel,
                String expectedLeadershipWindow,
                String expectedLeadershipMessage,
                String note
        ) {
            this(label, sectorKey, rotationScore, state, rotationLabel,
                    expectedLeadershipWindow, expectedLeadershipMessage, note,
                    null, null, null, null, List.of(), List.of());
        }

        public RotationCandidate {
            confirmationReasons = List.copyOf(
                    confirmationReasons == null ? List.of() : confirmationReasons);
            invalidationSignals = List.copyOf(
                    invalidationSignals == null ? List.of() : invalidationSignals);
        }
    }

    public record DensitySummary(
            int peer,
            int peerPct,
            int narrative,
            int narrativePct,
            int fallback,
            int fallbackPct,
            int bottleneck,
            int bottleneckPct,
            int capitalFlow,
            int capitalFlowPct
    ) {
    }

    public record RelatedTheme(String id, String theme) {
    }

    private static <T> List<T> immutableNullableList(List<T> source) {
        if (source == null) return null;
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
