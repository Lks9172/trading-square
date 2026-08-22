package io.macrosquare.company.application.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Persistable, transport-neutral company list read model. */
public record CompanyResearchSummarySnapshot(
        String ticker,
        LocalDate fundamentalsAsOf,
        Double marketCap,
        Double revenueGrowthYoY,
        Double operatingMargin,
        Double evToSales,
        Integer totalScore,
        Integer growthScore,
        Integer qualityScore,
        Integer valuationScore,
        Integer balanceSheetScore,
        Integer buyScore,
        String buyLabel,
        Integer appealScore,
        Integer crowdingScore,
        String valuationBasis,
        boolean valuationEligible,
        List<String> valuationWarnings,
        String fundamentalsStatus,
        LocalDate latestPeriodicReportDate,
        LocalDate latestPeriodicFilingDate,
        String latestPeriodicForm,
        Integer fundamentalsLagDays,
        List<String> scoreWarnings,
        Integer priceBottomScore,
        Integer volumeConfirmationScore,
        Integer failureRiskScore,
        Integer confirmedBottomScore,
        String confirmedBottomState,
        LocalDate confirmedBottomSignalDate,
        String reversalStatus,
        Integer reversalScore,
        List<String> priceSignalReasons,
        CompanyMacdTimingSnapshot macdTiming,
        String executionAction,
        Instant updatedAt
) {
    private static final Duration MAX_FUTURE_CLOCK_SKEW = Duration.ofMinutes(5);

    public CompanyResearchSummarySnapshot {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        ticker = ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-');
        validateMetric(marketCap, 0, Double.MAX_VALUE, false, "marketCap");
        validateMetric(revenueGrowthYoY, -100, 200, true, "revenueGrowthYoY");
        validateMetric(operatingMargin, -100, 100, true, "operatingMargin");
        validateMetric(evToSales, 0, 100, true, "evToSales");
        validateScore(totalScore, "totalScore");
        validateScore(growthScore, "growthScore");
        validateScore(qualityScore, "qualityScore");
        validateScore(valuationScore, "valuationScore");
        validateScore(balanceSheetScore, "balanceSheetScore");
        validateScore(buyScore, "buyScore");
        validateScore(appealScore, "appealScore");
        validateScore(crowdingScore, "crowdingScore");
        validateScore(priceBottomScore, "priceBottomScore");
        validateScore(volumeConfirmationScore, "volumeConfirmationScore");
        validateScore(failureRiskScore, "failureRiskScore");
        validateScore(confirmedBottomScore, "confirmedBottomScore");
        validateScore(reversalScore, "reversalScore");
        valuationWarnings = List.copyOf(valuationWarnings == null ? List.of() : valuationWarnings);
        priceSignalReasons = List.copyOf(priceSignalReasons == null ? List.of() : priceSignalReasons);
        valuationBasis = normalizeValuationBasis(valuationBasis);
        fundamentalsStatus = normalizeFundamentalsStatus(fundamentalsStatus);
        scoreWarnings = List.copyOf(scoreWarnings == null ? List.of() : scoreWarnings);
        confirmedBottomState = normalizeBottomState(confirmedBottomState);
        reversalStatus = normalizeReversalStatus(reversalStatus);
        executionAction = normalizeExecutionAction(executionAction);
        if (fundamentalsLagDays != null && fundamentalsLagDays < 0) {
            throw new IllegalArgumentException("fundamentalsLagDays must not be negative");
        }
        validateScoreBundle(
                totalScore, growthScore, qualityScore, valuationScore, balanceSheetScore,
                buyScore, appealScore, crowdingScore, buyLabel,
                fundamentalsStatus, valuationEligible
        );
        validatePriceSignalBundle(
                priceBottomScore, volumeConfirmationScore, failureRiskScore,
                confirmedBottomScore, confirmedBottomState
        );
        if ((reversalStatus == null) != (reversalScore == null)) {
            throw new IllegalArgumentException("reversal evidence bundle is incomplete");
        }
        if (reversalStatus != null && confirmedBottomState == null) {
            throw new IllegalArgumentException("reversal evidence requires current price signals");
        }
        if ("CURRENT".equals(fundamentalsStatus)
                && (fundamentalsAsOf == null || latestPeriodicReportDate == null
                || latestPeriodicFilingDate == null || latestPeriodicForm == null
                || latestPeriodicForm.isBlank())) {
            throw new IllegalArgumentException("CURRENT fundamentals require complete filing provenance");
        }
        if (("BUY".equals(executionAction) || "STRONG BUY".equals(executionAction))
                && (totalScore == null || priceBottomScore == null)) {
            throw new IllegalArgumentException("BUY action requires current score and price evidence");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** Compatibility constructor for snapshots created before notification reversal evidence was persisted. */
    public CompanyResearchSummarySnapshot(
            String ticker,
            LocalDate fundamentalsAsOf,
            Double marketCap,
            Double revenueGrowthYoY,
            Double operatingMargin,
            Double evToSales,
            Integer totalScore,
            Integer growthScore,
            Integer qualityScore,
            Integer valuationScore,
            Integer balanceSheetScore,
            Integer buyScore,
            String buyLabel,
            Integer appealScore,
            Integer crowdingScore,
            String valuationBasis,
            boolean valuationEligible,
            List<String> valuationWarnings,
            String fundamentalsStatus,
            LocalDate latestPeriodicReportDate,
            LocalDate latestPeriodicFilingDate,
            String latestPeriodicForm,
            Integer fundamentalsLagDays,
            List<String> scoreWarnings,
            Integer priceBottomScore,
            Integer volumeConfirmationScore,
            Integer failureRiskScore,
            Integer confirmedBottomScore,
            String confirmedBottomState,
            String executionAction,
            Instant updatedAt
    ) {
        this(
                ticker, fundamentalsAsOf, marketCap, revenueGrowthYoY, operatingMargin, evToSales,
                totalScore, growthScore, qualityScore, valuationScore, balanceSheetScore,
                buyScore, buyLabel, appealScore, crowdingScore,
                valuationBasis, valuationEligible, valuationWarnings,
                fundamentalsStatus, latestPeriodicReportDate, latestPeriodicFilingDate,
                latestPeriodicForm, fundamentalsLagDays, scoreWarnings,
                priceBottomScore, volumeConfirmationScore, failureRiskScore,
                confirmedBottomScore, confirmedBottomState,
                null, null, null, List.of(), null, executionAction, updatedAt
        );
    }

    /** Compatibility constructor for snapshots created before filing-freshness provenance. */
    public CompanyResearchSummarySnapshot(
            String ticker,
            LocalDate fundamentalsAsOf,
            Double marketCap,
            Double revenueGrowthYoY,
            Double operatingMargin,
            Double evToSales,
            Integer totalScore,
            Integer growthScore,
            Integer qualityScore,
            Integer valuationScore,
            Integer balanceSheetScore,
            Integer buyScore,
            String buyLabel,
            Integer appealScore,
            Integer crowdingScore,
            String valuationBasis,
            boolean valuationEligible,
            List<String> valuationWarnings,
            Integer priceBottomScore,
            Integer volumeConfirmationScore,
            Integer failureRiskScore,
            Integer confirmedBottomScore,
            String confirmedBottomState,
            Instant updatedAt
    ) {
        this(
                ticker, fundamentalsAsOf, marketCap, revenueGrowthYoY, operatingMargin, evToSales,
                totalScore, growthScore, qualityScore, valuationScore, balanceSheetScore,
                buyScore, buyLabel, appealScore, crowdingScore,
                valuationBasis, valuationEligible, valuationWarnings,
                "UNKNOWN", null, null, null, null, List.of("공시 최신성 재검증 대기 중"),
                priceBottomScore, volumeConfirmationScore, failureRiskScore,
                confirmedBottomScore, confirmedBottomState, "HOLD", updatedAt
        );
    }

    /** Compatibility constructor for callers created before authoritative execution actions. */
    public CompanyResearchSummarySnapshot(
            String ticker,
            LocalDate fundamentalsAsOf,
            Double marketCap,
            Double revenueGrowthYoY,
            Double operatingMargin,
            Double evToSales,
            Integer totalScore,
            Integer growthScore,
            Integer qualityScore,
            Integer valuationScore,
            Integer balanceSheetScore,
            Integer buyScore,
            String buyLabel,
            Integer appealScore,
            Integer crowdingScore,
            String valuationBasis,
            boolean valuationEligible,
            List<String> valuationWarnings,
            String fundamentalsStatus,
            LocalDate latestPeriodicReportDate,
            LocalDate latestPeriodicFilingDate,
            String latestPeriodicForm,
            Integer fundamentalsLagDays,
            List<String> scoreWarnings,
            Integer priceBottomScore,
            Integer volumeConfirmationScore,
            Integer failureRiskScore,
            Integer confirmedBottomScore,
            String confirmedBottomState,
            Instant updatedAt
    ) {
        this(
                ticker, fundamentalsAsOf, marketCap, revenueGrowthYoY, operatingMargin, evToSales,
                totalScore, growthScore, qualityScore, valuationScore, balanceSheetScore,
                buyScore, buyLabel, appealScore, crowdingScore,
                valuationBasis, valuationEligible, valuationWarnings,
                fundamentalsStatus, latestPeriodicReportDate, latestPeriodicFilingDate,
                latestPeriodicForm, fundamentalsLagDays, scoreWarnings,
                priceBottomScore, volumeConfirmationScore, failureRiskScore,
                confirmedBottomScore, confirmedBottomState, "HOLD", updatedAt
        );
    }

    public CompanyResearchSummarySnapshot withPriceSignals(
            Integer priceBottomScore,
            Integer volumeConfirmationScore,
            Integer failureRiskScore,
            Integer confirmedBottomScore,
            String confirmedBottomState,
            Instant updatedAt
    ) {
        return withPriceSignals(
                priceBottomScore, volumeConfirmationScore, failureRiskScore,
                confirmedBottomScore, confirmedBottomState,
                null, null, null, List.of(), updatedAt
        );
    }

    public CompanyResearchSummarySnapshot withPriceSignals(
            Integer priceBottomScore,
            Integer volumeConfirmationScore,
            Integer failureRiskScore,
            Integer confirmedBottomScore,
            String confirmedBottomState,
            LocalDate confirmedBottomSignalDate,
            String reversalStatus,
            Integer reversalScore,
            List<String> priceSignalReasons,
            Instant updatedAt
    ) {
        return new CompanyResearchSummarySnapshot(
                ticker, fundamentalsAsOf, marketCap, revenueGrowthYoY, operatingMargin, evToSales,
                totalScore, growthScore, qualityScore, valuationScore, balanceSheetScore,
                buyScore, buyLabel, appealScore, crowdingScore,
                valuationBasis, valuationEligible, valuationWarnings,
                fundamentalsStatus, latestPeriodicReportDate, latestPeriodicFilingDate,
                latestPeriodicForm, fundamentalsLagDays, scoreWarnings,
                priceBottomScore, volumeConfirmationScore, failureRiskScore,
                confirmedBottomScore, confirmedBottomState,
                confirmedBottomSignalDate, reversalStatus, reversalScore, priceSignalReasons, null,
                executionAction, updatedAt
        );
    }

    public CompanyResearchSummarySnapshot withPriceSignals(
            Integer priceBottomScore,
            Integer volumeConfirmationScore,
            Integer failureRiskScore,
            Integer confirmedBottomScore,
            String confirmedBottomState,
            LocalDate confirmedBottomSignalDate,
            String reversalStatus,
            Integer reversalScore,
            List<String> priceSignalReasons,
            CompanyMacdTimingSnapshot macdTiming,
            Instant updatedAt
    ) {
        return new CompanyResearchSummarySnapshot(
                ticker, fundamentalsAsOf, marketCap, revenueGrowthYoY, operatingMargin, evToSales,
                totalScore, growthScore, qualityScore, valuationScore, balanceSheetScore,
                buyScore, buyLabel, appealScore, crowdingScore,
                valuationBasis, valuationEligible, valuationWarnings,
                fundamentalsStatus, latestPeriodicReportDate, latestPeriodicFilingDate,
                latestPeriodicForm, fundamentalsLagDays, scoreWarnings,
                priceBottomScore, volumeConfirmationScore, failureRiskScore,
                confirmedBottomScore, confirmedBottomState,
                confirmedBottomSignalDate, reversalStatus, reversalScore, priceSignalReasons, macdTiming,
                executionAction, updatedAt
        );
    }

    public CompanyResearchSummarySnapshot withExecutionAction(String action, Instant refreshedAt) {
        return new CompanyResearchSummarySnapshot(
                ticker, fundamentalsAsOf, marketCap, revenueGrowthYoY, operatingMargin, evToSales,
                totalScore, growthScore, qualityScore, valuationScore, balanceSheetScore,
                buyScore, buyLabel, appealScore, crowdingScore,
                valuationBasis, valuationEligible, valuationWarnings,
                fundamentalsStatus, latestPeriodicReportDate, latestPeriodicFilingDate,
                latestPeriodicForm, fundamentalsLagDays, scoreWarnings,
                priceBottomScore, volumeConfirmationScore, failureRiskScore,
                confirmedBottomScore, confirmedBottomState,
                confirmedBottomSignalDate, reversalStatus, reversalScore, priceSignalReasons, macdTiming,
                action, refreshedAt
        );
    }

    /**
     * Invalidates every derived value after a current refresh failure.
     *
     * <p>The previous raw observations are retained for diagnosis, but no old
     * score or price signal is allowed to survive as if it were current. This
     * operation is ticker-agnostic and is used by the universe refresh for
     * every company.</p>
     */
    public CompanyResearchSummarySnapshot quarantined(String reason, Instant quarantinedAt) {
        var message = reason == null || reason.isBlank()
                ? "현재 원천 데이터 교차검증 실패로 파생 점수를 격리함"
                : reason.trim();
        return new CompanyResearchSummarySnapshot(
                ticker, fundamentalsAsOf, marketCap, revenueGrowthYoY, operatingMargin, null,
                null, null, null, null, null,
                null, null, null, null,
                valuationBasis, false, append(valuationWarnings, message),
                "UNAVAILABLE", latestPeriodicReportDate, latestPeriodicFilingDate,
                latestPeriodicForm, fundamentalsLagDays, append(scoreWarnings, message),
                null, null, null, null, null, "HOLD", quarantinedAt
        );
    }

    /** Removes stale price-derived evidence while preserving current fundamentals. */
    public CompanyResearchSummarySnapshot withoutPriceSignals(Instant refreshedAt) {
        return new CompanyResearchSummarySnapshot(
                ticker, fundamentalsAsOf, marketCap, revenueGrowthYoY, operatingMargin, evToSales,
                totalScore, growthScore, qualityScore, valuationScore, balanceSheetScore,
                buyScore, buyLabel, appealScore, crowdingScore,
                valuationBasis, valuationEligible, valuationWarnings,
                fundamentalsStatus, latestPeriodicReportDate, latestPeriodicFilingDate,
                latestPeriodicForm, fundamentalsLagDays, scoreWarnings,
                null, null, null, null, null, "HOLD", refreshedAt
        );
    }

    /**
     * Scores are safe to compare only when their filing freshness and every
     * required score axis are present. Raw observations remain available to a
     * company detail view even when this predicate is false.
     */
    public boolean scoreComparable() {
        return "CURRENT".equals(fundamentalsStatus)
                && valuationEligible
                && totalScore != null
                && growthScore != null
                && qualityScore != null
                && valuationScore != null
                && balanceSheetScore != null
                && buyScore != null
                && appealScore != null
                && crowdingScore != null
                && buyLabel != null;
    }

    public boolean scoreComparableAt(Instant now, Duration maximumAge) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(maximumAge, "maximumAge");
        if (maximumAge.isNegative() || maximumAge.isZero()) {
            throw new IllegalArgumentException("maximumAge must be positive");
        }
        return scoreComparable()
                && !updatedAt.isAfter(now.plus(MAX_FUTURE_CLOCK_SKEW))
                && !updatedAt.plus(maximumAge).isBefore(now);
    }

    public boolean priceSignalsCurrentAt(Instant now, Duration maximumAge) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(maximumAge, "maximumAge");
        if (maximumAge.isNegative() || maximumAge.isZero()) {
            throw new IllegalArgumentException("maximumAge must be positive");
        }
        return !updatedAt.isAfter(now.plus(MAX_FUTURE_CLOCK_SKEW))
                && !updatedAt.plus(maximumAge).isBefore(now)
                && priceBottomScore != null
                && volumeConfirmationScore != null
                && failureRiskScore != null
                && confirmedBottomScore != null
                && confirmedBottomState != null;
    }

    /** Current evidence required to evaluate and render a notification without re-running chart analysis. */
    public boolean notificationEvidenceCurrentAt(Instant now, Duration maximumAge) {
        return scoreComparableAt(now, maximumAge)
                && priceSignalsCurrentAt(now, maximumAge)
                && reversalStatus != null
                && reversalScore != null
                && macdTiming != null;
    }

    private static List<String> append(List<String> existing, String value) {
        var result = new ArrayList<>(existing == null ? List.<String>of() : existing);
        if (!result.contains(value)) result.add(value);
        return List.copyOf(result);
    }

    private static String normalizeExecutionAction(String value) {
        if (value == null || value.isBlank()) return "HOLD";
        var normalized = value.trim().toUpperCase(Locale.ROOT).replace('_', ' ');
        return switch (normalized) {
            case "STRONG BUY", "BUY", "HOLD", "REDUCE", "SELL" -> normalized;
            default -> throw new IllegalArgumentException("invalid execution action: " + normalized);
        };
    }

    private static String normalizeFundamentalsStatus(String value) {
        var normalized = value == null || value.isBlank()
                ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CURRENT", "LAGGING", "INCOMPLETE", "PENDING", "UNAVAILABLE", "UNKNOWN" -> normalized;
            default -> throw new IllegalArgumentException("invalid fundamentals status: " + normalized);
        };
    }

    private static String normalizeValuationBasis(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("valuationBasis is required");
        var normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "INDEPENDENT_MARKET_CAP", "SEC_SHARES", "UNAVAILABLE" -> normalized;
            default -> throw new IllegalArgumentException("invalid valuation basis: " + normalized);
        };
    }

    private static String normalizeBottomState(String value) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "UNMET", "CANDIDATE", "CONVICTION" -> normalized;
            default -> throw new IllegalArgumentException("invalid confirmed bottom state: " + normalized);
        };
    }

    private static String normalizeReversalStatus(String value) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "OFF", "EARLY", "ON", "STRONG" -> normalized;
            default -> throw new IllegalArgumentException("invalid reversal status: " + normalized);
        };
    }

    private static void validateScore(Integer value, String field) {
        if (value != null && (value < 0 || value > 100)) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
    }

    private static void validateMetric(
            Double value,
            double minimum,
            double maximum,
            boolean inclusiveMinimum,
            String field
    ) {
        if (value == null) return;
        if (!Double.isFinite(value)
                || (inclusiveMinimum ? value < minimum : value <= minimum)
                || value > maximum) {
            throw new IllegalArgumentException(field + " is outside the persisted contract");
        }
    }

    private static void validateScoreBundle(
            Integer total,
            Integer growth,
            Integer quality,
            Integer valuation,
            Integer balance,
            Integer buy,
            Integer appeal,
            Integer crowding,
            String label,
            String status,
            boolean valuationEligible
    ) {
        var count = java.util.stream.Stream.of(
                total, growth, quality, valuation, balance, buy, appeal, crowding
        ).filter(Objects::nonNull).count();
        if (count != 0 && count != 8) throw new IllegalArgumentException("company score bundle is incomplete");
        if ((total == null) != (label == null || label.isBlank())) {
            throw new IllegalArgumentException("buy label must match score bundle availability");
        }
        if (count > 0 && (!"CURRENT".equals(status) || !valuationEligible)) {
            throw new IllegalArgumentException("company scores require current eligible fundamentals");
        }
    }

    private static void validatePriceSignalBundle(
            Integer price,
            Integer volume,
            Integer failure,
            Integer confirmed,
            String state
    ) {
        var count = java.util.stream.Stream.of(price, volume, failure, confirmed, state)
                .filter(Objects::nonNull).count();
        if (count != 0 && count != 5) throw new IllegalArgumentException("price signal bundle is incomplete");
    }
}
