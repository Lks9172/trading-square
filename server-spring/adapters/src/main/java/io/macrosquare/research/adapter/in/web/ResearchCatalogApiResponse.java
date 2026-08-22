package io.macrosquare.research.adapter.in.web;

import io.macrosquare.research.application.model.ResearchCatalogModels;

import java.util.List;
import java.util.Map;

public final class ResearchCatalogApiResponse {

    private ResearchCatalogApiResponse() {
    }

    public record ThemeCatalog(List<Theme> themes) {
        static ThemeCatalog from(ResearchCatalogModels.ThemeCatalog catalog) {
            return new ThemeCatalog(catalog.themes().stream().map(Theme::from).toList());
        }
    }

    public record Theme(
            String id,
            String theme,
            String description,
            List<String> tickers,
            List<String> sectorKeys,
            ThemeSectorSummary sectorSummary
    ) {
        static Theme from(ResearchCatalogModels.Theme source) {
            return new Theme(
                    source.id(),
                    source.theme(),
                    source.description(),
                    source.tickers(),
                    source.sectorKeys(),
                    ThemeSectorSummary.from(source.sectorSummary())
            );
        }
    }

    public record SectorCatalog(List<Sector> sectors, RotationSummary rotation) {
        static SectorCatalog from(ResearchCatalogModels.SectorCatalog catalog) {
            return new SectorCatalog(
                    catalog.sectors().stream().map(Sector::from).toList(),
                    RotationSummary.from(catalog.rotation())
            );
        }
    }

    public record Sector(
            String id,
            String label,
            String description,
            String sectorKey,
            List<String> tickers,
            SectorSectorSummary sectorSummary,
            RotationSector rotation,
            DensitySummary densitySummary,
            List<RelatedTheme> relatedThemes
    ) {
        static Sector from(ResearchCatalogModels.Sector source) {
            return new Sector(
                    source.id(),
                    source.label(),
                    source.description(),
                    source.sectorKey(),
                    source.tickers(),
                    SectorSectorSummary.from(source.sectorSummary()),
                    RotationSector.from(source.rotation()),
                    DensitySummary.from(source.densitySummary()),
                    source.relatedThemes().stream().map(RelatedTheme::from).toList()
            );
        }
    }

    public record ThemeSectorSummary(
            Integer averageBuyScore,
            Integer averageBottomScore,
            Integer averageBottomFailureRiskScore,
            Integer averageVolumeConfirmationScore,
            Integer averageAppealScore,
            Integer averageCrowdingScore,
            Integer averageQualityScore,
            Integer averageRotationScore,
            ThemeSectorScore topSector
    ) {
        static ThemeSectorSummary from(ResearchCatalogModels.SectorSummary source) {
            if (source == null) return null;
            return new ThemeSectorSummary(
                    source.averageBuyScore(),
                    source.averageBottomScore(),
                    source.averageBottomFailureRiskScore(),
                    source.averageVolumeConfirmationScore(),
                    source.averageAppealScore(),
                    source.averageCrowdingScore(),
                    source.averageQualityScore(),
                    source.averageRotationScore(),
                    ThemeSectorScore.from(source.topSector())
            );
        }
    }

    public record SectorSectorSummary(
            Integer averageBuyScore,
            Integer averageBottomScore,
            Integer averageBottomFailureRiskScore,
            Integer averageVolumeConfirmationScore,
            Integer averageAppealScore,
            Integer averageCrowdingScore,
            Integer averageQualityScore,
            Integer averageRotationScore,
            SectorSectorScore topSector
    ) {
        static SectorSectorSummary from(ResearchCatalogModels.SectorSummary source) {
            if (source == null) return null;
            return new SectorSectorSummary(
                    source.averageBuyScore(),
                    source.averageBottomScore(),
                    source.averageBottomFailureRiskScore(),
                    source.averageVolumeConfirmationScore(),
                    source.averageAppealScore(),
                    source.averageCrowdingScore(),
                    source.averageQualityScore(),
                    source.averageRotationScore(),
                    SectorSectorScore.from(source.topSector())
            );
        }
    }

    public record ThemeSectorScore(
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
            String failureSummary
    ) {
        static ThemeSectorScore from(ResearchCatalogModels.SectorScore source) {
            if (source == null) return null;
            return new ThemeSectorScore(
                    source.key(), source.label(), source.classification(), source.momentumScore(),
                    source.qualityScore(), source.policySupport(), source.structuralDemand(),
                    source.supplyTightness(), source.marketConcentration(), source.appealScore(),
                    source.crowdingScore(), source.buyScore(), source.buyLabel(), source.stance(),
                    source.rotationScore(), source.rotationState(), source.rotationLabel(),
                    source.rotationReasons(), source.bottomState(), source.bottomScore(),
                    source.bottomFailureRiskScore(), source.actionLabel(), source.failureSummary()
            );
        }
    }

    public record SectorSectorScore(
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
            Integer avgVolumeConfirmationScore
    ) {
        static SectorSectorScore from(ResearchCatalogModels.SectorScore source) {
            if (source == null) return null;
            return new SectorSectorScore(
                    source.key(), source.label(), source.classification(), source.momentumScore(),
                    source.qualityScore(), source.policySupport(), source.structuralDemand(),
                    source.supplyTightness(), source.marketConcentration(), source.appealScore(),
                    source.crowdingScore(), source.buyScore(), source.buyLabel(), source.stance(),
                    source.rotationScore(), source.rotationState(), source.rotationLabel(),
                    source.rotationReasons(), source.bottomState(), source.bottomScore(),
                    source.bottomFailureRiskScore(), source.actionLabel(), source.failureSummary(),
                    source.averageVolumeConfirmationScore()
            );
        }
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
        static RotationSector from(ResearchCatalogModels.RotationSector source) {
            if (source == null) return null;
            return new RotationSector(
                    source.key(), source.label(), source.classification(), source.rotationScore(),
                    source.macroFitScore(), source.relativeStrengthScore(), source.fundamentalScore(),
                    source.valuationScore(), source.earningsRevisionScore(),
                    source.earningsRevisionObservedOn(), source.earningsRevisionCoveragePct(),
                    source.earningsRevisionUpPct(), source.earningsRevisionDownPct(), source.flowScore(),
                    source.fundFlowObservedOn(), source.fundFlow1dUsd(), source.fundFlow5dUsd(),
                    source.fundFlow20dUsd(), source.fundFlow5dPct(), source.fundFlow20dPct(),
                    source.priceBreadthScore(), source.priceBreadthObservedOn(),
                    source.priceBreadthCoveragePct(), source.aboveMa20Pct(), source.aboveMa50Pct(),
                    source.aboveMa200Pct(),
                    source.crowdingReliefScore(), source.state(), source.rotationLabel(),
                    source.expectedLeadershipWindow(), source.expectedLeadershipMessage(), source.reasons()
            );
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
        static RotationSummary from(ResearchCatalogModels.RotationSummary source) {
            if (source == null) return null;
            return new RotationSummary(
                    source.regime(), source.confidence(), source.regimeScores(),
                    source.summary(), source.favoredNext(),
                    source.fadingNext(), source.currentLeaders().stream().map(RotationCandidate::from).toList(),
                    source.nextCandidates().stream().map(RotationCandidate::from).toList(),
                    source.secondaryCandidates().stream().map(RotationCandidate::from).toList(),
                    source.fadingCandidates().stream().map(RotationCandidate::from).toList(),
                    source.calculatedAt(), source.currentMarketOverlay(), source.methodology()
            );
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

        static RotationCandidate from(ResearchCatalogModels.RotationCandidate source) {
            return new RotationCandidate(
                    source.label(), source.sectorKey(), source.rotationScore(), source.state(),
                    source.rotationLabel(), source.expectedLeadershipWindow(),
                    source.expectedLeadershipMessage(), source.note(), source.confirmationState(),
                    source.confirmationScore(), source.confirmationCoveragePct(),
                    source.confirmationLabel(), source.confirmationReasons(),
                    source.invalidationSignals()
            );
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
        static DensitySummary from(ResearchCatalogModels.DensitySummary source) {
            return new DensitySummary(
                    source.peer(), source.peerPct(), source.narrative(), source.narrativePct(),
                    source.fallback(), source.fallbackPct(), source.bottleneck(), source.bottleneckPct(),
                    source.capitalFlow(), source.capitalFlowPct()
            );
        }
    }

    public record RelatedTheme(String id, String theme) {
        static RelatedTheme from(ResearchCatalogModels.RelatedTheme source) {
            return new RelatedTheme(source.id(), source.theme());
        }
    }
}
