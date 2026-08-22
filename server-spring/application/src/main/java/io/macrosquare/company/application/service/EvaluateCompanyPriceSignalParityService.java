package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyPriceSignalSnapshot;
import io.macrosquare.company.application.model.CompanyPriceSignalSnapshot.ChartMarker;
import io.macrosquare.company.application.port.in.CompanyPriceSignalParityReport;
import io.macrosquare.company.application.port.in.EvaluateCompanyPriceSignalParityUseCase;
import io.macrosquare.company.application.port.out.CompanyResearchParityUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyPriceHistoryPort;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.domain.bottom.BottomPatternPhase;
import io.macrosquare.company.domain.bottom.BottomPatternPoint;
import io.macrosquare.company.domain.bottom.BottomPriceContext;
import io.macrosquare.company.domain.bottom.BottomPriceContextPolicy;
import io.macrosquare.company.domain.bottom.BottomPriceSignal;
import io.macrosquare.company.domain.bottom.BottomPriceSignalPolicy;
import io.macrosquare.company.domain.bottom.CompanyPriceHistoryQualityPolicy;
import io.macrosquare.company.domain.bottom.DeepBottomPolicy;
import io.macrosquare.company.domain.bottom.DeepBottomSignal;
import io.macrosquare.company.domain.bottom.PriceStructurePolicy;
import io.macrosquare.company.domain.bottom.ReversalConfirmation;
import io.macrosquare.company.domain.bottom.ReversalConfirmationEvidence;
import io.macrosquare.company.domain.bottom.ReversalConfirmationPolicy;
import io.macrosquare.company.domain.bottom.VolumePriceConfirmationPolicy;
import io.macrosquare.company.domain.horizon.CompanyHorizonWalkForwardPolicy;
import io.macrosquare.technical.domain.MacdMultiTimeframeAnalysis;
import io.macrosquare.technical.domain.MacdSignalPolicy;
import io.macrosquare.technical.domain.TechnicalClosePoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Direct Yahoo history and bottom/reversal calculation seam. The legacy
 * projection is retained only for migration diagnostics; current EPS-revision
 * evidence is loaded through the analyst port and never inferred from target
 * price movement stored in the cutover seed.
 */
public final class EvaluateCompanyPriceSignalParityService implements EvaluateCompanyPriceSignalParityUseCase {

    private static final int CURRENT_SIGNAL_LOOKBACK_DAYS = 380;

    private final LoadCompanyReadPort companyReadPort;
    private final LoadCompanyPriceHistoryPort priceHistoryPort;
    private final BottomPriceContextPolicy contextPolicy;
    private final BottomPriceSignalPolicy priceSignalPolicy;
    private final DeepBottomPolicy deepBottomPolicy;
    private final ReversalConfirmationPolicy reversalPolicy;
    private final VolumePriceConfirmationPolicy technicalPolicy;
    private final PriceStructurePolicy priceStructurePolicy;
    private final CompanyHorizonWalkForwardPolicy walkForwardPolicy;
    private final MacdSignalPolicy macdSignalPolicy;
    private final CompanyPriceHistoryQualityPolicy historyQualityPolicy = new CompanyPriceHistoryQualityPolicy();
    private final int lookbackDays;

    public EvaluateCompanyPriceSignalParityService(
            LoadCompanyReadPort companyReadPort,
            LoadCompanyPriceHistoryPort priceHistoryPort,
            BottomPriceContextPolicy contextPolicy,
            BottomPriceSignalPolicy priceSignalPolicy,
            DeepBottomPolicy deepBottomPolicy,
            ReversalConfirmationPolicy reversalPolicy,
            int lookbackDays
    ) {
        this(
                companyReadPort, priceHistoryPort, contextPolicy, priceSignalPolicy, deepBottomPolicy,
                reversalPolicy, new VolumePriceConfirmationPolicy(), new PriceStructurePolicy(),
                new CompanyHorizonWalkForwardPolicy(
                        contextPolicy, priceSignalPolicy, deepBottomPolicy, reversalPolicy,
                        new VolumePriceConfirmationPolicy()),
                new MacdSignalPolicy(),
                lookbackDays
        );
    }

    public EvaluateCompanyPriceSignalParityService(
            LoadCompanyReadPort companyReadPort,
            LoadCompanyPriceHistoryPort priceHistoryPort,
            BottomPriceContextPolicy contextPolicy,
            BottomPriceSignalPolicy priceSignalPolicy,
            DeepBottomPolicy deepBottomPolicy,
            ReversalConfirmationPolicy reversalPolicy,
            VolumePriceConfirmationPolicy technicalPolicy,
            CompanyHorizonWalkForwardPolicy walkForwardPolicy,
            int lookbackDays
    ) {
        this(
                companyReadPort, priceHistoryPort, contextPolicy, priceSignalPolicy, deepBottomPolicy,
                reversalPolicy, technicalPolicy, new PriceStructurePolicy(), walkForwardPolicy,
                new MacdSignalPolicy(),
                lookbackDays
        );
    }

    public EvaluateCompanyPriceSignalParityService(
            LoadCompanyReadPort companyReadPort,
            LoadCompanyPriceHistoryPort priceHistoryPort,
            BottomPriceContextPolicy contextPolicy,
            BottomPriceSignalPolicy priceSignalPolicy,
            DeepBottomPolicy deepBottomPolicy,
            ReversalConfirmationPolicy reversalPolicy,
            VolumePriceConfirmationPolicy technicalPolicy,
            PriceStructurePolicy priceStructurePolicy,
            CompanyHorizonWalkForwardPolicy walkForwardPolicy,
            int lookbackDays
    ) {
        this(
                companyReadPort, priceHistoryPort, contextPolicy, priceSignalPolicy, deepBottomPolicy,
                reversalPolicy, technicalPolicy, priceStructurePolicy, walkForwardPolicy,
                new MacdSignalPolicy(), lookbackDays
        );
    }

    public EvaluateCompanyPriceSignalParityService(
            LoadCompanyReadPort companyReadPort,
            LoadCompanyPriceHistoryPort priceHistoryPort,
            BottomPriceContextPolicy contextPolicy,
            BottomPriceSignalPolicy priceSignalPolicy,
            DeepBottomPolicy deepBottomPolicy,
            ReversalConfirmationPolicy reversalPolicy,
            VolumePriceConfirmationPolicy technicalPolicy,
            PriceStructurePolicy priceStructurePolicy,
            CompanyHorizonWalkForwardPolicy walkForwardPolicy,
            MacdSignalPolicy macdSignalPolicy,
            int lookbackDays
    ) {
        this.companyReadPort = Objects.requireNonNull(companyReadPort);
        this.priceHistoryPort = Objects.requireNonNull(priceHistoryPort);
        this.contextPolicy = Objects.requireNonNull(contextPolicy);
        this.priceSignalPolicy = Objects.requireNonNull(priceSignalPolicy);
        this.deepBottomPolicy = Objects.requireNonNull(deepBottomPolicy);
        this.reversalPolicy = Objects.requireNonNull(reversalPolicy);
        this.technicalPolicy = Objects.requireNonNull(technicalPolicy);
        this.priceStructurePolicy = Objects.requireNonNull(priceStructurePolicy);
        this.walkForwardPolicy = Objects.requireNonNull(walkForwardPolicy);
        this.macdSignalPolicy = Objects.requireNonNull(macdSignalPolicy);
        if (lookbackDays < 120) throw new IllegalArgumentException("lookbackDays must be at least 120");
        this.lookbackDays = lookbackDays;
    }

    @Override
    public CompanyPriceSignalParityReport evaluate(String ticker) {
        var basis = evaluateBasis(ticker);
        var walkForward = walkForwardPolicy.evaluate(basis.directHistory());
        var springSnapshot = basis.snapshot(walkForward);

        CompanyPriceSignalLegacyProjection legacy = null;
        String legacyUnavailableReason = null;
        try {
            legacy = CompanyPriceSignalLegacyProjection.from(companyReadPort.detail(basis.normalizedTicker()));
            if (!basis.canonicalTicker().equals(legacy.ticker())) {
                legacyUnavailableReason = "legacyProjection.tickerMismatch";
            }
        } catch (RuntimeException error) {
            legacyUnavailableReason = "legacyProjection.unavailable:" + error.getClass().getSimpleName();
        }
        var legacyAvailable = legacy != null && legacyUnavailableReason == null;
        // The placeholder is never used as current evidence. It only keeps the
        // diagnostic response shape stable while legacy availability is exposed
        // explicitly and every parity flag fails closed.
        var legacySnapshot = legacy == null ? springSnapshot : legacy.snapshot();

        var historyDifferences = legacyAvailable
                ? compareHistory(legacy.chartPoints(), basis.context().chartPoints()) : new ArrayList<String>();
        var markerDifferences = legacyAvailable
                ? compareMarkers(legacy.markers(), basis.markers()) : new ArrayList<String>();
        var priceSignalDifferences = legacyAvailable
                ? comparePriceSignal(legacy.priceSignal(), basis.priceSignal()) : new ArrayList<String>();
        var confirmedBottomDifferences = legacyAvailable
                ? compareConfirmedBottom(legacy.confirmedBottom(), basis.confirmedBottom()) : new ArrayList<String>();
        var reversalDifferences = legacyAvailable
                ? compareReversal(legacy.reversalConfirmation(), basis.reversal()) : new ArrayList<String>();
        var differences = new ArrayList<String>();
        if (!legacyAvailable) differences.add(legacyUnavailableReason == null
                ? "legacyProjection.unavailable" : legacyUnavailableReason);
        differences.addAll(historyDifferences);
        differences.addAll(markerDifferences);
        differences.addAll(priceSignalDifferences);
        differences.addAll(confirmedBottomDifferences);
        differences.addAll(reversalDifferences);

        return new CompanyPriceSignalParityReport(
                basis.canonicalTicker(),
                lookbackDays,
                legacyAvailable && differences.isEmpty(),
                legacyAvailable && historyDifferences.isEmpty(),
                legacyAvailable && markerDifferences.isEmpty(),
                legacyAvailable && priceSignalDifferences.isEmpty(),
                legacyAvailable && confirmedBottomDifferences.isEmpty(),
                legacyAvailable && reversalDifferences.isEmpty(),
                differences,
                legacySnapshot,
                springSnapshot,
                basis.context(),
                legacyAvailable
        );
    }

    @Override
    public CompanyPriceSignalParityReport evaluateCurrent(String ticker) {
        var basis = evaluateBasis(ticker);
        var snapshot = basis.snapshot(null);
        return new CompanyPriceSignalParityReport(
                basis.canonicalTicker(),
                lookbackDays,
                false,
                false,
                false,
                false,
                false,
                false,
                List.of("legacyProjection.skippedForCurrentOnly"),
                snapshot,
                snapshot,
                basis.context(),
                false
        );
    }

    private EvaluationBasis evaluateBasis(String ticker) {
        var normalizedTicker = normalizeTicker(ticker);
        var canonicalTicker = normalizedTicker.replace('.', '-');

        var directHistory = priceHistoryPort.load(canonicalTicker);
        var historyQuality = historyQualityPolicy.evaluate(directHistory);
        if (!historyQuality.eligible()) {
            throw new CompanyResearchParityUnavailableException(
                    "Direct company price history failed corporate-action basis validation",
                    new IllegalStateException(String.join("; ", historyQuality.warnings()))
            );
        }
        // The Yahoo adapter intentionally retains five-plus years for walk-forward
        // validation. Current bottom, volume, and price-structure signals must not
        // accidentally use that entire history: the legacy/current contract is a
        // bounded calendar-day window. In particular, using a five-year low here
        // makes the current rebound percentage several hundred percent and can
        // overstate a fresh bottom setup.
        var signalHistory = currentSignalWindow(directHistory);
        var context = contextPolicy.evaluate(signalHistory);
        // The chart signal is intentionally pure price/volume evidence. The
        // legacy projection below exists only for cutover diagnostics; its
        // captured B score or guidance must not contaminate today's signal.
        var priceSignal = priceSignalPolicy.evaluate(context);
        var confirmedBottom = deepBottomPolicy.evaluate(
                context.toDeepBottomEvidence(priceSignal.failureRiskScore())
        );
        // Reversal confirmation must consume evidence independent from the
        // bottom-event score. OBV/VWAP follow-through and the full support/
        // trend structure are calculated first; the looser bottom sub-scores
        // must never stand in for them.
        var technical = technicalPolicy.evaluate(signalHistory);
        var priceStructure = priceStructurePolicy.evaluate(signalHistory);
        var macdMomentum = macdSignalPolicy.evaluate(signalHistory.stream()
                .map(point -> new TechnicalClosePoint(point.date(), point.close()))
                .toList());
        var reversal = reversalPolicy.evaluate(new ReversalConfirmationEvidence(
                confirmedBottom,
                technical.score(),
                priceStructure.score(),
                priceSignal.structureState(),
                context.pattern().confirmPoint() == null ? null : context.pattern().confirmPoint().date(),
                List.of(),
                List.of(),
                List.of()
        ));
        return new EvaluationBasis(
                normalizedTicker,
                canonicalTicker,
                directHistory,
                context,
                priceSignal,
                confirmedBottom,
                reversal,
                technical,
                priceStructure,
                macdMomentum,
                markers(context)
        );
    }

    private record EvaluationBasis(
            String normalizedTicker,
            String canonicalTicker,
            List<BottomPatternPoint> directHistory,
            BottomPriceContext context,
            BottomPriceSignal priceSignal,
            DeepBottomSignal confirmedBottom,
            ReversalConfirmation reversal,
            io.macrosquare.company.domain.bottom.VolumePriceAnalysis technical,
            io.macrosquare.company.domain.bottom.PriceStructureAnalysis priceStructure,
            MacdMultiTimeframeAnalysis macdMomentum,
            List<ChartMarker> markers
    ) {
        private CompanyPriceSignalSnapshot snapshot(
                io.macrosquare.company.domain.horizon.CompanyWalkForwardValidation walkForward
        ) {
            return new CompanyPriceSignalSnapshot(
                    CompanyPriceSignalLegacyProjection.summary(context.chartPoints()),
                    markers,
                    priceSignal,
                    confirmedBottom,
                    reversal,
                    technical,
                    walkForward,
                    priceStructure,
                    macdMomentum
            );
        }
    }

    private List<BottomPatternPoint> currentSignalWindow(List<BottomPatternPoint> history) {
        if (history.isEmpty()) return history;
        var latestDate = history.stream()
                .map(BottomPatternPoint::date)
                .max(java.time.LocalDate::compareTo)
                .orElseThrow();
        var cutoff = latestDate.minusDays(CURRENT_SIGNAL_LOOKBACK_DAYS);
        return history.stream()
                .filter(point -> !point.date().isBefore(cutoff))
                .toList();
    }

    private static List<ChartMarker> markers(BottomPriceContext context) {
        var pattern = context.pattern();
        var markers = new ArrayList<ChartMarker>();
        add(markers, "peak", pattern.peakPoint());
        add(markers, "candidate", pattern.candidatePoint());
        add(markers, "retest", pattern.retestPoint());
        if (pattern.confirmPoint() != null) {
            add(markers, "confirm", pattern.confirmPoint());
        } else if (pattern.candidatePoint() != null && pattern.currentPoint() != null
                && !pattern.candidatePoint().date().equals(pattern.currentPoint().date())) {
            add(
                    markers,
                    pattern.phase() == BottomPatternPhase.RETEST ? "retest" : "current",
                    pattern.currentPoint()
            );
        }
        add(markers, "current", pattern.currentPoint());
        return List.copyOf(markers);
    }

    private static void add(List<ChartMarker> markers, String kind, BottomPatternPoint point) {
        if (point != null) markers.add(new ChartMarker(kind, point.date(), point.close()));
    }

    private static List<String> compareHistory(
            List<BottomPatternPoint> expected,
            List<BottomPatternPoint> actual
    ) {
        var differences = new ArrayList<String>();
        compare(differences, "priceHistory.pointCount", expected.size(), actual.size());
        var common = Math.min(expected.size(), actual.size());
        for (var index = 0; index < common; index++) {
            compare(differences, "priceHistory.points[" + index + "].date", expected.get(index).date(), actual.get(index).date());
            compare(differences, "priceHistory.points[" + index + "].close", expected.get(index).close(), actual.get(index).close());
        }
        return differences;
    }

    private static List<String> compareMarkers(List<ChartMarker> expected, List<ChartMarker> actual) {
        var differences = new ArrayList<String>();
        compare(differences, "bottomMarkers.count", expected.size(), actual.size());
        var common = Math.min(expected.size(), actual.size());
        for (var index = 0; index < common; index++) {
            var path = "bottomMarkers[" + index + "]";
            compare(differences, path + ".kind", expected.get(index).kind(), actual.get(index).kind());
            compare(differences, path + ".date", expected.get(index).date(), actual.get(index).date());
            compare(differences, path + ".value", expected.get(index).value(), actual.get(index).value());
        }
        return differences;
    }

    private static List<String> comparePriceSignal(BottomPriceSignal expected, BottomPriceSignal actual) {
        var differences = new ArrayList<String>();
        compare(differences, "priceSignal.priceResetScore", expected.priceResetScore(), actual.priceResetScore());
        compare(differences, "priceSignal.patternScore", expected.patternScore(), actual.patternScore());
        compare(differences, "priceSignal.absorptionScore", expected.absorptionScore(), actual.absorptionScore());
        compare(differences, "priceSignal.volumeConfirmationScore", expected.volumeConfirmationScore(), actual.volumeConfirmationScore());
        compare(differences, "priceSignal.priceBottomScore", expected.priceBottomScore(), actual.priceBottomScore());
        compare(differences, "priceSignal.failureRiskScore", expected.failureRiskScore(), actual.failureRiskScore());
        compare(differences, "priceSignal.structureState", expected.structureState(), actual.structureState());
        return differences;
    }

    private static List<String> compareConfirmedBottom(DeepBottomSignal expected, DeepBottomSignal actual) {
        var differences = new ArrayList<String>();
        compare(differences, "confirmedBottom.score", expected.score(), actual.score());
        compare(differences, "confirmedBottom.state", expected.state(), actual.state());
        compare(differences, "confirmedBottom.actionBias", expected.actionBias(), actual.actionBias());
        compare(differences, "confirmedBottom.signalDate", expected.signalDate(), actual.signalDate());
        compare(differences, "confirmedBottom.daysSinceSignal", expected.daysSinceSignal(), actual.daysSinceSignal());
        compare(differences, "confirmedBottom.summary", expected.summary(), actual.summary());
        compare(differences, "confirmedBottom.recentVolumeRatio", expected.recentVolumeRatio(), actual.recentVolumeRatio());
        compare(differences, "confirmedBottom.contractionRatio", expected.contractionRatio(), actual.contractionRatio());
        compare(differences, "confirmedBottom.drawdown120dPct", expected.drawdown120dPct(), actual.drawdown120dPct());
        compare(differences, "confirmedBottom.ma20GapPct", expected.ma20GapPct(), actual.ma20GapPct());
        compare(differences, "confirmedBottom.recentDrop3dPct", expected.recentDrop3dPct(), actual.recentDrop3dPct());
        compare(differences, "confirmedBottom.reasons", expected.reasons(), actual.reasons());
        compare(differences, "confirmedBottom.cautions", expected.cautions(), actual.cautions());
        return differences;
    }

    private static List<String> compareReversal(ReversalConfirmation expected, ReversalConfirmation actual) {
        var differences = new ArrayList<String>();
        compare(differences, "reversalConfirmation.status", expected.status(), actual.status());
        compare(differences, "reversalConfirmation.score", expected.score(), actual.score());
        compare(differences, "reversalConfirmation.signalDate", expected.signalDate(), actual.signalDate());
        compare(differences, "reversalConfirmation.summary", expected.summary(), actual.summary());
        compare(differences, "reversalConfirmation.reasons", expected.reasons(), actual.reasons());
        compare(differences, "reversalConfirmation.cautions", expected.cautions(), actual.cautions());
        return differences;
    }

    private static void compare(List<String> differences, String path, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) differences.add(path);
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        return ticker.trim().toUpperCase(Locale.ROOT);
    }
}
