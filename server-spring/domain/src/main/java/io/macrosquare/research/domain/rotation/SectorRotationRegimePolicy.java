package io.macrosquare.research.domain.rotation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.macrosquare.research.domain.rotation.RotationMath.*;

/** Infers the macro phase and sector fit independently from ranking/presentation concerns. */
final class SectorRotationRegimePolicy {

    RotationRegimeAssessment inferRegime(RotationMarketEvidence market) {
        var scores = computeRegimeScores(market);
        var ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(Map.Entry.<SectorRotationRegime, Integer>comparingByValue().reversed());
        var selected = ranked.getFirst();
        var secondScore = ranked.size() > 1 ? ranked.get(1).getValue() : 0;
        var rawSeparation = (int) clamp(selected.getValue() - secondScore + 50, 5, 95);
        // A wide score gap produced from sparse inputs must not look as reliable
        // as the same gap produced from all seven continuous observations and
        // both event flags.
        var evidenceCoveragePct = macroCoverage(market) * 100 / 9;
        var confidence = Math.min(rawSeparation, Math.min(95, evidenceCoveragePct));
        return new RotationRegimeAssessment(selected.getKey(), confidence, scores);
    }

    Map<SectorRotationRegime, Integer> computeRegimeScores(RotationMarketEvidence market) {
        var liquidityBull = positiveScore(market.liquidityDirection(), -1, 2);
        var curveSteep = positiveScore(market.yieldCurve10y2y(), -0.15, 0.45);
        var curveFlat = market.yieldCurve10y2y() == null
                ? 50
                : 100 - Math.abs(clamp(market.yieldCurve10y2y() * 180, -100, 100));
        var lowRealYield = negativeScore(market.realYield(), 1.2, 2.5);
        var highRealYield = positiveScore(market.realYield(), 1.6, 2.8);
        var benignDollar = negativeScore(market.dollarIndex(), 101.5, 106);
        var inflationHeat = positiveScore(market.wti(), 72, 88);
        var moderateYield = market.realYield() == null
                ? 50
                : 100 - Math.min(100, Math.abs(market.realYield() - 1.9) * 75);
        var financialEase = clamp(
                (market.financialStressIndex() == null
                        ? 50
                        : negativeScore(market.financialStressIndex(), -0.35, 1.1)) * 0.45
                        + (market.highYieldOas() == null
                        ? 50
                        : negativeScore(market.highYieldOas(), 3.3, 6.5)) * 0.55,
                0,
                100
        );
        var financialStress = 100 - financialEase;
        // Event flags are three-state evidence. Missing cannot mean a confirmed
        // "not overheated" observation or a confirmed absence of an upturn.
        var overheated = flagScore(market.overheated());
        var copperGoldUpturn = flagScore(market.copperGoldRatioUpturn());
        // macroRegime is a presentation summary derived from many of the same
        // continuous observations below. Feeding that label back as a 0/100
        // feature duplicated the inputs and created discontinuous score jumps
        // at the upstream label boundary. The rotation model therefore uses
        // only point-in-time continuous/event evidence here.
        var early = clamp((
                liquidityBull * 0.24
                        + curveSteep * 0.22
                        + lowRealYield * 0.18
                        + benignDollar * 0.12
                        + (100 - inflationHeat) * 0.12
                        + financialEase * 0.08
                        + (100 - overheated) * 0.06) / 1.02,
                0,
                100
        );
        var mid = clamp((
                liquidityBull * 0.22
                        + moderateYield * 0.2
                        + benignDollar * 0.14
                        + curveFlat * 0.14
                        + (100 - overheated) * 0.1
                        + (100 - inflationHeat) * 0.06
                        + financialEase * 0.06) / 0.92,
                0,
                100
        );
        var lateInflation = clamp((
                inflationHeat * 0.28
                        + highRealYield * 0.18
                        + curveFlat * 0.12
                        + overheated * 0.14
                        + financialStress * 0.06
                        + (100 - liquidityBull) * 0.1) / 0.88,
                0,
                100
        );
        var defensive = clamp((
                highRealYield * 0.18
                        + (100 - liquidityBull) * 0.16
                        + financialStress * 0.16
                        + (market.yieldCurve10y2y() == null
                        ? 50
                        : positiveScore(-market.yieldCurve10y2y(), -0.15, 0.55)) * 0.14) / 0.64,
                0,
                100
        );
        var reAcceleration = clamp((
                liquidityBull * 0.22
                        + copperGoldUpturn * 0.2
                        + curveSteep * 0.16
                        + benignDollar * 0.12
                        + moderateYield * 0.12
                        + financialEase * 0.14
                        + (100 - inflationHeat) * 0.08) / 1.04,
                0,
                100
        );

        var scores = new LinkedHashMap<SectorRotationRegime, Integer>();
        scores.put(SectorRotationRegime.EARLY_CYCLICAL, rounded(early));
        scores.put(SectorRotationRegime.MID_GROWTH, rounded(mid));
        scores.put(SectorRotationRegime.LATE_INFLATION, rounded(lateInflation));
        scores.put(SectorRotationRegime.DEFENSIVE, rounded(defensive));
        scores.put(SectorRotationRegime.RE_ACCELERATION, rounded(reAcceleration));
        return scores;
    }

    private static int macroCoverage(RotationMarketEvidence market) {
        return (int) java.util.stream.Stream.of(
                market.liquidityDirection(), market.realYield(), market.yieldCurve10y2y(),
                market.wti(), market.dollarIndex(), market.financialStressIndex(),
                market.highYieldOas(), market.overheated(),
                market.copperGoldRatioUpturn()).filter(java.util.Objects::nonNull).count();
    }

    private static int flagScore(Boolean value) {
        return value == null ? 50 : value ? 100 : 0;
    }

    int computeMacroFitScore(
            String sectorKey,
            SectorRotationRegime regime,
            RotationMarketEvidence market
    ) {
        var rateSensitiveRealEstate = (market.realYield() != null && market.realYield() <= 2.15)
                || (market.yieldCurve10y2y() != null && market.yieldCurve10y2y() >= 0.05);
        return switch (regime) {
            case EARLY_CYCLICAL -> switch (sectorKey) {
                case "SECTOR_XLY" -> 88;
                case "SECTOR_XLF" -> 85;
                case "SECTOR_XLRE" -> rateSensitiveRealEstate ? 82 : 58;
                case "SECTOR_XLI" -> 82;
                case "SECTOR_XLK" -> 80;
                case "SECTOR_XLB" -> 76;
                case "SECTOR_SOXX" -> 72;
                case "SECTOR_SMH" -> 71;
                case "SECTOR_XLC" -> 68;
                case "SECTOR_IGF" -> 77;
                case "SECTOR_GRID" -> 64;
                case "SECTOR_XLE" -> 56;
                case "SECTOR_XLV" -> 42;
                case "SECTOR_XLU" -> 38;
                case "SECTOR_XLP" -> 35;
                case "SECTOR_ITA" -> 60;
                default -> 55;
            };
            case MID_GROWTH -> switch (sectorKey) {
                case "SECTOR_XLK" -> 87;
                case "SECTOR_SOXX" -> 86;
                case "SECTOR_SMH" -> 85;
                case "SECTOR_XLC" -> 79;
                case "SECTOR_XLI" -> 69;
                case "SECTOR_XLF" -> 66;
                case "SECTOR_XLY" -> 65;
                case "SECTOR_XLRE" -> rateSensitiveRealEstate ? 64 : 52;
                case "SECTOR_IGF" -> 64;
                case "SECTOR_GRID" -> 70;
                case "SECTOR_XLB" -> 51;
                case "SECTOR_XLE" -> 48;
                case "SECTOR_XLV" -> 45;
                case "SECTOR_XLU" -> 40;
                case "SECTOR_XLP" -> 39;
                case "SECTOR_ITA" -> 61;
                default -> 55;
            };
            case LATE_INFLATION -> switch (sectorKey) {
                case "SECTOR_XLE" -> 89;
                case "SECTOR_XLP" -> 77;
                case "SECTOR_XLU" -> 76;
                case "SECTOR_XLV" -> 72;
                case "SECTOR_XLB" -> 69;
                case "SECTOR_ITA" -> 67;
                case "SECTOR_XLF" -> 58;
                case "SECTOR_XLI" -> 57;
                case "SECTOR_IGF" -> 54;
                case "SECTOR_XLRE" -> rateSensitiveRealEstate ? 46 : 38;
                case "SECTOR_GRID" -> 60;
                case "SECTOR_XLK" -> 40;
                case "SECTOR_SOXX" -> 38;
                case "SECTOR_SMH" -> 37;
                case "SECTOR_XLC" -> 42;
                case "SECTOR_XLY" -> 35;
                default -> 55;
            };
            case DEFENSIVE -> switch (sectorKey) {
                case "SECTOR_XLP" -> 89;
                case "SECTOR_XLU" -> 87;
                case "SECTOR_XLV" -> 83;
                case "SECTOR_XLRE" -> rateSensitiveRealEstate ? 72 : 60;
                case "SECTOR_ITA" -> 66;
                case "SECTOR_XLE" -> 54;
                case "SECTOR_GRID" -> 58;
                case "SECTOR_XLC" -> 46;
                case "SECTOR_XLK" -> 42;
                case "SECTOR_SOXX" -> 39;
                case "SECTOR_SMH" -> 38;
                case "SECTOR_XLF" -> 34;
                case "SECTOR_XLI" -> 36;
                case "SECTOR_XLY" -> 33;
                case "SECTOR_XLB" -> 34;
                case "SECTOR_IGF" -> 41;
                default -> 55;
            };
            case RE_ACCELERATION -> switch (sectorKey) {
                case "SECTOR_XLI" -> 85;
                case "SECTOR_XLF" -> 83;
                case "SECTOR_XLB" -> 81;
                case "SECTOR_IGF" -> 80;
                case "SECTOR_XLK" -> 75;
                case "SECTOR_XLY" -> 73;
                case "SECTOR_XLRE" -> rateSensitiveRealEstate ? 69 : 55;
                case "SECTOR_GRID" -> 69;
                case "SECTOR_SOXX" -> 72;
                case "SECTOR_SMH" -> 71;
                case "SECTOR_XLC" -> 67;
                case "SECTOR_XLE" -> 60;
                case "SECTOR_XLV" -> 46;
                case "SECTOR_XLU" -> 44;
                case "SECTOR_XLP" -> 43;
                case "SECTOR_ITA" -> 64;
                default -> 55;
            };
        };
    }
}
