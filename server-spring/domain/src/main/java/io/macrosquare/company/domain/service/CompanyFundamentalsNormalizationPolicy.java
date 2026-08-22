package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyFundamentalsEvidence;
import io.macrosquare.company.domain.model.CompanyFundamentalsSnapshot;
import io.macrosquare.company.domain.model.CompanyMarketValuationEvidence;
import io.macrosquare.company.domain.model.CompanyValuationQuality;
import io.macrosquare.company.domain.model.FinancialFactPoint;
import io.macrosquare.company.domain.model.Ticker;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Normalizes semantic filing facts into the company fundamentals used by the
 * scoring policies. The rules intentionally preserve the established scoring
 * contract; policy changes require separate historical validation.
 */
public final class CompanyFundamentalsNormalizationPolicy {

    private static final double[] COMMON_SHARE_SPLIT_FACTORS = {2, 3, 4, 5, 10, 20};
    private static final double SHARE_SPLIT_FACTOR_TOLERANCE = 0.12;
    private static final double MAX_SAME_DAY_REFERENCE_PRICE_DIVERGENCE_PCT = 5.0;
    private static final double MAX_UNEXPLAINED_SHARE_DIVERGENCE_PCT = 35.0;
    private static final long MAX_MARKET_CAP_QUOTE_GAP_DAYS = 10;
    private static final long MAX_SEC_SHARE_FALLBACK_AGE_DAYS = 550;
    private static final long MAX_FINANCIAL_SERIES_AGE_DAYS = 550;

    public CompanyFundamentalsSnapshot normalize(
            Ticker ticker,
            String cik,
            CompanyFundamentalsEvidence evidence,
            Double currentPrice,
            LocalDate fallbackDate
    ) {
        return normalize(
                ticker,
                cik,
                evidence,
                CompanyMarketValuationEvidence.quoteOnly(
                        currentPrice,
                        currentPrice == null ? null : fallbackDate
                ),
                fallbackDate,
                true
        );
    }

    /**
     * Production normalization. Independent market capitalization is required
     * for valuation multiples; a failed external observation never falls back
     * to silently multiplying a potentially pre-split SEC share count.
     */
    public CompanyFundamentalsSnapshot normalizeWithMarketEvidence(
            Ticker ticker,
            String cik,
            CompanyFundamentalsEvidence evidence,
            CompanyMarketValuationEvidence marketEvidence,
            LocalDate fallbackDate
    ) {
        return normalize(ticker, cik, evidence, marketEvidence, fallbackDate, false);
    }

    private CompanyFundamentalsSnapshot normalize(
            Ticker ticker,
            String cik,
            CompanyFundamentalsEvidence evidence,
            CompanyMarketValuationEvidence marketEvidence,
            LocalDate fallbackDate,
            boolean allowSecShareFallback
    ) {
        Objects.requireNonNull(ticker, "ticker must not be null");
        if (cik == null || cik.isBlank()) throw new IllegalArgumentException("cik must not be blank");
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(marketEvidence, "marketEvidence must not be null");
        Objects.requireNonNull(fallbackDate, "fallbackDate must not be null");
        evidence = suppressStaleSeries(evidence, fallbackDate);

        var revenueSeries = evidence.revenue();
        var revenueTtm = sumTtm(revenueSeries);
        if (revenueTtm == null) {
            var derivedRevenue = deriveRevenueFromOperatingIdentity(
                    evidence.operatingIncome(), evidence.costsAndExpenses()
            );
            var derivedRevenueTtm = sumTtm(derivedRevenue);
            if (derivedRevenueTtm != null) {
                revenueSeries = derivedRevenue;
                revenueTtm = derivedRevenueTtm;
            }
        }
        var operatingIncomeTtm = sumTtm(evidence.operatingIncome());
        var netIncomeTtm = sumTtm(evidence.netIncome());
        var operatingCashFlowTtm = sumTtm(evidence.operatingCashFlow());
        var capexTtm = sumTtm(evidence.capitalExpenditure());
        var freeCashFlowTtm = operatingCashFlowTtm != null && capexTtm != null
                ? operatingCashFlowTtm - Math.abs(capexTtm)
                : null;
        var cash = latestInstant(evidence.cash());
        var debt = latestInstant(evidence.debt());
        var currentAssets = latestInstant(evidence.currentAssets());
        var currentLiabilities = latestInstant(evidence.currentLiabilities());
        var rawReceivables = latestInstant(evidence.receivables());
        var rawInventory = latestInstant(evidence.inventory());
        var valuation = resolveValuation(evidence, marketEvidence, allowSecShareFallback);
        var sharesOutstanding = valuation.resolvedShares();
        var marketCap = valuation.marketCap();
        var enterpriseValue = marketCap == null
                ? null
                : marketCap + nullToZero(debt) - nullToZero(cash);
        var stockCompTtm = sumTtm(evidence.stockCompensation());
        var equity = latestInstant(evidence.stockholdersEquity());
        var averageEquity = averageAnnualValue(evidence.stockholdersEquity());
        var dilutionShares = evidence.weightedAverageDilutedShares().isEmpty()
                ? evidence.sharesOutstanding()
                : evidence.weightedAverageDilutedShares();
        var receivables = saneWorkingCapitalValue(rawReceivables, currentAssets, revenueTtm, 0.8);
        var inventory = saneWorkingCapitalValue(rawInventory, currentAssets, revenueTtm, 0.6);
        var directTaxRate = effectiveTaxRate(evidence);
        var taxRate = directTaxRate == null ? 0.21 : directTaxRate;
        var investedCapital = averageInvestedCapital(evidence);
        // Quality ratios use audited annual duration facts. SEC 10-Q facts can
        // be quarter-only or year-to-date; summing them without duration
        // metadata would double count Q2/Q3 values.
        var annualNetIncome = currentAnnualValue(evidence.netIncome());
        var annualOperatingCashFlow = currentAnnualValue(evidence.operatingCashFlow());
        var nopatOperatingIncome = operatingIncomeTtm != null
                ? operatingIncomeTtm
                : currentAnnualValue(evidence.operatingIncome());
        var roic = nopatOperatingIncome == null || investedCapital.value() == null
                ? null
                : ratio(
                        nopatOperatingIncome * (1 - taxRate),
                        investedCapital.value(),
                        -100,
                        200
                );
        var averageAssets = averageAnnualValue(evidence.totalAssets());
        var accrualRatio = annualNetIncome == null || annualOperatingCashFlow == null
                ? null
                : ratio(annualNetIncome - annualOperatingCashFlow, averageAssets.value(), -100, 100);

        return new CompanyFundamentalsSnapshot(
                ticker,
                cik,
                dateOfCoreFacts(revenueSeries, evidence, fallbackDate),
                revenueTtm,
                operatingIncomeTtm,
                netIncomeTtm,
                freeCashFlowTtm,
                cash,
                debt,
                currentAssets,
                currentLiabilities,
                receivables,
                inventory,
                capexTtm,
                operatingCashFlowTtm,
                sharesOutstanding,
                marketCap,
                enterpriseValue,
                trailingYoY(revenueSeries),
                ratio(operatingIncomeTtm, revenueTtm, -100, 100),
                trendFromAnnualRatio(evidence.operatingIncome(), revenueSeries),
                ratio(freeCashFlowTtm, revenueTtm, -100, 100),
                multiple(nullToZero(debt) - nullToZero(cash), revenueTtm, -10.0, 10.0),
                valuation.quality().valuationEligible()
                        ? multiple(enterpriseValue, revenueTtm, 0.0, 100.0)
                        : null,
                valuation.quality().valuationEligible()
                        ? multiple(enterpriseValue, freeCashFlowTtm, 0.0, 300.0)
                        : null,
                dilutionYoY(dilutionShares),
                ratio(stockCompTtm, revenueTtm, 0, 50),
                ratio(netIncomeTtm, averageEquity.value() == null ? equity : averageEquity.value(), -100, 100),
                multiple(currentAssets, currentLiabilities, 0.0, 20.0),
                multiple(receivables, revenueTtm, 0.0, 0.8),
                multiple(inventory, revenueTtm, 0.0, 0.6),
                roic,
                directTaxRate == null ? null : directTaxRate * 100,
                roic != null && (directTaxRate == null || investedCapital.estimated()),
                dilutionCagr(dilutionShares, 3),
                accrualRatio,
                valuation.quality()
        );
    }

    private static ValuationResolution resolveValuation(
            CompanyFundamentalsEvidence evidence,
            CompanyMarketValuationEvidence marketEvidence,
            boolean allowSecShareFallback
    ) {
        var rawSharesPoint = latestInstantPoint(evidence.sharesOutstanding());
        var rawShares = rawSharesPoint == null ? null : rawSharesPoint.value();
        var rawSharesDate = rawSharesPoint == null ? null : parseDate(rawSharesPoint.endDate());
        var price = marketEvidence.currentPrice();
        var quoteDate = marketEvidence.quoteDate();
        var independentMarketCap = freshIndependentMarketCap(marketEvidence);
        if (independentMarketCap != null) {
            var referencePrice = marketEvidence.marketCapReferencePrice();
            var resolvedShares = independentMarketCap / referencePrice;
            var rolledMarketCap = price == null
                    ? independentMarketCap
                    : resolvedShares * price;
            var divergence = divergencePct(rawShares, resolvedShares);
            var splitFactor = detectedSplitFactor(rawShares, resolvedShares);
            var warnings = new ArrayList<String>();
            if (quoteDate != null && !quoteDate.equals(marketEvidence.marketCapDate())) {
                warnings.add("independent market capitalization rolled forward from "
                        + marketEvidence.marketCapDate() + " using its reference close");
            }
            if (splitFactor != null) {
                warnings.add("SEC shares and market data differ by a split-like factor of " + splitFactor + "x");
            } else if (divergence != null && Math.abs(divergence) > 20) {
                warnings.add("SEC shares and implied current shares diverge by "
                        + String.format(java.util.Locale.ROOT, "%.1f%%", divergence));
            }
            var sameDayPriceMismatch = sameDayReferencePriceMismatch(marketEvidence);
            if (sameDayPriceMismatch) {
                warnings.add("quote and market-cap reference close use inconsistent price bases");
            }
            var unexplainedShareMismatch = divergence != null
                    && Math.abs(divergence) > MAX_UNEXPLAINED_SHARE_DIVERGENCE_PCT
                    && splitFactor == null;
            if (unexplainedShareMismatch) {
                warnings.add("unexplained SEC/implied-share divergence exceeds the publication limit");
            }
            // Independent market cap remains observable for diagnostics, but
            // score-producing valuation multiples are quarantined whenever the
            // quote/reference basis or implied share count cannot be reconciled.
            var valuationEligible = !sameDayPriceMismatch && !unexplainedShareMismatch;
            var quality = new CompanyValuationQuality(
                    CompanyValuationQuality.MarketCapitalizationBasis.INDEPENDENT_MARKET_CAP,
                    quoteDate == null ? marketEvidence.marketCapDate() : quoteDate,
                    rawSharesDate,
                    rawShares,
                    resolvedShares,
                    divergence,
                    splitFactor,
                    valuationEligible,
                    warnings
            );
            return new ValuationResolution(rolledMarketCap, resolvedShares, quality);
        }

        if (allowSecShareFallback
                && price != null
                && quoteDate != null
                && rawShares != null
                && rawShares > 0
                && rawSharesDate != null) {
            var age = ChronoUnit.DAYS.between(rawSharesDate, quoteDate);
            if (age >= -2 && age <= MAX_SEC_SHARE_FALLBACK_AGE_DAYS) {
                var marketCap = price * rawShares;
                if (Double.isFinite(marketCap) && marketCap > 0) {
                    var quality = new CompanyValuationQuality(
                            CompanyValuationQuality.MarketCapitalizationBasis.SEC_SHARES,
                            quoteDate,
                            rawSharesDate,
                            rawShares,
                            rawShares,
                            0.0,
                            null,
                            true,
                            List.of("independent market capitalization unavailable; SEC-share fallback used")
                    );
                    return new ValuationResolution(marketCap, rawShares, quality);
                }
            }
        }

        var warning = marketEvidence.independentMarketCap() == null
                ? "independent market capitalization unavailable; valuation multiples suppressed"
                : marketEvidence.marketCapReferencePrice() == null
                ? "market-cap reference close unavailable; valuation multiples suppressed"
                : "independent market capitalization is stale relative to the quote; valuation multiples suppressed";
        var quality = new CompanyValuationQuality(
                CompanyValuationQuality.MarketCapitalizationBasis.UNAVAILABLE,
                marketEvidence.marketCapDate(),
                rawSharesDate,
                rawShares,
                null,
                null,
                null,
                false,
                List.of(warning)
        );
        return new ValuationResolution(null, null, quality);
    }

    private static Double freshIndependentMarketCap(CompanyMarketValuationEvidence evidence) {
        if (evidence.independentMarketCap() == null || evidence.marketCapDate() == null) return null;
        // The reference close is the basis bridge between an independently
        // observed market cap and today's quote. Falling back to today's price
        // silently recreates the pre/post-split unit-mixing incident.
        if (evidence.marketCapReferencePrice() == null) return null;
        if (evidence.quoteDate() == null) return evidence.independentMarketCap();
        var gap = ChronoUnit.DAYS.between(evidence.marketCapDate(), evidence.quoteDate());
        if (gap < -2 || gap > MAX_MARKET_CAP_QUOTE_GAP_DAYS) return null;
        // A dated market cap cannot be combined with a different day's price:
        // doing so keeps cap stale and makes the implied share count move with
        // the stock. A reference close is therefore mandatory off-date.
        if (gap != 0 && evidence.marketCapReferencePrice() == null) return null;
        return evidence.independentMarketCap();
    }

    private static boolean sameDayReferencePriceMismatch(CompanyMarketValuationEvidence evidence) {
        if (evidence.currentPrice() == null || evidence.quoteDate() == null
                || evidence.marketCapDate() == null || evidence.marketCapReferencePrice() == null
                || !evidence.quoteDate().equals(evidence.marketCapDate())) {
            return false;
        }
        var divergence = Math.abs(evidence.currentPrice() / evidence.marketCapReferencePrice() - 1) * 100;
        return !Double.isFinite(divergence) || divergence > MAX_SAME_DAY_REFERENCE_PRICE_DIVERGENCE_PCT;
    }

    private static Double divergencePct(Double rawShares, Double resolvedShares) {
        if (rawShares == null || resolvedShares == null || rawShares <= 0 || resolvedShares <= 0) return null;
        var value = ((rawShares / resolvedShares) - 1) * 100;
        return Double.isFinite(value) ? value : null;
    }

    private static Double detectedSplitFactor(Double rawShares, Double resolvedShares) {
        if (rawShares == null || resolvedShares == null || rawShares <= 0 || resolvedShares <= 0) return null;
        var factor = Math.max(rawShares / resolvedShares, resolvedShares / rawShares);
        for (var common : COMMON_SHARE_SPLIT_FACTORS) {
            if (Math.abs(factor / common - 1) <= SHARE_SPLIT_FACTOR_TOLERANCE) return common;
        }
        return null;
    }

    private static Double latestInstant(List<FinancialFactPoint> points) {
        var point = latestInstantPoint(points);
        return point == null ? null : point.value();
    }

    private static FinancialFactPoint latestInstantPoint(List<FinancialFactPoint> points) {
        return points.stream()
                .filter(point -> point.endDate() != null)
                .max(Comparator.comparing(FinancialFactPoint::endDate))
                .orElse(null);
    }

    /**
     * A current balance-sheet date must not make an obsolete duration concept
     * look current. Some issuers stopped reporting a legacy XBRL tag years ago;
     * previously that stale operating-income or revenue series was combined
     * with current facts and produced plausible-looking but false margins.
     */
    private static CompanyFundamentalsEvidence suppressStaleSeries(
            CompanyFundamentalsEvidence evidence,
            LocalDate referenceDate
    ) {
        return new CompanyFundamentalsEvidence(
                currentSeries(evidence.revenue(), referenceDate),
                currentSeries(evidence.operatingIncome(), referenceDate),
                currentSeries(evidence.netIncome(), referenceDate),
                currentSeries(evidence.operatingCashFlow(), referenceDate),
                currentSeries(evidence.capitalExpenditure(), referenceDate),
                currentSeries(evidence.cash(), referenceDate),
                currentSeries(evidence.debt(), referenceDate),
                currentSeries(evidence.sharesOutstanding(), referenceDate),
                currentSeries(evidence.stockCompensation(), referenceDate),
                currentSeries(evidence.stockholdersEquity(), referenceDate),
                currentSeries(evidence.currentAssets(), referenceDate),
                currentSeries(evidence.currentLiabilities(), referenceDate),
                currentSeries(evidence.receivables(), referenceDate),
                currentSeries(evidence.inventory(), referenceDate),
                currentSeries(evidence.pretaxIncome(), referenceDate),
                currentSeries(evidence.incomeTaxExpense(), referenceDate),
                currentSeries(evidence.totalAssets(), referenceDate),
                currentSeries(evidence.weightedAverageDilutedShares(), referenceDate),
                currentSeries(evidence.costsAndExpenses(), referenceDate)
        );
    }

    /**
     * Some issuers disclose no standardized total-revenue concept in SEC
     * Company Facts while publishing both total costs/expenses and operating
     * income for the same statement periods. The audited income-statement
     * identity Revenue = Costs and expenses + Operating income is an exact,
     * period-aligned fallback; unmatched periods are never guessed or mixed.
     */
    private static List<FinancialFactPoint> deriveRevenueFromOperatingIdentity(
            List<FinancialFactPoint> operatingIncome,
            List<FinancialFactPoint> costsAndExpenses
    ) {
        if (operatingIncome.isEmpty() || costsAndExpenses.isEmpty()) return List.of();
        var expensesByPeriod = new LinkedHashMap<FactPeriod, FinancialFactPoint>();
        for (var point : costsAndExpenses) {
            if (point.endDate() == null || point.value() < 0) continue;
            expensesByPeriod.putIfAbsent(FactPeriod.from(point), point);
        }
        var result = new ArrayList<FinancialFactPoint>();
        var seen = new LinkedHashSet<FactPeriod>();
        for (var income : operatingIncome) {
            if (income.endDate() == null) continue;
            var period = FactPeriod.from(income);
            if (!seen.add(period)) continue;
            var expenses = expensesByPeriod.get(period);
            if (expenses == null) continue;
            var value = expenses.value() + income.value();
            if (!Double.isFinite(value) || value <= 0) continue;
            result.add(new FinancialFactPoint(
                    value,
                    income.form(),
                    income.fiscalPeriod(),
                    income.endDate(),
                    income.startDate()
            ));
        }
        return List.copyOf(result);
    }

    private static List<FinancialFactPoint> currentSeries(
            List<FinancialFactPoint> points,
            LocalDate referenceDate
    ) {
        var eligible = points.stream()
                .filter(point -> {
                    var date = parseDate(point.endDate());
                    return date == null || !date.isAfter(referenceDate.plusDays(2));
                })
                .toList();
        var latest = eligible.stream()
                .map(FinancialFactPoint::endDate)
                .map(CompanyFundamentalsNormalizationPolicy::parseDate)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (latest == null) return eligible;
        var age = ChronoUnit.DAYS.between(latest, referenceDate);
        return age > MAX_FINANCIAL_SERIES_AGE_DAYS ? List.of() : eligible;
    }

    private static AnnualValue latestAnnual(List<FinancialFactPoint> points) {
        var annual = latestAnnualSeries(points);
        if (annual.isEmpty() || annual.getFirst().value() == 0) return null;
        return new AnnualValue(
                annual.getFirst().value(),
                annual.size() > 1 ? annual.get(1).value() : null
        );
    }

    private static List<FinancialFactPoint> latestAnnualSeries(List<FinancialFactPoint> points) {
        var sorted = points.stream()
                .filter(CompanyFundamentalsNormalizationPolicy::isAnnualFact)
                .filter(point -> point.endDate() != null)
                .sorted(Comparator.comparing(FinancialFactPoint::endDate).reversed())
                .toList();
        var dates = new LinkedHashSet<String>();
        var result = new ArrayList<FinancialFactPoint>();
        for (var point : sorted) {
            if (dates.add(point.endDate())) result.add(point);
            if (result.size() == 4) break;
        }
        return List.copyOf(result);
    }

    private static List<FinancialFactPoint> latestQuarterlies(List<FinancialFactPoint> points) {
        return points.stream()
                .filter(point -> isQuarterlyReport(point.form()))
                .filter(point -> point.endDate() != null)
                .sorted(Comparator.comparing(FinancialFactPoint::endDate).reversed())
                .limit(4)
                .toList();
    }

    private static Double sumTtm(List<FinancialFactPoint> points) {
        var annual = latestAnnual(points);
        var annualPlusYtd = annualPlusYearToDate(points);
        if (annualPlusYtd != null) return annualPlusYtd;

        var standalone = latestStandaloneQuarters(points);
        if (standalone.size() == 4) {
            var firstStart = parseDate(standalone.getLast().startDate());
            var lastEnd = parseDate(standalone.getFirst().endDate());
            var latestReportedEnd = latestReportedEnd(points);
            if (firstStart != null && lastEnd != null
                    && (latestReportedEnd == null
                    || Math.abs(ChronoUnit.DAYS.between(lastEnd, latestReportedEnd)) <= 45)) {
                var coverage = ChronoUnit.DAYS.between(firstStart, lastEnd);
                if (coverage >= 300 && coverage <= 430) {
                    return finiteSum(standalone);
                }
            }
        }

        // Compatibility for callers that pre-normalize four explicit quarters
        // but do not yet supply duration metadata. Requiring four distinct
        // fiscal periods prevents Q1/Q2/Q3 YTD values from being double-counted.
        var legacyQuarterlies = latestQuarterlies(points);
        var legacyPeriods = legacyQuarterlies.stream()
                .map(FinancialFactPoint::fiscalPeriod)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (legacyQuarterlies.size() == 4
                && legacyPeriods.size() == 4
                && isContinuousLegacyQuarterSet(legacyQuarterlies, latestReportedEnd(points))) {
            return finiteSum(legacyQuarterlies);
        }
        if (annual == null) return null;
        var annualPoint = latestAnnualPoint(points);
        var annualEnd = annualPoint == null ? null : parseDate(annualPoint.endDate());
        var latestReportedEnd = latestReportedEnd(points);
        // Never publish an old FY value as today's TTM merely because the same
        // XBRL concept reappeared in recent quarterlies without enough periods
        // to reconstruct a continuous trailing year. That mixed PSA's old
        // operating-income FY with current revenue and produced a plausible but
        // false current margin.
        if (annualEnd != null && latestReportedEnd != null
                && latestReportedEnd.isAfter(annualEnd.plusDays(45))) {
            return null;
        }
        return annual.value();
    }

    private static boolean isContinuousLegacyQuarterSet(
            List<FinancialFactPoint> quarterlies,
            LocalDate latestReportedEnd
    ) {
        if (quarterlies.size() != 4) return false;
        var ends = quarterlies.stream()
                .map(FinancialFactPoint::endDate)
                .map(CompanyFundamentalsNormalizationPolicy::parseDate)
                .toList();
        if (ends.stream().anyMatch(Objects::isNull)) return false;
        if (new LinkedHashSet<>(ends).size() != 4) return false;
        if (latestReportedEnd != null
                && Math.abs(ChronoUnit.DAYS.between(ends.getFirst(), latestReportedEnd)) > 45) {
            return false;
        }
        for (var index = 0; index + 1 < ends.size(); index++) {
            var gap = ChronoUnit.DAYS.between(ends.get(index + 1), ends.get(index));
            if (gap < 60 || gap > 135) return false;
        }
        var coverage = ChronoUnit.DAYS.between(ends.getLast(), ends.getFirst());
        return coverage >= 240 && coverage <= 380;
    }

    /**
     * True TTM = latest audited FY + current YTD - prior-year comparable YTD.
     * SEC 10-Q arrays contain both standalone and cumulative values; period
     * start/end dates are the only transport-neutral way to separate them.
     */
    private static Double annualPlusYearToDate(List<FinancialFactPoint> points) {
        var annual = latestAnnualPoint(points);
        if (annual == null) return null;
        var annualEnd = parseDate(annual.endDate());
        if (annualEnd == null) return null;

        var current = points.stream()
                .filter(point -> isQuarterlyReport(point.form()))
                .filter(point -> point.startDate() != null && point.endDate() != null)
                .filter(point -> {
                    var start = parseDate(point.startDate());
                    var end = parseDate(point.endDate());
                    return start != null && end != null
                            && end.isAfter(annualEnd)
                            && !start.isBefore(annualEnd.minusDays(7))
                            && !start.isAfter(annualEnd.plusDays(45));
                })
                .max(Comparator
                        .comparing(FinancialFactPoint::endDate)
                        .thenComparingLong(CompanyFundamentalsNormalizationPolicy::durationDays))
                .orElse(null);
        if (current == null) return null;

        var currentStart = parseDate(current.startDate());
        var currentEnd = parseDate(current.endDate());
        if (currentStart == null || currentEnd == null) return null;
        var latestReportedEnd = latestReportedEnd(points);
        if (latestReportedEnd != null
                && Math.abs(ChronoUnit.DAYS.between(currentEnd, latestReportedEnd)) > 45) {
            return null;
        }
        var targetStart = currentStart.minusYears(1);
        var targetEnd = currentEnd.minusYears(1);
        var prior = points.stream()
                .filter(point -> isQuarterlyReport(point.form()))
                .filter(point -> point.startDate() != null && point.endDate() != null)
                .filter(point -> {
                    var start = parseDate(point.startDate());
                    var end = parseDate(point.endDate());
                    return start != null && end != null
                            && Math.abs(ChronoUnit.DAYS.between(targetStart, start)) <= 45
                            && Math.abs(ChronoUnit.DAYS.between(targetEnd, end)) <= 45
                            && Math.abs(durationDays(point) - durationDays(current)) <= 45;
                })
                .min(Comparator.comparingLong(point -> comparablePeriodDistance(point, targetStart, targetEnd)))
                .orElse(null);
        if (prior == null) return null;
        var value = annual.value() + current.value() - prior.value();
        return Double.isFinite(value) ? value : null;
    }

    private static FinancialFactPoint latestAnnualPoint(List<FinancialFactPoint> points) {
        return points.stream()
                .filter(CompanyFundamentalsNormalizationPolicy::isAnnualFact)
                .filter(point -> point.endDate() != null)
                .max(Comparator.comparing(FinancialFactPoint::endDate))
                .orElse(null);
    }

    private static LocalDate latestReportedEnd(List<FinancialFactPoint> points) {
        return points.stream()
                .filter(point -> isAnnualReport(point.form()) || isQuarterlyReport(point.form()))
                .map(FinancialFactPoint::endDate)
                .map(CompanyFundamentalsNormalizationPolicy::parseDate)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private static List<FinancialFactPoint> latestStandaloneQuarters(List<FinancialFactPoint> points) {
        var sorted = points.stream()
                .filter(point -> isQuarterlyReport(point.form()) || isAnnualReport(point.form()))
                .filter(point -> point.startDate() != null && point.endDate() != null)
                .filter(point -> {
                    var duration = durationDays(point);
                    return duration >= 70 && duration <= 120;
                })
                .sorted(Comparator.comparing(FinancialFactPoint::endDate).reversed())
                .toList();
        var periods = new LinkedHashSet<String>();
        var result = new ArrayList<FinancialFactPoint>();
        for (var point : sorted) {
            if (!periods.add(point.startDate() + "|" + point.endDate())) continue;
            result.add(point);
            if (result.size() == 4) break;
        }
        return List.copyOf(result);
    }

    private static boolean isAnnualFact(FinancialFactPoint point) {
        if (!isAnnualReport(point.form()) || !"FY".equals(point.fiscalPeriod())) return false;
        if (point.startDate() == null || point.endDate() == null) return true;
        var duration = durationDays(point);
        return duration >= 300 && duration <= 430;
    }

    private static long durationDays(FinancialFactPoint point) {
        var start = parseDate(point.startDate());
        var end = parseDate(point.endDate());
        return start == null || end == null ? -1 : ChronoUnit.DAYS.between(start, end) + 1;
    }

    private static boolean isAnnualReport(String form) {
        return "10-K".equals(form) || "20-F".equals(form);
    }

    private static boolean isQuarterlyReport(String form) {
        return "10-Q".equals(form) || "6-K".equals(form);
    }

    private static long comparablePeriodDistance(
            FinancialFactPoint point,
            LocalDate targetStart,
            LocalDate targetEnd
    ) {
        var start = parseDate(point.startDate());
        var end = parseDate(point.endDate());
        if (start == null || end == null) return Long.MAX_VALUE;
        return Math.abs(ChronoUnit.DAYS.between(targetStart, start))
                + Math.abs(ChronoUnit.DAYS.between(targetEnd, end));
    }

    private static LocalDate parseDate(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Double finiteSum(List<FinancialFactPoint> points) {
        var value = points.stream().mapToDouble(FinancialFactPoint::value).sum();
        return Double.isFinite(value) ? value : null;
    }

    private static Double yoyFromAnnual(List<FinancialFactPoint> points) {
        var annual = latestAnnual(points);
        if (annual == null || annual.previousValue() == null || annual.previousValue() == 0) return null;
        return ((annual.value() - annual.previousValue()) / Math.abs(annual.previousValue())) * 100;
    }

    /**
     * Revenue YoY must use the same trailing period as revenueTtm. The former
     * annual-only implementation kept showing the prior fiscal year's growth
     * after one or more current 10-Qs had already been incorporated into TTM.
     */
    private static Double trailingYoY(List<FinancialFactPoint> points) {
        var current = sumTtm(points);
        var currentEnd = points.stream()
                .filter(point -> isAnnualReport(point.form()) || isQuarterlyReport(point.form()))
                .map(FinancialFactPoint::endDate)
                .map(CompanyFundamentalsNormalizationPolicy::parseDate)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (current == null || currentEnd == null) return null;

        var target = currentEnd.minusYears(1);
        var priorEnd = points.stream()
                .filter(point -> isAnnualReport(point.form()) || isQuarterlyReport(point.form()))
                .map(FinancialFactPoint::endDate)
                .map(CompanyFundamentalsNormalizationPolicy::parseDate)
                .filter(Objects::nonNull)
                .filter(end -> Math.abs(ChronoUnit.DAYS.between(target, end)) <= 45)
                .min(Comparator.comparingLong(end -> Math.abs(ChronoUnit.DAYS.between(target, end))))
                .orElse(null);
        if (priorEnd == null) return yoyFromAnnual(points);

        var priorPoints = points.stream()
                .filter(point -> {
                    var end = parseDate(point.endDate());
                    return end != null && !end.isAfter(priorEnd);
                })
                .toList();
        var previous = sumTtm(priorPoints);
        if (previous == null || previous <= 0) return yoyFromAnnual(points);
        var value = ((current - previous) / Math.abs(previous)) * 100;
        return Double.isFinite(value) && value >= -100 && value <= 200 ? value : null;
    }

    private static Double annualCagr(List<FinancialFactPoint> points, int targetYears) {
        var annual = latestAnnualSeries(points);
        if (annual.size() < targetYears + 1) return null;
        var latest = annual.getFirst();
        var prior = annual.get(targetYears);
        if (latest.value() <= 0 || prior.value() <= 0) return null;
        var years = elapsedYears(prior.endDate(), latest.endDate(), targetYears);
        if (years < targetYears - 0.75) return null;
        var value = (Math.pow(latest.value() / prior.value(), 1.0 / years) - 1) * 100;
        return Double.isFinite(value) && value >= -100 && value <= 200 ? value : null;
    }

    /**
     * SEC companyfacts can contain pre-split historical share counts together
     * with post-split restated recent periods. Treating that basis break as
     * economic dilution produced false 100%+ dilution penalties. A canonical
     * split-like discontinuity is therefore unavailable evidence, not dilution.
     * Real non-canonical issuance remains measurable and is not clipped.
     */
    private static Double dilutionYoY(List<FinancialFactPoint> points) {
        var annual = latestAnnualSeries(points);
        if (annual.size() < 2 || hasSplitLikeBasisBreak(annual.subList(0, 2))) return null;
        return yoyFromAnnual(points);
    }

    private static Double dilutionCagr(List<FinancialFactPoint> points, int targetYears) {
        var annual = latestAnnualSeries(points);
        if (annual.size() < targetYears + 1
                || hasSplitLikeBasisBreak(annual.subList(0, targetYears + 1))) {
            return null;
        }
        return annualCagr(points, targetYears);
    }

    private static boolean hasSplitLikeBasisBreak(List<FinancialFactPoint> annual) {
        for (var index = 0; index + 1 < annual.size(); index++) {
            var left = annual.get(index).value();
            var right = annual.get(index + 1).value();
            if (left <= 0 || right <= 0) continue;
            var factor = Math.max(left / right, right / left);
            for (var common : COMMON_SHARE_SPLIT_FACTORS) {
                if (Math.abs(factor / common - 1) <= SHARE_SPLIT_FACTOR_TOLERANCE) return true;
            }
        }
        return false;
    }

    private static double elapsedYears(String earlier, String later, int fallback) {
        try {
            var days = ChronoUnit.DAYS.between(LocalDate.parse(earlier), LocalDate.parse(later));
            return days > 0 ? days / 365.2425 : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static Double effectiveTaxRate(CompanyFundamentalsEvidence evidence) {
        var tax = currentAnnualValue(evidence.incomeTaxExpense());
        var pretax = currentAnnualValue(evidence.pretaxIncome());
        if (tax == null || pretax == null || pretax <= 0) return null;
        var rate = tax / pretax;
        if (!Double.isFinite(rate)) return null;
        return Math.max(0, Math.min(0.40, rate));
    }

    private static Double currentAnnualValue(List<FinancialFactPoint> points) {
        var value = latestAnnual(points);
        return value == null ? null : value.value();
    }

    private static AverageValue averageInvestedCapital(CompanyFundamentalsEvidence evidence) {
        var current = investedCapitalAt(evidence, 0);
        if (current == null || current <= 0) return AverageValue.unavailable();
        var previous = investedCapitalAt(evidence, 1);
        if (previous == null || previous <= 0) return new AverageValue(current, true);
        return new AverageValue((current + previous) / 2.0, false);
    }

    private static Double investedCapitalAt(CompanyFundamentalsEvidence evidence, int index) {
        var equity = annualValueAt(evidence.stockholdersEquity(), index);
        if (equity == null) return null;
        var debt = annualValueAt(evidence.debt(), index);
        var cash = annualValueAt(evidence.cash(), index);
        var value = equity + nullToZero(debt) - nullToZero(cash);
        return Double.isFinite(value) ? value : null;
    }

    private static AverageValue averageAnnualValue(List<FinancialFactPoint> points) {
        var current = annualValueAt(points, 0);
        if (current == null || current <= 0) return AverageValue.unavailable();
        var previous = annualValueAt(points, 1);
        if (previous == null || previous <= 0) return new AverageValue(current, true);
        return new AverageValue((current + previous) / 2.0, false);
    }

    private static Double annualValueAt(List<FinancialFactPoint> points, int index) {
        var annual = latestAnnualSeries(points);
        return annual.size() > index ? annual.get(index).value() : null;
    }

    private static Double trendFromAnnualRatio(
            List<FinancialFactPoint> numeratorPoints,
            List<FinancialFactPoint> denominatorPoints
    ) {
        var numerators = latestAnnualSeries(numeratorPoints);
        var denominators = latestAnnualSeries(denominatorPoints);
        var denominatorByDate = denominators.stream().collect(java.util.stream.Collectors.toMap(
                FinancialFactPoint::endDate,
                FinancialFactPoint::value,
                (left, right) -> left
        ));
        var matched = numerators.stream()
                .filter(point -> denominatorByDate.containsKey(point.endDate()))
                .limit(2)
                .toList();
        if (matched.size() < 2) return null;
        var latestDate = parseDate(matched.get(0).endDate());
        var previousDate = parseDate(matched.get(1).endDate());
        if (latestDate == null || previousDate == null) return null;
        var periodGap = ChronoUnit.DAYS.between(previousDate, latestDate);
        if (periodGap < 300 || periodGap > 430) return null;
        var latestDenominator = denominatorByDate.get(matched.get(0).endDate());
        var previousDenominator = denominatorByDate.get(matched.get(1).endDate());
        if (latestDenominator == 0 || previousDenominator == 0) return null;
        var latestRatio = (matched.get(0).value() / latestDenominator) * 100;
        var previousRatio = (matched.get(1).value() / previousDenominator) * 100;
        return latestRatio - previousRatio;
    }

    private static Double ratio(Double numerator, Double denominator, double minimum, double maximum) {
        if (numerator == null || denominator == null || denominator <= 0) return null;
        var value = (numerator / denominator) * 100;
        if (!Double.isFinite(value) || value < minimum || value > maximum) return null;
        return value;
    }

    private static Double multiple(
            Double numerator,
            Double denominator,
            Double minimum,
            Double maximum
    ) {
        if (numerator == null || denominator == null || denominator <= 0) return null;
        var value = numerator / denominator;
        if (!Double.isFinite(value)) return null;
        if (minimum != null && value < minimum) return null;
        if (maximum != null && value > maximum) return null;
        return value;
    }

    private static Double saneWorkingCapitalValue(
            Double value,
            Double currentAssets,
            Double revenueTtm,
            double maximumRevenueRatio
    ) {
        if (value == null || value < 0) return null;
        if (currentAssets != null && currentAssets > 0 && value > currentAssets * 0.95) return null;
        if (revenueTtm != null && revenueTtm > 0 && value > revenueTtm * maximumRevenueRatio) return null;
        return value;
    }

    private static String dateOfCoreFacts(
            List<FinancialFactPoint> normalizedRevenue,
            CompanyFundamentalsEvidence evidence,
            LocalDate fallbackDate
    ) {
        // The public as-of date is the period of the revenue model that drives
        // growth, margins and valuation. A newer cash/share point must not make
        // an older income statement look current after a 10-Q has been filed.
        var revenueDate = normalizedRevenue.stream()
                .map(FinancialFactPoint::endDate)
                .filter(Objects::nonNull)
                .max(String::compareTo)
                .orElse(null);
        if (revenueDate != null) return revenueDate;
        return Stream.of(
                        evidence.revenue(),
                        evidence.cash(),
                        evidence.debt(),
                        evidence.sharesOutstanding()
                )
                .flatMap(List::stream)
                .map(FinancialFactPoint::endDate)
                .filter(Objects::nonNull)
                .max(String::compareTo)
                .orElse(fallbackDate.toString());
    }

    private static double nullToZero(Double value) {
        return value == null ? 0 : value;
    }

    private record AnnualValue(double value, Double previousValue) {
    }

    private record AverageValue(Double value, boolean estimated) {
        private static AverageValue unavailable() {
            return new AverageValue(null, false);
        }
    }

    private record ValuationResolution(
            Double marketCap,
            Double resolvedShares,
            CompanyValuationQuality quality
    ) {
    }

    private record FactPeriod(
            String form,
            String fiscalPeriod,
            String startDate,
            String endDate
    ) {
        private static FactPeriod from(FinancialFactPoint point) {
            return new FactPeriod(
                    point.form(), point.fiscalPeriod(), point.startDate(), point.endDate()
            );
        }
    }
}
