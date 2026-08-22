package io.macrosquare.research.application.model;

import io.macrosquare.research.domain.rotation.SectorRotationItem;
import io.macrosquare.research.domain.rotation.SectorRotationView;

import java.util.LinkedHashMap;
import java.util.Map;
import java.time.LocalDate;

/**
 * Current market overlay for the structurally slower-moving research catalog.
 *
 * <p>Price momentum and macro evidence are refreshed from the native market
 * snapshot. Quality and valuation remain slower reference fundamentals, while
 * earnings revision is a dated constituent breadth loaded through a read-only
 * cross-context port.</p>
 */
public record CurrentSectorRotationAssessment(
        String calculatedAt,
        SectorRotationView rotation,
        Map<String, CurrentSectorProfile> profiles,
        int currentMomentumCoverage,
        int totalReturnCoverage,
        int universeSize,
        Map<String, LocalDate> rawObservedOn,
        Map<String, LocalDate> derivedObservedOn
) {
    public CurrentSectorRotationAssessment {
        if (calculatedAt == null || calculatedAt.isBlank()) {
            throw new IllegalArgumentException("calculatedAt is required");
        }
        if (rotation == null) throw new IllegalArgumentException("rotation is required");
        profiles = Map.copyOf(new LinkedHashMap<>(profiles == null ? Map.of() : profiles));
        rawObservedOn = Map.copyOf(new LinkedHashMap<>(rawObservedOn == null ? Map.of() : rawObservedOn));
        derivedObservedOn = Map.copyOf(new LinkedHashMap<>(
                derivedObservedOn == null ? Map.of() : derivedObservedOn));
        if (currentMomentumCoverage < 0 || totalReturnCoverage < 0
                || universeSize < currentMomentumCoverage || universeSize < totalReturnCoverage) {
            throw new IllegalArgumentException("rotation coverage is invalid");
        }
    }

    public CurrentSectorRotationAssessment(
            String calculatedAt,
            SectorRotationView rotation,
            Map<String, CurrentSectorProfile> profiles,
            int currentMomentumCoverage,
            int totalReturnCoverage,
            int universeSize
    ) {
        this(calculatedAt, rotation, profiles, currentMomentumCoverage, totalReturnCoverage,
                universeSize, Map.of(), Map.of());
    }

    public record CurrentSectorProfile(
            String key,
            String label,
            String classification,
            Double shortTermRelativeStrength,
            Double mediumTermRelativeStrength,
            Integer qualityScore,
            Integer policySupport,
            Integer structuralDemand,
            Integer supplyTightness,
            Integer marketConcentration,
            Integer appealScore,
            Integer crowdingScore,
            Integer buyScore,
            String buyLabel,
            Integer valuationScore,
            Integer earningsRevisionScore,
            CurrentRevisionBreadth currentRevisionBreadth,
            CurrentFundFlow currentFundFlow,
            CurrentPriceBreadth currentPriceBreadth,
            SectorRotationItem rotation
    ) {
        public CurrentSectorProfile {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
            if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
            if (classification == null || classification.isBlank()) {
                throw new IllegalArgumentException("classification is required");
            }
            if (rotation == null) throw new IllegalArgumentException("rotation is required");
        }
    }

    public record CurrentRevisionBreadth(
            LocalDate asOfDate,
            LocalDate oldestObservedOn,
            LocalDate latestObservedOn,
            int constituentCount,
            int coveredCount,
            int coveragePct,
            int revisedUpPct,
            int revisedDownPct,
            int score
    ) {
        public CurrentRevisionBreadth {
            if (asOfDate == null || oldestObservedOn == null || latestObservedOn == null) {
                throw new IllegalArgumentException("revision breadth dates are required");
            }
            if (constituentCount < 1 || coveredCount < 1 || coveredCount > constituentCount
                    || coveragePct < 0 || coveragePct > 100
                    || revisedUpPct < 0 || revisedUpPct > 100
                    || revisedDownPct < 0 || revisedDownPct > 100
                    || score < 0 || score > 100) {
                throw new IllegalArgumentException("revision breadth values are invalid");
            }
        }
    }

    public record CurrentFundFlow(
            LocalDate observedOn,
            double nav,
            double totalNetAssets,
            double flow1dUsd,
            double flow5dUsd,
            double flow20dUsd,
            double flow5dPct,
            double flow20dPct,
            int score
    ) {
        public CurrentFundFlow {
            if (observedOn == null || !Double.isFinite(nav) || nav <= 0
                    || !Double.isFinite(totalNetAssets) || totalNetAssets <= 0
                    || !Double.isFinite(flow1dUsd) || !Double.isFinite(flow5dUsd)
                    || !Double.isFinite(flow20dUsd) || !Double.isFinite(flow5dPct)
                    || !Double.isFinite(flow20dPct) || score < 0 || score > 100) {
                throw new IllegalArgumentException("fund flow values are invalid");
            }
        }
    }

    public record CurrentPriceBreadth(
            LocalDate asOfDate,
            LocalDate oldestObservedOn,
            LocalDate latestObservedOn,
            int constituentCount,
            int coveredCount,
            int coveragePct,
            int aboveMa20Pct,
            int aboveMa50Pct,
            int aboveMa200Pct,
            int score
    ) {
        public CurrentPriceBreadth {
            if (asOfDate == null || oldestObservedOn == null || latestObservedOn == null
                    || constituentCount < 1 || coveredCount < 1 || coveredCount > constituentCount
                    || coveragePct < 0 || coveragePct > 100 || aboveMa20Pct < 0 || aboveMa20Pct > 100
                    || aboveMa50Pct < 0 || aboveMa50Pct > 100 || aboveMa200Pct < 0 || aboveMa200Pct > 100
                    || score < 0 || score > 100) {
                throw new IllegalArgumentException("price breadth values are invalid");
            }
        }
    }
}
