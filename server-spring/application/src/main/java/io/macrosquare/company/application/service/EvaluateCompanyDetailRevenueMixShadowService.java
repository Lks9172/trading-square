package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.port.in.CompanyDetailRevenueMixShadowReport;
import io.macrosquare.company.application.port.in.EvaluateCompanyDetailRevenueMixShadowUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanyRevenueMixParityUseCase;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Prepares, but does not serve, a company detail with direct revenue-mix data.
 */
public final class EvaluateCompanyDetailRevenueMixShadowService
        implements EvaluateCompanyDetailRevenueMixShadowUseCase {

    private static final Set<String> MIX_FIELDS = Set.of(
            "segmentGeoMixNote", "segmentMix", "geoMix"
    );

    private final LoadCompanyReadPort companyReadPort;
    private final EvaluateCompanyRevenueMixParityUseCase revenueMixParity;
    private final CompanyRevenueMixComposer composer;

    public EvaluateCompanyDetailRevenueMixShadowService(
            LoadCompanyReadPort companyReadPort,
            EvaluateCompanyRevenueMixParityUseCase revenueMixParity,
            CompanyRevenueMixComposer composer
    ) {
        this.companyReadPort = Objects.requireNonNull(companyReadPort);
        this.revenueMixParity = Objects.requireNonNull(revenueMixParity);
        this.composer = Objects.requireNonNull(composer);
    }

    @Override
    public CompanyDetailRevenueMixShadowReport evaluate(String ticker) {
        var normalizedTicker = normalizeTicker(ticker);
        var serving = companyReadPort.detail(normalizedTicker);
        var direct = revenueMixParity.evaluate(normalizedTicker);
        var composition = composer.compose(serving, direct.spring());
        var contractCompatible = contractCompatible(serving, composition.enrichedDetail());
        var servingSnapshotMatched = composition.baseline().equals(direct.legacy());
        var shadowServeReady = contractCompatible
                && servingSnapshotMatched
                && composition.actualUsed()
                && direct.directCoveragePassed()
                && direct.percentageValidationPassed();
        return new CompanyDetailRevenueMixShadowReport(
                normalizedTicker,
                contractCompatible,
                servingSnapshotMatched,
                shadowServeReady,
                direct.migrationReady(),
                direct,
                composition
        );
    }

    private static boolean contractCompatible(Research serving, Research shadow) {
        if (!sameOutsideFinancials(serving, shadow)) return false;
        var servingFields = serving.financials().fields();
        var shadowFields = shadow.financials().fields();
        if (!servingFields.keySet().containsAll(MIX_FIELDS)
                || !servingFields.keySet().equals(shadowFields.keySet())) {
            return false;
        }
        var nonMixFields = new HashSet<>(servingFields.keySet());
        nonMixFields.removeAll(MIX_FIELDS);
        return nonMixFields.stream().allMatch(field -> Objects.equals(
                servingFields.get(field), shadowFields.get(field)
        ));
    }

    private static boolean sameOutsideFinancials(Research left, Research right) {
        return left.profile().equals(right.profile())
                && left.quote().equals(right.quote())
                && left.score().equals(right.score())
                && left.buyScore().equals(right.buyScore())
                && left.filings().equals(right.filings())
                && left.irMaterials().equals(right.irMaterials())
                && left.highlights().equals(right.highlights())
                && left.peerGroup().equals(right.peerGroup())
                && left.bottleneck().equals(right.bottleneck())
                && left.narrative().equals(right.narrative())
                && left.capitalFlow().equals(right.capitalFlow())
                && left.cashFlowQuality().equals(right.cashFlowQuality())
                && left.multipleInsight().equals(right.multipleInsight())
                && left.guidanceInsight().equals(right.guidanceInsight())
                && left.timeframeView().equals(right.timeframeView())
                && left.correctionAssessment().equals(right.correctionAssessment())
                && left.thesisMonitor().equals(right.thesisMonitor())
                && left.reversalConfirmation().equals(right.reversalConfirmation())
                && left.sectorContext().equals(right.sectorContext())
                && left.verdicts().equals(right.verdicts())
                && left.bottomSignal().equals(right.bottomSignal())
                && left.positionSizing().equals(right.positionSizing())
                && left.executionBridge().equals(right.executionBridge())
                && left.peers().equals(right.peers());
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        return ticker.trim().toUpperCase(Locale.ROOT);
    }
}
