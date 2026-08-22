package io.macrosquare.company.application.port.in;

import io.macrosquare.company.domain.model.CompanyBuyScore;
import io.macrosquare.company.application.model.CompanyAnalystHistoryRead;
import io.macrosquare.company.application.model.CompanyMarketQuote;
import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import io.macrosquare.company.domain.model.CompanyFundamentalsSnapshot;
import io.macrosquare.company.domain.model.CompanyFundamentalsFreshness;
import io.macrosquare.company.domain.model.CompanyMarketExpectations;
import io.macrosquare.company.domain.model.CompanyScore;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;

public record CompanyResearchParityReport(
        String ticker,
        String cik,
        String registryCik,
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
        CompanyMarketQuote legacyQuote,
        CompanyMarketQuote springQuote,
        CompanyAnalystConsensus legacyAnalystConsensus,
        CompanyAnalystConsensus springAnalystConsensus,
        CompanyAnalystHistoryRead analystHistory,
        CompanyMarketExpectations legacyExpectations,
        CompanyMarketExpectations springExpectations,
        CompanyFundamentalsSnapshot legacyFundamentals,
        CompanyFundamentalsSnapshot springFundamentals,
        CompanyScore legacyScore,
        CompanyScore springScore,
        CompanyBuyScore legacyBuyScore,
        CompanyBuyScore springBuyScore,
        CompanyFundamentalsFreshness fundamentalsFreshness,
        boolean legacyAvailable
) {
    public CompanyResearchParityReport {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        if (cik == null || cik.isBlank()) throw new IllegalArgumentException("cik is required");
        if (registryCik == null || registryCik.isBlank()) throw new IllegalArgumentException("registryCik is required");
        differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
        Objects.requireNonNull(legacyQuote, "legacyQuote");
        Objects.requireNonNull(springQuote, "springQuote");
        Objects.requireNonNull(legacyAnalystConsensus, "legacyAnalystConsensus");
        Objects.requireNonNull(springAnalystConsensus, "springAnalystConsensus");
        Objects.requireNonNull(analystHistory, "analystHistory");
        Objects.requireNonNull(legacyExpectations, "legacyExpectations");
        Objects.requireNonNull(springExpectations, "springExpectations");
        Objects.requireNonNull(legacyFundamentals, "legacyFundamentals");
        Objects.requireNonNull(springFundamentals, "springFundamentals");
        Objects.requireNonNull(legacyScore, "legacyScore");
        Objects.requireNonNull(springScore, "springScore");
        Objects.requireNonNull(legacyBuyScore, "legacyBuyScore");
        Objects.requireNonNull(springBuyScore, "springBuyScore");
        Objects.requireNonNull(fundamentalsFreshness, "fundamentalsFreshness");
    }

    public CompanyResearchParityReport(
            String ticker,
            String cik,
            String registryCik,
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
            CompanyMarketQuote legacyQuote,
            CompanyMarketQuote springQuote,
            CompanyAnalystConsensus legacyAnalystConsensus,
            CompanyAnalystConsensus springAnalystConsensus,
            CompanyAnalystHistoryRead analystHistory,
            CompanyMarketExpectations legacyExpectations,
            CompanyMarketExpectations springExpectations,
            CompanyFundamentalsSnapshot legacyFundamentals,
            CompanyFundamentalsSnapshot springFundamentals,
            CompanyScore legacyScore,
            CompanyScore springScore,
            CompanyBuyScore legacyBuyScore,
            CompanyBuyScore springBuyScore,
            CompanyFundamentalsFreshness fundamentalsFreshness
    ) {
        this(
                ticker, cik, registryCik, allMatched, identityMatched, quoteMatched,
                analystConsensusMatched, analystHistoryMatched, expectationsMatched,
                fundamentalsMatched, scoreMatched, buyScoreMatched, differences,
                legacyQuote, springQuote, legacyAnalystConsensus, springAnalystConsensus,
                analystHistory, legacyExpectations, springExpectations,
                legacyFundamentals, springFundamentals, legacyScore, springScore,
                legacyBuyScore, springBuyScore, fundamentalsFreshness, true
        );
    }

    /**
     * A composite score is publishable only when every advertised score axis
     * has current, independently usable evidence. A zero produced from an
     * empty axis is an implementation sentinel, not an investment opinion.
     */
    public boolean scoreComparable() {
        return fundamentalsFreshness.scoreComparable()
                && springFundamentals.revenueTtm() != null
                && springFundamentals.revenueGrowthYoY() != null
                && springFundamentals.valuationQuality().valuationEligible()
                && hasEvidence(springScore.growth())
                && hasEvidence(springScore.quality())
                && hasEvidence(springScore.valuation())
                && hasEvidence(springScore.balanceSheet());
    }

    public List<String> scoreWarnings() {
        var warnings = new LinkedHashSet<String>(fundamentalsFreshness.warnings());
        if (springFundamentals.revenueTtm() == null) warnings.add("매출 TTM 근거가 없어 점수를 보류함");
        if (springFundamentals.revenueGrowthYoY() == null) warnings.add("TTM 매출 성장률 근거가 없어 점수를 보류함");
        if (!springFundamentals.valuationQuality().valuationEligible()) {
            warnings.add("검증 가능한 시가총액/주식수 근거가 없어 밸류 점수를 보류함");
            warnings.addAll(springFundamentals.valuationQuality().warnings());
        }
        addMissingAxisWarning(warnings, "성장", springScore.growth());
        addMissingAxisWarning(warnings, "수익성", springScore.quality());
        addMissingAxisWarning(warnings, "밸류", springScore.valuation());
        addMissingAxisWarning(warnings, "재무", springScore.balanceSheet());
        return List.copyOf(warnings);
    }

    private static boolean hasEvidence(io.macrosquare.company.domain.model.ScoreBreakdown value) {
        return !value.reasons().isEmpty();
    }

    private static void addMissingAxisWarning(
            LinkedHashSet<String> warnings,
            String axis,
            io.macrosquare.company.domain.model.ScoreBreakdown value
    ) {
        if (!hasEvidence(value)) warnings.add(axis + " 점수 산출 근거가 없어 종합 점수를 보류함");
    }
}
