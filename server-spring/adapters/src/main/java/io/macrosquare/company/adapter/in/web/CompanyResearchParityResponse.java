package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.CompanyResearchParityReport;
import io.macrosquare.company.application.model.CompanyAnalystHistoryRead;
import io.macrosquare.company.application.model.CompanyMarketQuote;
import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import io.macrosquare.company.domain.model.CompanyBuyLabel;
import io.macrosquare.company.domain.model.CompanyBuyScore;
import io.macrosquare.company.domain.model.CompanyFundamentalsSnapshot;
import io.macrosquare.company.domain.model.CompanyFundamentalsFreshness;
import io.macrosquare.company.domain.model.CompanyMarketExpectations;
import io.macrosquare.company.domain.model.CompanyScore;
import io.macrosquare.company.domain.model.ScoreBreakdown;

import java.util.List;
import java.time.LocalDate;

public record CompanyResearchParityResponse(
        String ticker,
        String cik,
        String registryCik,
        boolean legacyAvailable,
        boolean allMatched,
        boolean identityMatched,
        boolean quoteMatched,
        boolean analystConsensusMatched,
        boolean analystHistoryMatched,
        boolean expectationsMatched,
        boolean fundamentalsMatched,
        boolean scoreMatched,
        boolean buyScoreMatched,
        List<String> differences,
        ComparisonResponse<QuoteResponse> quote,
        ComparisonResponse<AnalystConsensusResponse> analystConsensus,
        AnalystHistoryResponse analystHistory,
        ComparisonResponse<ExpectationsResponse> expectations,
        ComparisonResponse<FundamentalsResponse> fundamentals,
        ComparisonResponse<ScoreResponse> score,
        ComparisonResponse<BuyScoreResponse> buyScore,
        FreshnessResponse fundamentalsFreshness
) {
    static CompanyResearchParityResponse from(CompanyResearchParityReport report) {
        return new CompanyResearchParityResponse(
                report.ticker(),
                report.cik(),
                report.registryCik(),
                report.legacyAvailable(),
                report.allMatched(),
                report.identityMatched(),
                report.quoteMatched(),
                report.analystConsensusMatched(),
                report.analystHistoryMatched(),
                report.expectationsMatched(),
                report.fundamentalsMatched(),
                report.scoreMatched(),
                report.buyScoreMatched(),
                report.differences(),
                new ComparisonResponse<>(
                        QuoteResponse.from(report.legacyQuote()),
                        QuoteResponse.from(report.springQuote())
                ),
                new ComparisonResponse<>(
                        AnalystConsensusResponse.from(report.legacyAnalystConsensus()),
                        AnalystConsensusResponse.from(report.springAnalystConsensus())
                ),
                AnalystHistoryResponse.from(report.analystHistory()),
                new ComparisonResponse<>(
                        ExpectationsResponse.from(report.legacyExpectations()),
                        ExpectationsResponse.from(report.springExpectations())
                ),
                new ComparisonResponse<>(
                        FundamentalsResponse.from(report.legacyFundamentals()),
                        FundamentalsResponse.from(report.springFundamentals())
                ),
                new ComparisonResponse<>(
                        ScoreResponse.from(report.legacyScore()),
                        ScoreResponse.from(report.springScore())
                ),
                new ComparisonResponse<>(
                        BuyScoreResponse.from(report.legacyBuyScore()),
                        BuyScoreResponse.from(report.springBuyScore())
                ),
                FreshnessResponse.from(report.fundamentalsFreshness())
        );
    }

    public record FreshnessResponse(
            String status,
            LocalDate fundamentalsAsOf,
            LocalDate latestPeriodicReportDate,
            LocalDate latestPeriodicFilingDate,
            String latestPeriodicForm,
            Integer lagDays,
            boolean scoreComparable,
            List<String> warnings
    ) {
        static FreshnessResponse from(CompanyFundamentalsFreshness value) {
            return new FreshnessResponse(
                    value.status().name(), value.fundamentalsAsOf(), value.latestPeriodicReportDate(),
                    value.latestPeriodicFilingDate(), value.latestPeriodicForm(), value.lagDays(),
                    value.scoreComparable(), value.warnings()
            );
        }
    }

    public record ComparisonResponse<T>(T legacy, T spring) {
    }

    public record QuoteResponse(String symbol, Double price, LocalDate date) {
        static QuoteResponse from(CompanyMarketQuote value) {
            return new QuoteResponse(value.symbol(), value.price(), value.date());
        }
    }

    public record AnalystConsensusResponse(
            Double analystScore,
            Double upsidePct,
            Double epsEstimateRevision7dPct,
            Double epsEstimateRevision30dPct,
            Double epsEstimateRevision90dPct
    ) {
        static AnalystConsensusResponse from(CompanyAnalystConsensus value) {
            return new AnalystConsensusResponse(
                    value.analystScore(), value.upsidePct(),
                    value.epsEstimateRevision7dPct(), value.epsEstimateRevision30dPct(),
                    value.epsEstimateRevision90dPct());
        }
    }

    public record AnalystHistoryResponse(
            String mode,
            String selectedSource,
            String legacyState,
            String shadowState,
            boolean comparisonPerformed,
            boolean matched,
            List<String> differences,
            Integer legacyPointCount,
            Integer shadowPointCount,
            LocalDate legacyLatestDate,
            LocalDate shadowLatestDate
    ) {
        static AnalystHistoryResponse from(CompanyAnalystHistoryRead value) {
            return new AnalystHistoryResponse(
                    compatibilityMode(value.mode()),
                    compatibilitySource(value.selectedSource()),
                    value.seedState().name(),
                    value.storeState().name(),
                    value.comparisonPerformed(),
                    value.matched(),
                    value.differences(),
                    value.seedPointCount(),
                    value.storePointCount(),
                    value.seedLatestDate(),
                    value.storeLatestDate()
            );
        }

        private static String compatibilityMode(CompanyAnalystHistoryRead.Mode mode) {
            return switch (mode) {
                case SEED_ONLY -> "LEGACY";
                case DUAL_COMPARE -> "DUAL_COMPARE";
                case STORE_PREFERRED -> "SHADOW_PREFERRED";
            };
        }

        private static String compatibilitySource(CompanyAnalystHistoryRead.Source source) {
            return switch (source) {
                case SEED -> "LEGACY";
                case STORE -> "SHADOW";
                case SEED_FALLBACK -> "LEGACY_FALLBACK";
            };
        }
    }

    public record ExpectationsResponse(
            Double estimateUpsidePct,
            Double estimateRevision7d,
            Double estimateRevision30d,
            Double estimateRevision90d,
            Double targetUpsideChange30d,
            Double analystScoreRevision30d
    ) {
        static ExpectationsResponse from(CompanyMarketExpectations value) {
            return new ExpectationsResponse(
                    value.estimateUpsidePct(),
                    value.estimateRevision7d(),
                    value.estimateRevision30d(),
                    value.estimateRevision90d(),
                    value.targetUpsideChange30d(),
                    value.analystScoreRevision30d()
            );
        }
    }

    public record FundamentalsResponse(
            String ticker,
            String cik,
            String asOf,
            Double revenueTtm,
            Double operatingIncomeTtm,
            Double netIncomeTtm,
            Double freeCashFlowTtm,
            Double cash,
            Double debt,
            Double currentAssets,
            Double currentLiabilities,
            Double receivables,
            Double inventory,
            Double capexTtm,
            Double operatingCashFlowTtm,
            Double sharesOutstanding,
            Double marketCap,
            Double enterpriseValue,
            Double revenueGrowthYoY,
            Double operatingMargin,
            Double operatingMarginTrend,
            Double freeCashFlowMargin,
            Double netDebtToRevenue,
            Double evToSales,
            Double evToFcf,
            Double shareDilutionYoY,
            Double stockCompToRevenue,
            Double roe,
            Double currentRatio,
            Double receivablesToRevenue,
            Double inventoryToRevenue,
            Double roic,
            Double effectiveTaxRate,
            boolean roicEstimated,
            Double shareDilution3yCagr,
            Double accrualRatio
    ) {
        static FundamentalsResponse from(CompanyFundamentalsSnapshot value) {
            return new FundamentalsResponse(
                    value.ticker().value(), value.cik(), value.asOf(), value.revenueTtm(),
                    value.operatingIncomeTtm(), value.netIncomeTtm(), value.freeCashFlowTtm(),
                    value.cash(), value.debt(), value.currentAssets(), value.currentLiabilities(),
                    value.receivables(), value.inventory(), value.capexTtm(), value.operatingCashFlowTtm(),
                    value.sharesOutstanding(), value.marketCap(), value.enterpriseValue(),
                    value.revenueGrowthYoY(), value.operatingMargin(), value.operatingMarginTrend(),
                    value.freeCashFlowMargin(), value.netDebtToRevenue(), value.evToSales(), value.evToFcf(),
                    value.shareDilutionYoY(), value.stockCompToRevenue(), value.roe(), value.currentRatio(),
                    value.receivablesToRevenue(), value.inventoryToRevenue(), value.roic(),
                    value.effectiveTaxRate(), value.roicEstimated(), value.shareDilution3yCagr(),
                    value.accrualRatio()
            );
        }
    }

    public record ScoreResponse(
            String ticker,
            int totalScore,
            BreakdownResponse growth,
            BreakdownResponse quality,
            BreakdownResponse valuation,
            BreakdownResponse balanceSheet,
            List<String> reasons
    ) {
        static ScoreResponse from(CompanyScore value) {
            return new ScoreResponse(
                    value.ticker().value(), value.totalScore(), BreakdownResponse.from(value.growth()),
                    BreakdownResponse.from(value.quality()), BreakdownResponse.from(value.valuation()),
                    BreakdownResponse.from(value.balanceSheet()), value.reasons()
            );
        }
    }

    public record BreakdownResponse(int value, List<String> reasons) {
        static BreakdownResponse from(ScoreBreakdown value) {
            return new BreakdownResponse(value.value(), value.reasons());
        }
    }

    public record BuyScoreResponse(
            int appealScore,
            int crowdingScore,
            int buyScore,
            String label,
            List<String> reasons
    ) {
        static BuyScoreResponse from(CompanyBuyScore value) {
            return new BuyScoreResponse(
                    value.appealScore(), value.crowdingScore(), value.buyScore(), label(value.label()), value.reasons()
            );
        }

        private static String label(CompanyBuyLabel label) {
            return switch (label) {
                case FAVORABLE -> "매수 우호";
                case SELECTIVE -> "선별 접근";
                case CHASE_RISK -> "추격 주의";
            };
        }
    }
}
