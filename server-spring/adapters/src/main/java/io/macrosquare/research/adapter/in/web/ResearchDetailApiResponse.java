package io.macrosquare.research.adapter.in.web;

import io.macrosquare.research.application.model.ResearchCatalogModels;

import java.util.List;

public final class ResearchDetailApiResponse {

    private ResearchDetailApiResponse() {
    }

    public record ThemeDetail(
            ThemeDefinition theme,
            List<Company> items,
            List<SectorScore> sectorScores,
            SectorSummary sectorSummary,
            String sortKey,
            String companySortKey
    ) {
        static ThemeDetail from(ResearchCatalogModels.ThemeDetail source) {
            return new ThemeDetail(
                    ThemeDefinition.from(source.theme()),
                    source.items().stream().map(Company::from).toList(),
                    source.sectorScores().stream().map(SectorScore::from).toList(),
                    SectorSummary.from(source.sectorSummary()),
                    source.sortKey(),
                    source.companySortKey()
            );
        }
    }

    public record ThemeDefinition(
            String id,
            String theme,
            String description,
            List<String> tickers,
            List<String> sectorKeys
    ) {
        static ThemeDefinition from(ResearchCatalogModels.ThemeDefinition source) {
            return new ThemeDefinition(
                    source.id(), source.theme(), source.description(), source.tickers(), source.sectorKeys()
            );
        }
    }

    public record SectorDetail(
            SectorDefinition sector,
            String sortKey,
            List<ResearchCatalogApiResponse.RelatedTheme> relatedThemes,
            List<SectorScore> sectorScores,
            SectorSummary sectorSummary,
            ResearchCatalogApiResponse.RotationSector rotation,
            ResearchCatalogApiResponse.RotationSummary rotationSummary,
            ResearchCatalogApiResponse.DensitySummary densitySummary,
            List<Company> items
    ) {
        static SectorDetail from(ResearchCatalogModels.SectorDetail source) {
            return new SectorDetail(
                    SectorDefinition.from(source.sector()),
                    source.sortKey(),
                    source.relatedThemes().stream().map(ResearchCatalogApiResponse.RelatedTheme::from).toList(),
                    source.sectorScores().stream().map(SectorScore::from).toList(),
                    SectorSummary.from(source.sectorSummary()),
                    ResearchCatalogApiResponse.RotationSector.from(source.rotation()),
                    ResearchCatalogApiResponse.RotationSummary.from(source.rotationSummary()),
                    ResearchCatalogApiResponse.DensitySummary.from(source.densitySummary()),
                    source.items().stream().map(Company::from).toList()
            );
        }
    }

    public record SectorDefinition(
            String id,
            String label,
            String description,
            String sectorKey,
            List<String> tickers
    ) {
        static SectorDefinition from(ResearchCatalogModels.SectorDefinition source) {
            return new SectorDefinition(
                    source.id(), source.label(), source.description(), source.sectorKey(), source.tickers()
            );
        }
    }

    public sealed interface Company permits CompanyView, FailedCompanyView {
        static Company from(ResearchCatalogModels.CompanyItem source) {
            if (source.error() != null) return FailedCompanyView.from(source);
            return CompanyView.from(source);
        }
    }

    public record CompanyView(
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
            int rank
    ) implements Company {
        static CompanyView from(ResearchCatalogModels.CompanyItem source) {
            return new CompanyView(
                    source.ticker(), source.name(), source.marketCap(), source.totalScore(), source.buyScore(),
                    source.buyLabel(), source.appealScore(), source.crowdingScore(), source.revenueGrowthYoY(),
                    source.operatingMargin(), source.evToSales(), source.sectorKey(), source.bottomScore(),
                    source.priceBottomScore(), source.volumeConfirmationScore(), source.failureRiskScore(),
                    source.bottomState(), source.confirmedBottomScore(), source.confirmedBottomState(), source.rank()
            );
        }
    }

    public record FailedCompanyView(
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
            String error,
            int rank
    ) implements Company {
        static FailedCompanyView from(ResearchCatalogModels.CompanyItem source) {
            return new FailedCompanyView(
                    source.ticker(), source.name(), source.marketCap(), source.totalScore(), source.buyScore(),
                    source.buyLabel(), source.appealScore(), source.crowdingScore(), source.revenueGrowthYoY(),
                    source.operatingMargin(), source.evToSales(), source.sectorKey(), source.bottomScore(),
                    source.priceBottomScore(), source.volumeConfirmationScore(), source.failureRiskScore(),
                    source.bottomState(), source.confirmedBottomScore(), source.confirmedBottomState(),
                    source.error(), source.rank()
            );
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
        static SectorSummary from(ResearchCatalogModels.SectorSummary source) {
            if (source == null) return null;
            return new SectorSummary(
                    source.averageBuyScore(), source.averageBottomScore(),
                    source.averageBottomFailureRiskScore(), source.averageVolumeConfirmationScore(),
                    source.averageAppealScore(), source.averageCrowdingScore(), source.averageQualityScore(),
                    source.averageRotationScore(), SectorScore.from(source.topSector())
            );
        }
    }

    public sealed interface SectorScore permits BaseSectorScore, EnrichedSectorScore {
        static SectorScore from(ResearchCatalogModels.SectorScore source) {
            if (source == null) return null;
            if (source.averageVolumeConfirmationScorePresent()) return EnrichedSectorScore.from(source);
            return BaseSectorScore.from(source);
        }
    }

    public record BaseSectorScore(
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
            Number buyScoreDelta7d,
            Number buyScoreDelta30d,
            List<Integer> buyScoreTrend
    ) implements SectorScore {
        static BaseSectorScore from(ResearchCatalogModels.SectorScore source) {
            return new BaseSectorScore(
                    source.key(), source.label(), source.classification(), source.momentumScore(),
                    source.qualityScore(), source.policySupport(), source.structuralDemand(),
                    source.supplyTightness(), source.marketConcentration(), source.appealScore(),
                    source.crowdingScore(), source.buyScore(), source.buyLabel(), source.stance(),
                    source.rotationScore(), source.rotationState(), source.rotationLabel(),
                    source.rotationReasons(), source.bottomState(), source.bottomScore(),
                    source.bottomFailureRiskScore(), source.actionLabel(), source.failureSummary(),
                    jsonNumber(source.buyScoreDelta7d()), jsonNumber(source.buyScoreDelta30d()),
                    source.buyScoreTrend()
            );
        }
    }

    public record EnrichedSectorScore(
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
            Integer avgVolumeConfirmationScore,
            Number buyScoreDelta7d,
            Number buyScoreDelta30d,
            List<Integer> buyScoreTrend
    ) implements SectorScore {
        static EnrichedSectorScore from(ResearchCatalogModels.SectorScore source) {
            return new EnrichedSectorScore(
                    source.key(), source.label(), source.classification(), source.momentumScore(),
                    source.qualityScore(), source.policySupport(), source.structuralDemand(),
                    source.supplyTightness(), source.marketConcentration(), source.appealScore(),
                    source.crowdingScore(), source.buyScore(), source.buyLabel(), source.stance(),
                    source.rotationScore(), source.rotationState(), source.rotationLabel(),
                    source.rotationReasons(), source.bottomState(), source.bottomScore(),
                    source.bottomFailureRiskScore(), source.actionLabel(), source.failureSummary(),
                    source.averageVolumeConfirmationScore(), jsonNumber(source.buyScoreDelta7d()),
                    jsonNumber(source.buyScoreDelta30d()), source.buyScoreTrend()
            );
        }
    }

    private static Number jsonNumber(Double value) {
        if (value == null) return null;
        if (value == Math.rint(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
            return value.longValue();
        }
        return value;
    }
}
