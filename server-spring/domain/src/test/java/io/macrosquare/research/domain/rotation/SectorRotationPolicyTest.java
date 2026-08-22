package io.macrosquare.research.domain.rotation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectorRotationPolicyTest {

    private final SectorRotationPolicy policy = new SectorRotationPolicy();

    @Test
    void separatesObservedPriceLeadershipFromForwardMacroRotation() {
        var view = policy.evaluate(new SectorRotationInput(
                growthMarket(),
                growthSectors(),
                Map.of(
                        "ai-power", 85,
                        "energy-supply", 70,
                        "defense-rearm", 76
                )
        ));

        assertEquals(SectorRotationRegime.EARLY_CYCLICAL, view.regime());
        assertTrue(view.confidence() >= 50);
        assertTrue(view.regimeScores().values().stream().allMatch(score -> score >= 0 && score <= 100));
        var current = view.currentLeaders().stream()
                .map(SectorRotationOutlookBucket::sectorKey).toList();
        assertTrue(current.contains("SECTOR_XLK"));
        assertTrue(current.contains("SECTOR_XLI"));
        assertTrue(current.contains("SECTOR_XLE"));
        assertTrue(view.fadingCandidates().stream()
                .anyMatch(value -> value.sectorKey().equals("SECTOR_XLE")));
        assertEquals(SectorRotationState.IMPROVING, view.sectors().stream()
                .filter(value -> value.key().equals("SECTOR_XLF"))
                .findFirst().orElseThrow().state());
        assertTrue(view.nextCandidates().stream().allMatch(value ->
                value.expectedLeadershipWindow() == SectorRotationHorizon.ONE_TO_THREE_MONTHS));
        assertTrue(view.secondaryCandidates().stream().allMatch(value ->
                value.expectedLeadershipWindow() == SectorRotationHorizon.THREE_TO_SIX_MONTHS));
        assertFalse(view.favoredNext().contains("기술"));
        assertTrue(view.summary().contains("현재 가격 상대강도 주도"));
        assertTrue(view.summary().contains("후보"));

        var technology = view.sectors().stream()
                .filter(value -> value.key().equals("SECTOR_XLK")).findFirst().orElseThrow();
        assertEquals(SectorRotationState.LEADING, technology.state());
        var energy = view.sectors().stream()
                .filter(value -> value.key().equals("SECTOR_XLE")).findFirst().orElseThrow();
        assertEquals(SectorRotationState.WEAKENING, energy.state());
    }

    @Test
    void usesTheConfiguredEnergySupplyNarrativeKey() {
        var base = policy.evaluate(new SectorRotationInput(growthMarket(), growthSectors(), Map.of()));
        var configuredLookup = policy.evaluate(new SectorRotationInput(
                growthMarket(), growthSectors(), Map.of("energy-supply", 100)));

        var baseEnergy = base.sectors().stream().filter(item -> item.key().equals("SECTOR_XLE")).findFirst().orElseThrow();
        var adjustedEnergy = configuredLookup.sectors().stream().filter(item -> item.key().equals("SECTOR_XLE")).findFirst().orElseThrow();
        assertEquals(69, baseEnergy.fundamentalScore());
        assertEquals(72, adjustedEnergy.fundamentalScore());
    }

    @Test
    void undatedEarningsRevisionReferenceDoesNotChangeTheCurrentRotationScore() {
        var lowRevision = new SectorRotationEvidence(
                "SECTOR_XLI", "산업재", SectorClassification.CYCLICAL,
                2.0, 1.0, 70, true, 75, 68, 60, 10, 40, 70);
        var highRevision = new SectorRotationEvidence(
                "SECTOR_XLI", "산업재", SectorClassification.CYCLICAL,
                2.0, 1.0, 70, true, 75, 68, 60, 90, 40, 70);

        var low = policy.evaluate(new SectorRotationInput(growthMarket(), List.of(lowRevision), Map.of()))
                .sectors().getFirst();
        var high = policy.evaluate(new SectorRotationInput(growthMarket(), List.of(highRevision), Map.of()))
                .sectors().getFirst();

        assertEquals(low.fundamentalScore(), high.fundamentalScore());
        assertEquals(low.rotationScore(), high.rotationScore());
        assertEquals(10, low.earningsRevisionScore());
        assertEquals(90, high.earningsRevisionScore());
    }

    @Test
    void crowdingBelowTheDocumentedSeventyThresholdDoesNotCreateAWeakeningLabel() {
        var sector = new SectorRotationEvidence(
                "SECTOR_XLK", "기술", SectorClassification.STRUCTURAL,
                8.0, 2.0, 80, true, 75, 70, 60, 65, 69, 72);

        var item = policy.evaluate(new SectorRotationInput(growthMarket(), List.of(sector), Map.of()))
                .sectors().getFirst();

        assertEquals(SectorRotationState.LEADING, item.state());
        assertEquals(SectorRotationLabel.LEADER, item.rotationLabel());
    }

    @Test
    void aCloseMacroRegimeRaceCannotEraseAnObservedPriceLeader() {
        var market = new RotationMarketEvidence(
                -1.0, 2.44, .46, 78.18, 99.6, -.5063, 2.71, 271.0,
                false, false, MacroRegime.BOND_VIGILANTE, null, null, null);
        var sectors = List.of(
                sector("SECTOR_XLK", "기술", SectorClassification.STRUCTURAL,
                        11.5, -1.38, 62, 53, 61, 71, 18, 64),
                sector("SECTOR_SOXX", "반도체", SectorClassification.STRUCTURAL,
                        28.65, -9.18, 75, 70, 48, 80, 30, 72),
                sector("SECTOR_XLV", "헬스케어", SectorClassification.DEFENSIVE,
                        1.19, -.65, 50, 57, 63, 73, 29, 64)
        );

        var view = policy.evaluate(new SectorRotationInput(market, sectors, Map.of()));

        var technology = view.sectors().stream()
                .filter(value -> value.key().equals("SECTOR_XLK")).findFirst().orElseThrow();
        var semiconductors = view.sectors().stream()
                .filter(value -> value.key().equals("SECTOR_SOXX")).findFirst().orElseThrow();
        assertEquals(SectorRotationState.LEADING, technology.state());
        assertEquals(SectorRotationState.WEAKENING, semiconductors.state());
        assertTrue(view.currentLeaders().stream()
                .anyMatch(value -> value.sectorKey().equals("SECTOR_XLK")));
        assertTrue(view.currentLeaders().stream()
                .anyMatch(value -> value.sectorKey().equals("SECTOR_SOXX")));
        assertFalse(view.currentLeaders().stream()
                .anyMatch(value -> value.sectorKey().equals("SECTOR_XLV")));
    }

    @Test
    void missingMacroInputsStayNeutralAndCannotManufactureRegimeConfidence() {
        var market = new RotationMarketEvidence(
                null, null, null, null, null, null, null, null,
                null, null, MacroRegime.RISK_ON, null, null, null);

        var assessment = policy.inferRegime(market);

        assertEquals(0, assessment.confidence());
        assertTrue(assessment.regimeScores().values().stream()
                .allMatch(score -> score >= 0 && score <= 100));
    }

    @Test
    void missingAbsoluteTrendCannotPromoteAHighRelativeRankToCurrentLeader() {
        var sector = new SectorRotationEvidence(
                "SECTOR_XLK", "기술", SectorClassification.STRUCTURAL,
                12.0, 4.0, 95, null,
                80, 75, 65, 70, 30, 76);

        var view = policy.evaluate(new SectorRotationInput(growthMarket(), List.of(sector), Map.of()));

        assertFalse(view.currentLeaders().stream()
                .anyMatch(value -> value.sectorKey().equals("SECTOR_XLK")));
    }

    @Test
    void missingRelativeStrengthCannotBeReplacedBySyntheticZeroForStatePromotion() {
        var sector = new SectorRotationEvidence(
                "SECTOR_XLK", "기술", SectorClassification.STRUCTURAL,
                null, null, 95, true,
                90, 90, 90, 90, 10, 90);

        var view = policy.evaluate(new SectorRotationInput(growthMarket(), List.of(sector), Map.of()));
        var item = view.sectors().getFirst();

        assertEquals(SectorRotationState.LAGGING, item.state());
        assertTrue(view.currentLeaders().isEmpty());
        assertTrue(view.nextCandidates().isEmpty());
        assertTrue(view.secondaryCandidates().isEmpty());
        assertTrue(item.reasons().stream().anyMatch(reason -> reason.contains("상대강도 근거가 없어")));
    }

    @Test
    void missingMacroEventFlagsCapConfidenceAndStayNeutral() {
        var observed = growthMarket();
        var missingFlags = new RotationMarketEvidence(
                observed.liquidityDirection(), observed.realYield(), observed.yieldCurve10y2y(),
                observed.wti(), observed.dollarIndex(), observed.financialStressIndex(),
                observed.highYieldOas(), observed.highYieldOasBasisPoints(),
                null, null, observed.macroRegime(),
                observed.institutionalTechFlow(), observed.institutionalFinancialFlow(),
                observed.institutionalEnergyFlow());

        var assessment = policy.inferRegime(missingFlags);

        assertTrue(assessment.confidence() <= 77,
                "seven of nine current macro observations cap the separation score");
        assertTrue(assessment.regimeScores().values().stream().allMatch(score -> score >= 0 && score <= 100));
    }

    @Test
    void anUpstreamMacroLabelCannotCreateACategoricalRotationJump() {
        var observed = growthMarket();
        var riskOn = withMacroRegime(observed, MacroRegime.RISK_ON);
        var panic = withMacroRegime(observed, MacroRegime.PANIC_BUT_OK);

        assertEquals(policy.computeRegimeScores(riskOn), policy.computeRegimeScores(panic));
        assertEquals(
                policy.evaluate(new SectorRotationInput(riskOn, growthSectors(), Map.of())).sectors(),
                policy.evaluate(new SectorRotationInput(panic, growthSectors(), Map.of())).sectors());
    }

    @Test
    void financialConditionsAreNotAddedAgainAsASeparateSectorScoreAxis() {
        var base = growthMarket();
        var sameRotationInputsButUnusedBasisPointAliasChanged = new RotationMarketEvidence(
                base.liquidityDirection(), base.realYield(), base.yieldCurve10y2y(), base.wti(),
                base.dollarIndex(), base.financialStressIndex(), base.highYieldOas(), 900.0,
                base.overheated(), base.copperGoldRatioUpturn(), base.macroRegime(),
                base.institutionalTechFlow(), base.institutionalFinancialFlow(),
                base.institutionalEnergyFlow());

        assertEquals(
                policy.evaluate(new SectorRotationInput(base, growthSectors(), Map.of())).sectors(),
                policy.evaluate(new SectorRotationInput(
                        sameRotationInputsButUnusedBasisPointAliasChanged, growthSectors(), Map.of())).sectors());
    }

    private static RotationMarketEvidence withMacroRegime(
            RotationMarketEvidence value,
            MacroRegime macroRegime
    ) {
        return new RotationMarketEvidence(
                value.liquidityDirection(), value.realYield(), value.yieldCurve10y2y(), value.wti(),
                value.dollarIndex(), value.financialStressIndex(), value.highYieldOas(),
                value.highYieldOasBasisPoints(), value.overheated(), value.copperGoldRatioUpturn(),
                macroRegime, value.institutionalTechFlow(), value.institutionalFinancialFlow(),
                value.institutionalEnergyFlow());
    }

    private static RotationMarketEvidence growthMarket() {
        return new RotationMarketEvidence(
                2.0,
                1.2,
                0.6,
                68.0,
                100.0,
                -0.2,
                3.4,
                320.0,
                false,
                true,
                MacroRegime.RISK_ON,
                1.4,
                0.8,
                -0.4
        );
    }

    private static List<SectorRotationEvidence> growthSectors() {
        return List.of(
                sector("SECTOR_XLK", "기술", SectorClassification.STRUCTURAL, 8.0, 5.0, 84, 79, 62, 82, 52, 76),
                sector("SECTOR_XLI", "산업재", SectorClassification.CYCLICAL, 4.5, 4.0, 78, 74, 68, 71, 42, 73),
                sector("SECTOR_XLF", "금융", SectorClassification.CYCLICAL, 2.0, 2.0, 74, 70, 72, 68, 35, 71),
                sector("SECTOR_XLE", "에너지", SectorClassification.CYCLICAL, 8.0, -5.0, 70, 62, 75, 54, 82, 55),
                sector("SECTOR_XLU", "유틸리티", SectorClassification.DEFENSIVE, -4.0, -5.0, 68, 52, 55, 50, 30, 58),
                sector("SECTOR_XLRE", "부동산", SectorClassification.DEFENSIVE, -1.0, 1.0, 66, 64, 74, 57, 28, 68)
        );
    }

    private static SectorRotationEvidence sector(
            String key,
            String label,
            SectorClassification classification,
            double mediumTerm,
            double shortTerm,
            int quality,
            int appeal,
            int valuation,
            int earningsRevision,
            int crowding,
            int buy
    ) {
        return new SectorRotationEvidence(
                key,
                label,
                classification,
                mediumTerm,
                shortTerm,
                Math.max(0, Math.min(100, (int) Math.round(50 + mediumTerm * 4))),
                mediumTerm >= 0,
                quality,
                appeal,
                valuation,
                earningsRevision,
                crowding,
                buy
        );
    }
}
