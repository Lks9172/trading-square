package io.macrosquare.research.application.service;

import io.macrosquare.research.domain.rotation.RotationMarketEvidence;
import io.macrosquare.research.domain.rotation.SectorRotationPolicy;
import io.macrosquare.research.application.model.ResearchSnapshot;
import io.macrosquare.research.application.port.in.EvaluateResearchParityUseCase;
import io.macrosquare.research.application.port.in.NarrativeParityResult;
import io.macrosquare.research.application.port.in.ResearchParityReport;
import io.macrosquare.research.application.port.in.RotationParityResult;
import io.macrosquare.research.application.port.out.LoadResearchSnapshotPort;
import io.macrosquare.research.domain.narrative.NarrativeEvidence;
import io.macrosquare.research.domain.narrative.NarrativeHeatPolicy;
import io.macrosquare.research.domain.narrative.NarrativeTheme;

import java.util.ArrayList;
import java.util.Objects;

public final class EvaluateResearchParityService implements EvaluateResearchParityUseCase {

    private final LoadResearchSnapshotPort snapshotPort;
    private final NarrativeHeatPolicy narrativePolicy;
    private final SectorRotationPolicy rotationPolicy;

    public EvaluateResearchParityService(
            LoadResearchSnapshotPort snapshotPort,
            NarrativeHeatPolicy narrativePolicy,
            SectorRotationPolicy rotationPolicy
    ) {
        this.snapshotPort = Objects.requireNonNull(snapshotPort);
        this.narrativePolicy = Objects.requireNonNull(narrativePolicy);
        this.rotationPolicy = Objects.requireNonNull(rotationPolicy);
    }

    @Override
    public ResearchParityReport evaluate() {
        var snapshot = snapshotPort.loadLatest();
        var narrativeResults = java.util.Arrays.stream(NarrativeTheme.values())
                .map(theme -> compareNarrative(snapshot, theme))
                .toList();
        var rotationResult = compareRotation(snapshot);
        var matchedNarratives = (int) narrativeResults.stream().filter(NarrativeParityResult::matched).count();
        var allMatched = rotationResult.matched() && matchedNarratives == narrativeResults.size();
        return new ResearchParityReport(
                snapshot.timestamp(),
                allMatched,
                matchedNarratives,
                narrativeResults.size(),
                rotationResult,
                narrativeResults
        );
    }

    private NarrativeParityResult compareNarrative(ResearchSnapshot snapshot, NarrativeTheme theme) {
        var expected = snapshot.legacyNarratives().get(theme);
        if (expected == null) {
            return new NarrativeParityResult(
                    theme,
                    false,
                    null,
                    null,
                    null,
                    null,
                    java.util.List.of("legacyBaselineMissing")
            );
        }

        var actual = narrativePolicy.evaluate(
                theme,
                new NarrativeEvidence(
                        snapshot.rawValues(),
                        snapshot.derivedValues(),
                        snapshot.assetSignals(),
                        snapshot.manualEvidence(),
                        expected.externalSignals()
                )
        );
        var differences = new ArrayList<String>();
        if (expected.stage() != actual.stage()) differences.add("stage");
        if (expected.heatScore() != actual.heatScore()) differences.add("heatScore");
        if (!expected.drivers().equals(actual.drivers())) differences.add("drivers");
        if (!expected.risks().equals(actual.risks())) differences.add("risks");
        if (!expected.proxyScores().equals(actual.proxyScores())) differences.add("proxyScores");
        if (!expected.externalSignals().equals(actual.externalSignals())) differences.add("externalSignals");
        return new NarrativeParityResult(
                theme,
                differences.isEmpty(),
                expected.stage(),
                expected.heatScore(),
                actual.stage(),
                actual.heatScore(),
                differences
        );
    }

    private RotationParityResult compareRotation(ResearchSnapshot snapshot) {
        var expected = snapshot.legacyRotationAssessment();
        var actual = rotationPolicy.inferRegime(toRotationEvidence(snapshot));
        var differences = new ArrayList<String>();
        if (expected.regime() != actual.regime()) differences.add("regime");
        if (expected.confidence() != actual.confidence()) differences.add("confidence");
        if (!expected.regimeScores().equals(actual.regimeScores())) differences.add("regimeScores");
        return new RotationParityResult(
                differences.isEmpty(),
                expected.regime(),
                actual.regime(),
                expected.confidence(),
                actual.confidence(),
                expected.regimeScores(),
                actual.regimeScores(),
                differences
        );
    }

    private static RotationMarketEvidence toRotationEvidence(ResearchSnapshot snapshot) {
        return new RotationMarketEvidence(
                snapshot.derivedValues().get("LIQUIDITY_DIRECTION"),
                snapshot.derivedValues().get("REAL_YIELD"),
                snapshot.rawValues().get("T10Y2Y"),
                snapshot.rawValues().get("WTI"),
                snapshot.rawValues().get("DXY"),
                snapshot.rawValues().get("STLFSI4"),
                snapshot.rawValues().get("BAMLH0A0HYM2"),
                snapshot.derivedValues().get("CREDIT_HY_OAS_BP"),
                enabledNullable(snapshot.derivedValues().get("OVERHEATED")),
                enabledNullable(snapshot.derivedValues().get("COPPER_GOLD_RATIO_UPTURN")),
                snapshot.macroRegime(),
                snapshot.derivedValues().get("INSTITUTIONAL_SECTOR_TECH_FLOW"),
                snapshot.derivedValues().get("INSTITUTIONAL_SECTOR_FIN_FLOW"),
                snapshot.derivedValues().get("INSTITUTIONAL_SECTOR_ENERGY_FLOW")
        );
    }

    private static Boolean enabledNullable(Double value) {
        return value == null ? null : value == 1;
    }
}
