package io.macrosquare.company.application.service;

import io.macrosquare.company.application.port.in.CompanyResearchParityReport;
import io.macrosquare.company.application.port.in.EvaluateCompanyResearchParityUseCase;
import io.macrosquare.company.application.port.in.ResolveCompanyAnalystHistoryUseCase;
import io.macrosquare.company.application.model.CompanyMarketQuote;
import io.macrosquare.company.application.model.CompanyMarketCapitalization;
import io.macrosquare.company.application.port.out.CompanyFundamentalsUnavailableException;
import io.macrosquare.company.application.port.out.CompanyResearchParityUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystConsensusPort;
import io.macrosquare.company.application.port.out.LoadCompanyFundamentalsEvidencePort;
import io.macrosquare.company.application.port.out.LoadCompanyMarketQuotePort;
import io.macrosquare.company.application.port.out.LoadCompanyMarketCapitalizationPort;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.application.port.out.LoadCompanySubmissionsEvidencePort;
import io.macrosquare.company.application.port.out.ResolveCompanyIdentityPort;
import io.macrosquare.company.domain.model.CompanyBuyScore;
import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import io.macrosquare.company.domain.model.CompanyAnalystEvidence;
import io.macrosquare.company.domain.model.CompanyFundamentalsSnapshot;
import io.macrosquare.company.domain.model.CompanyMarketExpectations;
import io.macrosquare.company.domain.model.CompanyMarketValuationEvidence;
import io.macrosquare.company.domain.model.CompanyScore;
import io.macrosquare.company.domain.model.ScoreBreakdown;
import io.macrosquare.company.domain.model.Ticker;
import io.macrosquare.company.domain.service.CompanyBuyScoringPolicy;
import io.macrosquare.company.domain.service.CompanyFundamentalsContinuityPolicy;
import io.macrosquare.company.domain.service.CompanyFundamentalsFreshnessPolicy;
import io.macrosquare.company.domain.service.CompanyFundamentalsNormalizationPolicy;
import io.macrosquare.company.domain.service.CompanyMarketExpectationsPolicy;
import io.macrosquare.company.domain.service.CompanyScoringPolicy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Read-only parallel run: legacy remains the serving baseline while Spring
 * directly normalizes filing facts and recomputes Score/Buy Score.
 */
public final class EvaluateCompanyResearchParityService implements EvaluateCompanyResearchParityUseCase {

    private final LoadCompanyReadPort companyReadPort;
    private final ResolveCompanyIdentityPort companyIdentityPort;
    private final LoadCompanyFundamentalsEvidencePort fundamentalsEvidencePort;
    private final LoadCompanyMarketQuotePort marketQuotePort;
    private final LoadCompanyMarketCapitalizationPort marketCapitalizationPort;
    private final LoadCompanySubmissionsEvidencePort submissionsEvidencePort;
    private final LoadCompanyAnalystConsensusPort analystConsensusPort;
    private final ResolveCompanyAnalystHistoryUseCase analystHistoryUseCase;
    private final CompanyFundamentalsNormalizationPolicy normalizationPolicy;
    private final CompanyFundamentalsContinuityPolicy continuityPolicy;
    private final CompanyFundamentalsFreshnessPolicy freshnessPolicy;
    private final CompanyMarketExpectationsPolicy expectationsPolicy;
    private final CompanyScoringPolicy scoringPolicy;
    private final CompanyBuyScoringPolicy buyScoringPolicy;
    private final Clock clock;

    public EvaluateCompanyResearchParityService(
            LoadCompanyReadPort companyReadPort,
            ResolveCompanyIdentityPort companyIdentityPort,
            LoadCompanyFundamentalsEvidencePort fundamentalsEvidencePort,
            LoadCompanyMarketQuotePort marketQuotePort,
            LoadCompanyMarketCapitalizationPort marketCapitalizationPort,
            LoadCompanyAnalystConsensusPort analystConsensusPort,
            ResolveCompanyAnalystHistoryUseCase analystHistoryUseCase,
            CompanyFundamentalsNormalizationPolicy normalizationPolicy,
            CompanyMarketExpectationsPolicy expectationsPolicy,
            CompanyScoringPolicy scoringPolicy,
            CompanyBuyScoringPolicy buyScoringPolicy,
            Clock clock
    ) {
        this(
                companyReadPort,
                companyIdentityPort,
                fundamentalsEvidencePort,
                marketQuotePort,
                marketCapitalizationPort,
                null,
                analystConsensusPort,
                analystHistoryUseCase,
                normalizationPolicy,
                new CompanyFundamentalsContinuityPolicy(),
                new CompanyFundamentalsFreshnessPolicy(),
                expectationsPolicy,
                scoringPolicy,
                buyScoringPolicy,
                clock
        );
    }

    public EvaluateCompanyResearchParityService(
            LoadCompanyReadPort companyReadPort,
            ResolveCompanyIdentityPort companyIdentityPort,
            LoadCompanyFundamentalsEvidencePort fundamentalsEvidencePort,
            LoadCompanyMarketQuotePort marketQuotePort,
            LoadCompanyMarketCapitalizationPort marketCapitalizationPort,
            LoadCompanySubmissionsEvidencePort submissionsEvidencePort,
            LoadCompanyAnalystConsensusPort analystConsensusPort,
            ResolveCompanyAnalystHistoryUseCase analystHistoryUseCase,
            CompanyFundamentalsNormalizationPolicy normalizationPolicy,
            CompanyFundamentalsContinuityPolicy continuityPolicy,
            CompanyFundamentalsFreshnessPolicy freshnessPolicy,
            CompanyMarketExpectationsPolicy expectationsPolicy,
            CompanyScoringPolicy scoringPolicy,
            CompanyBuyScoringPolicy buyScoringPolicy,
            Clock clock
    ) {
        this.companyReadPort = Objects.requireNonNull(companyReadPort);
        this.companyIdentityPort = Objects.requireNonNull(companyIdentityPort);
        this.fundamentalsEvidencePort = Objects.requireNonNull(fundamentalsEvidencePort);
        this.marketQuotePort = Objects.requireNonNull(marketQuotePort);
        this.marketCapitalizationPort = Objects.requireNonNull(marketCapitalizationPort);
        this.submissionsEvidencePort = submissionsEvidencePort;
        this.analystConsensusPort = Objects.requireNonNull(analystConsensusPort);
        this.analystHistoryUseCase = Objects.requireNonNull(analystHistoryUseCase);
        this.normalizationPolicy = Objects.requireNonNull(normalizationPolicy);
        this.continuityPolicy = Objects.requireNonNull(continuityPolicy);
        this.freshnessPolicy = Objects.requireNonNull(freshnessPolicy);
        this.expectationsPolicy = Objects.requireNonNull(expectationsPolicy);
        this.scoringPolicy = Objects.requireNonNull(scoringPolicy);
        this.buyScoringPolicy = Objects.requireNonNull(buyScoringPolicy);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Compatibility constructor for isolated tests created before independent market-cap evidence. */
    public EvaluateCompanyResearchParityService(
            LoadCompanyReadPort companyReadPort,
            ResolveCompanyIdentityPort companyIdentityPort,
            LoadCompanyFundamentalsEvidencePort fundamentalsEvidencePort,
            LoadCompanyMarketQuotePort marketQuotePort,
            LoadCompanyAnalystConsensusPort analystConsensusPort,
            ResolveCompanyAnalystHistoryUseCase analystHistoryUseCase,
            CompanyFundamentalsNormalizationPolicy normalizationPolicy,
            CompanyMarketExpectationsPolicy expectationsPolicy,
            CompanyScoringPolicy scoringPolicy,
            CompanyBuyScoringPolicy buyScoringPolicy,
            Clock clock
    ) {
        this.companyReadPort = Objects.requireNonNull(companyReadPort);
        this.companyIdentityPort = Objects.requireNonNull(companyIdentityPort);
        this.fundamentalsEvidencePort = Objects.requireNonNull(fundamentalsEvidencePort);
        this.marketQuotePort = Objects.requireNonNull(marketQuotePort);
        this.marketCapitalizationPort = null;
        this.submissionsEvidencePort = null;
        this.analystConsensusPort = Objects.requireNonNull(analystConsensusPort);
        this.analystHistoryUseCase = Objects.requireNonNull(analystHistoryUseCase);
        this.normalizationPolicy = Objects.requireNonNull(normalizationPolicy);
        this.continuityPolicy = new CompanyFundamentalsContinuityPolicy();
        this.freshnessPolicy = new CompanyFundamentalsFreshnessPolicy();
        this.expectationsPolicy = Objects.requireNonNull(expectationsPolicy);
        this.scoringPolicy = Objects.requireNonNull(scoringPolicy);
        this.buyScoringPolicy = Objects.requireNonNull(buyScoringPolicy);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public CompanyResearchParityReport evaluate(String ticker) {
        var normalizedTicker = normalizeTicker(ticker);
        var springIdentity = companyIdentityPort.resolve(normalizedTicker);
        var springQuote = marketQuotePort.load(springIdentity.ticker());
        if (!springQuote.available()) {
            throw new CompanyResearchParityUnavailableException(
                    "Direct company quote was unavailable",
                    new IllegalStateException("quote port returned an unavailable quote")
            );
        }
        var quoteSymbol = springQuote.symbol().trim().toUpperCase(Locale.ROOT).replace('.', '-');
        if (!springIdentity.ticker().equals(quoteSymbol)) {
            throw new CompanyResearchParityUnavailableException(
                    "Direct market quote returned a different security",
                    new IllegalStateException("requested=" + springIdentity.ticker() + ", returned=" + quoteSymbol)
            );
        }
        var todayUtc = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (springQuote.date().isAfter(todayUtc.plusDays(1))
                || springQuote.date().isBefore(todayUtc.minusDays(7))) {
            throw new CompanyResearchParityUnavailableException(
                    "Direct market quote date was outside the accepted freshness window",
                    new IllegalStateException("quoteDate=" + springQuote.date() + ", today=" + todayUtc)
            );
        }
        var springAnalystConsensus = analystConsensusPort.load(springIdentity.ticker());
        var analystHistory = analystHistoryUseCase.resolve(springIdentity.ticker());
        var springExpectations = expectationsPolicy.evaluate(
                new CompanyAnalystEvidence(
                        springAnalystConsensus.analystScore(),
                        springAnalystConsensus.upsidePct(),
                        springAnalystConsensus.epsEstimateRevision7dPct(),
                        springAnalystConsensus.epsEstimateRevision30dPct(),
                        springAnalystConsensus.epsEstimateRevision90dPct(),
                        analystHistory.history()
                ),
                clock.instant()
        );
        var resolvedEvidence = resolveFundamentalsEvidence(springIdentity.fundamentalsCiks());
        var springFundamentals = normalizeFundamentals(springIdentity.ticker(), resolvedEvidence, springQuote);
        var fundamentalsFreshness = resolveFreshness(
                springIdentity.submissionCiks(), springFundamentals
        );
        var springScore = scoringPolicy.evaluate(springFundamentals.scoringFinancials());
        var springBuyScore = buyScoringPolicy.evaluate(
                springFundamentals.scoringFinancials(),
                springScore,
                springExpectations
        );

        // The captured document is a migration diagnostic, never a dependency
        // of the current SEC/Yahoo calculation. A malformed legacy projection
        // must not quarantine otherwise valid current fundamentals and scores.
        CompanyResearchCoreProjection legacy = null;
        String legacyUnavailableReason = null;
        try {
            legacy = CompanyResearchCoreProjection.from(companyReadPort.detail(normalizedTicker));
        } catch (RuntimeException error) {
            legacyUnavailableReason = "legacyProjection.unavailable:" + error.getClass().getSimpleName();
        }
        var legacyAvailable = legacy != null;
        var legacyQuote = legacyAvailable ? legacy.quote() : springQuote;
        var legacyAnalystConsensus = legacyAvailable
                ? legacy.analystConsensus() : springAnalystConsensus;
        var legacyExpectations = legacyAvailable ? legacy.expectations() : springExpectations;
        var legacyFundamentals = legacyAvailable ? legacy.fundamentals() : springFundamentals;
        var legacyScore = legacyAvailable ? legacy.score() : springScore;
        var legacyBuyScore = legacyAvailable ? legacy.buyScore() : springBuyScore;

        var identityDifferences = legacyAvailable
                ? compareIdentity(legacy, springIdentity.ticker(), resolvedEvidence.cik())
                : new ArrayList<String>();
        var quoteDifferences = legacyAvailable
                ? compareQuote(legacyQuote, springQuote) : new ArrayList<String>();
        var analystConsensusDifferences = legacyAvailable
                ? compareAnalystConsensus(legacyAnalystConsensus, springAnalystConsensus)
                : new ArrayList<String>();
        var analystHistoryDifferences = legacyAvailable
                ? analystHistory.differences() : new ArrayList<String>();
        var expectationDifferences = legacyAvailable
                ? compareExpectations(legacyExpectations, springExpectations) : new ArrayList<String>();
        var fundamentalDifferences = legacyAvailable
                ? compareFundamentals(legacyFundamentals, springFundamentals) : new ArrayList<String>();
        var scoreDifferences = legacyAvailable
                ? compareScore(legacyScore, springScore) : new ArrayList<String>();
        var buyScoreDifferences = legacyAvailable
                ? compareBuyScore(legacyBuyScore, springBuyScore) : new ArrayList<String>();
        var differences = new ArrayList<String>();
        if (!legacyAvailable) differences.add(legacyUnavailableReason == null
                ? "legacyProjection.unavailable" : legacyUnavailableReason);
        differences.addAll(identityDifferences);
        differences.addAll(quoteDifferences);
        differences.addAll(analystConsensusDifferences);
        differences.addAll(analystHistoryDifferences);
        differences.addAll(expectationDifferences);
        differences.addAll(fundamentalDifferences);
        differences.addAll(scoreDifferences);
        differences.addAll(buyScoreDifferences);

        return new CompanyResearchParityReport(
                springIdentity.ticker(),
                resolvedEvidence.cik(),
                springIdentity.registryCik(),
                legacyAvailable && differences.isEmpty(),
                legacyAvailable && identityDifferences.isEmpty(),
                legacyAvailable && quoteDifferences.isEmpty(),
                legacyAvailable && analystConsensusDifferences.isEmpty(),
                legacyAvailable && analystHistoryDifferences.isEmpty(),
                legacyAvailable && expectationDifferences.isEmpty(),
                legacyAvailable && fundamentalDifferences.isEmpty(),
                legacyAvailable && scoreDifferences.isEmpty(),
                legacyAvailable && buyScoreDifferences.isEmpty(),
                differences,
                legacyQuote,
                springQuote,
                legacyAnalystConsensus,
                springAnalystConsensus,
                analystHistory,
                legacyExpectations,
                springExpectations,
                legacyFundamentals,
                springFundamentals,
                legacyScore,
                springScore,
                legacyBuyScore,
                springBuyScore,
                fundamentalsFreshness,
                legacyAvailable
        );
    }

    private CompanyFundamentalsSnapshot normalizeFundamentals(
            String ticker,
            ResolvedFundamentalsEvidence evidence,
            CompanyMarketQuote quote
    ) {
        if (marketCapitalizationPort == null) {
            return normalizationPolicy.normalize(
                    new Ticker(ticker), evidence.cik(), evidence.evidence(), quote.price(), LocalDate.now(clock));
        }
        CompanyMarketCapitalization marketCap = null;
        try {
            marketCap = marketCapitalizationPort.load(ticker);
        } catch (RuntimeException ignored) {
            // Valuation normalization is fail-closed. Core filing quality can
            // still be evaluated while market-cap-dependent multiples remain unavailable.
        }
        if (marketCap != null && !ticker.equals(marketCap.symbol())) {
            throw new CompanyResearchParityUnavailableException(
                    "Direct market capitalization returned a different security",
                    new IllegalStateException("requested=" + ticker + ", returned=" + marketCap.symbol())
            );
        }
        var valuationEvidence = new CompanyMarketValuationEvidence(
                quote.price(),
                quote.date(),
                marketCap == null ? null : marketCap.value(),
                marketCap == null ? null : marketCap.date(),
                marketCap == null ? null : marketCap.referencePrice()
        );
        return normalizationPolicy.normalizeWithMarketEvidence(
                new Ticker(ticker), evidence.cik(), evidence.evidence(), valuationEvidence, LocalDate.now(clock));
    }

    private static List<String> compareAnalystConsensus(
            CompanyAnalystConsensus expected,
            CompanyAnalystConsensus actual
    ) {
        var differences = new ArrayList<String>();
        compare(differences, "analystConsensus.analystScore", expected.analystScore(), actual.analystScore());
        compare(differences, "analystConsensus.upsidePct", expected.upsidePct(), actual.upsidePct());
        compare(differences, "analystConsensus.epsEstimateRevision7dPct",
                expected.epsEstimateRevision7dPct(), actual.epsEstimateRevision7dPct());
        compare(differences, "analystConsensus.epsEstimateRevision30dPct",
                expected.epsEstimateRevision30dPct(), actual.epsEstimateRevision30dPct());
        compare(differences, "analystConsensus.epsEstimateRevision90dPct",
                expected.epsEstimateRevision90dPct(), actual.epsEstimateRevision90dPct());
        return differences;
    }

    private static List<String> compareExpectations(
            CompanyMarketExpectations expected,
            CompanyMarketExpectations actual
    ) {
        var differences = new ArrayList<String>();
        compare(differences, "expectations.estimateUpsidePct", expected.estimateUpsidePct(), actual.estimateUpsidePct());
        compare(differences, "expectations.estimateRevision7d", expected.estimateRevision7d(), actual.estimateRevision7d());
        compare(differences, "expectations.estimateRevision30d", expected.estimateRevision30d(), actual.estimateRevision30d());
        compare(differences, "expectations.estimateRevision90d", expected.estimateRevision90d(), actual.estimateRevision90d());
        compare(differences, "expectations.targetUpsideChange30d",
                expected.targetUpsideChange30d(), actual.targetUpsideChange30d());
        compare(
                differences,
                "expectations.analystScoreRevision30d",
                expected.analystScoreRevision30d(),
                actual.analystScoreRevision30d()
        );
        return differences;
    }

    private static List<String> compareQuote(
            CompanyMarketQuote expected,
            CompanyMarketQuote actual
    ) {
        var differences = new ArrayList<String>();
        compare(differences, "quote.symbol", expected.symbol(), actual.symbol());
        compare(differences, "quote.price", expected.price(), actual.price());
        compare(differences, "quote.date", expected.date(), actual.date());
        return differences;
    }

    private ResolvedFundamentalsEvidence resolveFundamentalsEvidence(List<String> cikCandidates) {
        var loaded = new ArrayList<LoadedFundamentalsEvidence>();
        RuntimeException lastFailure = null;
        for (var cik : cikCandidates) {
            try {
                var evidence = fundamentalsEvidencePort.load(cik);
                loaded.add(new LoadedFundamentalsEvidence(cik, evidence));
            } catch (CompanyFundamentalsUnavailableException error) {
                lastFailure = error;
            }
        }
        if (!loaded.isEmpty()) {
            var merged = continuityPolicy.merge(
                    loaded.stream().map(LoadedFundamentalsEvidence::evidence).toList()
            );
            // The legal successor owns the current observation when it has a
            // newer revenue period; the predecessor only supplies missing
            // history needed for TTM and YoY reconstruction.
            var selectedCik = loaded.stream()
                    .max(Comparator.comparing(value -> latestRevenueDate(value.evidence())))
                    .map(LoadedFundamentalsEvidence::cik)
                    .orElse(loaded.getFirst().cik());
            return new ResolvedFundamentalsEvidence(selectedCik, merged);
        }
        throw new CompanyResearchParityUnavailableException(
                "No SEC fundamentals candidate could be resolved",
                lastFailure == null ? new IllegalStateException("empty CIK candidate list") : lastFailure
        );
    }

    private io.macrosquare.company.domain.model.CompanyFundamentalsFreshness resolveFreshness(
            List<String> submissionCiks,
            CompanyFundamentalsSnapshot fundamentals
    ) {
        if (submissionsEvidencePort == null) {
            var date = parseDate(fundamentals.asOf());
            return freshnessPolicy.evaluate(fundamentals, date, date, "TEST");
        }
        var latest = submissionCiks.stream()
                .flatMap(cik -> {
                    try {
                        return submissionsEvidencePort.load(cik).filings().stream();
                    } catch (RuntimeException ignored) {
                        return java.util.stream.Stream.empty();
                    }
                })
                .filter(filing -> filing.reportDate() != null)
                .filter(filing -> List.of("10-Q", "10-K", "20-F", "40-F").contains(filing.form()))
                .max(Comparator.comparing(io.macrosquare.company.domain.model.CompanyFilingEvidence::reportDate)
                        .thenComparing(io.macrosquare.company.domain.model.CompanyFilingEvidence::filingDate))
                .orElse(null);
        return freshnessPolicy.evaluate(
                fundamentals,
                latest == null ? null : latest.reportDate(),
                latest == null ? null : latest.filingDate(),
                latest == null ? null : latest.form()
        );
    }

    private static String latestRevenueDate(
            io.macrosquare.company.domain.model.CompanyFundamentalsEvidence evidence
    ) {
        return evidence.revenue().stream()
                .map(io.macrosquare.company.domain.model.FinancialFactPoint::endDate)
                .filter(Objects::nonNull)
                .max(String::compareTo)
                .orElse("");
    }

    private static LocalDate parseDate(String value) {
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<String> compareIdentity(
            CompanyResearchCoreProjection legacy,
            String springTicker,
            String springCik
    ) {
        var differences = new ArrayList<String>();
        compare(differences, "identity.ticker", legacy.ticker(), springTicker);
        compare(differences, "identity.cik", legacy.cik(), springCik);
        return differences;
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        return ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> compareFundamentals(
            CompanyFundamentalsSnapshot expected,
            CompanyFundamentalsSnapshot actual
    ) {
        var differences = new ArrayList<String>();
        compare(differences, "fundamentals.ticker", expected.ticker(), actual.ticker());
        compare(differences, "fundamentals.cik", expected.cik(), actual.cik());
        compare(differences, "fundamentals.asOf", expected.asOf(), actual.asOf());
        compare(differences, "fundamentals.revenueTtm", expected.revenueTtm(), actual.revenueTtm());
        compare(differences, "fundamentals.operatingIncomeTtm", expected.operatingIncomeTtm(), actual.operatingIncomeTtm());
        compare(differences, "fundamentals.netIncomeTtm", expected.netIncomeTtm(), actual.netIncomeTtm());
        compare(differences, "fundamentals.freeCashFlowTtm", expected.freeCashFlowTtm(), actual.freeCashFlowTtm());
        compare(differences, "fundamentals.cash", expected.cash(), actual.cash());
        compare(differences, "fundamentals.debt", expected.debt(), actual.debt());
        compare(differences, "fundamentals.currentAssets", expected.currentAssets(), actual.currentAssets());
        compare(differences, "fundamentals.currentLiabilities", expected.currentLiabilities(), actual.currentLiabilities());
        compare(differences, "fundamentals.receivables", expected.receivables(), actual.receivables());
        compare(differences, "fundamentals.inventory", expected.inventory(), actual.inventory());
        compare(differences, "fundamentals.capexTtm", expected.capexTtm(), actual.capexTtm());
        compare(differences, "fundamentals.operatingCashFlowTtm", expected.operatingCashFlowTtm(), actual.operatingCashFlowTtm());
        compare(differences, "fundamentals.sharesOutstanding", expected.sharesOutstanding(), actual.sharesOutstanding());
        compare(differences, "fundamentals.marketCap", expected.marketCap(), actual.marketCap());
        compare(differences, "fundamentals.enterpriseValue", expected.enterpriseValue(), actual.enterpriseValue());
        compare(differences, "fundamentals.revenueGrowthYoY", expected.revenueGrowthYoY(), actual.revenueGrowthYoY());
        compare(differences, "fundamentals.operatingMargin", expected.operatingMargin(), actual.operatingMargin());
        compare(differences, "fundamentals.operatingMarginTrend", expected.operatingMarginTrend(), actual.operatingMarginTrend());
        compare(differences, "fundamentals.freeCashFlowMargin", expected.freeCashFlowMargin(), actual.freeCashFlowMargin());
        compare(differences, "fundamentals.netDebtToRevenue", expected.netDebtToRevenue(), actual.netDebtToRevenue());
        compare(differences, "fundamentals.evToSales", expected.evToSales(), actual.evToSales());
        compare(differences, "fundamentals.evToFcf", expected.evToFcf(), actual.evToFcf());
        compare(differences, "fundamentals.shareDilutionYoY", expected.shareDilutionYoY(), actual.shareDilutionYoY());
        compare(differences, "fundamentals.stockCompToRevenue", expected.stockCompToRevenue(), actual.stockCompToRevenue());
        compare(differences, "fundamentals.roe", expected.roe(), actual.roe());
        compare(differences, "fundamentals.currentRatio", expected.currentRatio(), actual.currentRatio());
        compare(differences, "fundamentals.receivablesToRevenue", expected.receivablesToRevenue(), actual.receivablesToRevenue());
        compare(differences, "fundamentals.inventoryToRevenue", expected.inventoryToRevenue(), actual.inventoryToRevenue());
        // Valuation provenance is a Spring-only quality extension. It has no
        // captured legacy equivalent and therefore is not a parity dimension.
        return differences;
    }

    private static List<String> compareScore(CompanyScore expected, CompanyScore actual) {
        var differences = new ArrayList<String>();
        compare(differences, "score.ticker", expected.ticker(), actual.ticker());
        compare(differences, "score.totalScore", expected.totalScore(), actual.totalScore());
        compareBreakdown(differences, "score.growth", expected.growth(), actual.growth());
        compareBreakdown(differences, "score.quality", expected.quality(), actual.quality());
        compareBreakdown(differences, "score.valuation", expected.valuation(), actual.valuation());
        compareBreakdown(differences, "score.balanceSheet", expected.balanceSheet(), actual.balanceSheet());
        compare(differences, "score.reasons", expected.reasons(), actual.reasons());
        return differences;
    }

    private static void compareBreakdown(
            List<String> differences,
            String path,
            ScoreBreakdown expected,
            ScoreBreakdown actual
    ) {
        compare(differences, path + ".value", expected.value(), actual.value());
        compare(differences, path + ".reasons", expected.reasons(), actual.reasons());
    }

    private static List<String> compareBuyScore(CompanyBuyScore expected, CompanyBuyScore actual) {
        var differences = new ArrayList<String>();
        compare(differences, "buyScore.appealScore", expected.appealScore(), actual.appealScore());
        compare(differences, "buyScore.crowdingScore", expected.crowdingScore(), actual.crowdingScore());
        compare(differences, "buyScore.buyScore", expected.buyScore(), actual.buyScore());
        compare(differences, "buyScore.label", expected.label(), actual.label());
        compare(differences, "buyScore.reasons", expected.reasons(), actual.reasons());
        return differences;
    }

    private static void compare(List<String> differences, String path, Double expected, Double actual) {
        if (expected == null && actual == null) return;
        if (expected == null || actual == null) {
            differences.add(path);
            return;
        }
        var tolerance = Math.max(1e-9, Math.max(Math.abs(expected), Math.abs(actual)) * 1e-12);
        if (Math.abs(expected - actual) > tolerance) differences.add(path);
    }

    private static void compare(List<String> differences, String path, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) differences.add(path);
    }

    private record ResolvedFundamentalsEvidence(
            String cik,
            io.macrosquare.company.domain.model.CompanyFundamentalsEvidence evidence
    ) {
    }

    private record LoadedFundamentalsEvidence(
            String cik,
            io.macrosquare.company.domain.model.CompanyFundamentalsEvidence evidence
    ) {
    }
}
