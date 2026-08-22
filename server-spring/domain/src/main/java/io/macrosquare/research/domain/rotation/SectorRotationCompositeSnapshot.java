package io.macrosquare.research.domain.rotation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable point-in-time output of the live standard-sector composite. */
public record SectorRotationCompositeSnapshot(
        UUID runId,
        Instant calculatedAt,
        LocalDate asOfDate,
        LocalDate priceAnchorOn,
        String methodologyVersion,
        SectorRotationRegime regime,
        int regimeConfidence,
        int currentMomentumCoverage,
        int totalReturnCoverage,
        int universeSize,
        LocalDate oldestMacroObservedOn,
        LocalDate latestMacroObservedOn,
        List<Item> items
) {
    public SectorRotationCompositeSnapshot {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(calculatedAt, "calculatedAt");
        Objects.requireNonNull(asOfDate, "asOfDate");
        Objects.requireNonNull(priceAnchorOn, "priceAnchorOn");
        if (!LocalDate.ofInstant(calculatedAt, java.time.ZoneOffset.UTC).equals(asOfDate)) {
            throw new IllegalArgumentException("asOfDate must be the UTC signal calculation date");
        }
        if (priceAnchorOn.isAfter(asOfDate)) throw new IllegalArgumentException("priceAnchorOn is in the future");
        if (methodologyVersion == null || methodologyVersion.isBlank()) {
            throw new IllegalArgumentException("methodologyVersion is required");
        }
        Objects.requireNonNull(regime, "regime");
        if (regimeConfidence < 0 || regimeConfidence > 100
                || universeSize != 11
                || currentMomentumCoverage < 0 || currentMomentumCoverage > universeSize
                || totalReturnCoverage < 0 || totalReturnCoverage > universeSize) {
            throw new IllegalArgumentException("snapshot coverage is invalid");
        }
        if ((oldestMacroObservedOn == null) != (latestMacroObservedOn == null)
                || oldestMacroObservedOn != null && (oldestMacroObservedOn.isAfter(latestMacroObservedOn)
                || latestMacroObservedOn.isAfter(asOfDate))) {
            throw new IllegalArgumentException("macro source dates are invalid");
        }
        items = List.copyOf(items == null ? List.of() : items);
        if (items.size() != universeSize
                || items.stream().map(Item::sectorKey).distinct().count() != universeSize) {
            throw new IllegalArgumentException("snapshot must contain 11 unique standard sectors");
        }
        if (items.stream().flatMap(Item::evidenceDates).anyMatch(date -> date.isAfter(asOfDate))) {
            throw new IllegalArgumentException("item evidence date is after the signal date");
        }
    }

    public record Item(
            String sectorKey,
            int rank,
            int rotationScore,
            int macroFitScore,
            int relativeStrengthScore,
            int fundamentalScore,
            Integer valuationScore,
            Integer earningsRevisionScore,
            Integer fundFlowScore,
            Integer priceBreadthScore,
            int crowdingReliefScore,
            SectorRotationState state,
            SectorRotationLabel rotationLabel,
            SectorRotationHorizon expectedLeadershipWindow,
            LocalDate oldestMomentumObservedOn,
            LocalDate latestMomentumObservedOn,
            LocalDate revisionObservedOn,
            Integer revisionCoveragePct,
            LocalDate fundFlowObservedOn,
            LocalDate priceBreadthObservedOn,
            Integer priceBreadthCoveragePct
    ) {
        private static final Set<String> STANDARD_KEYS = Set.of(
                "SECTOR_XLK", "SECTOR_XLF", "SECTOR_XLE", "SECTOR_XLV", "SECTOR_XLI",
                "SECTOR_XLY", "SECTOR_XLC", "SECTOR_XLB", "SECTOR_XLRE", "SECTOR_XLU", "SECTOR_XLP");

        public Item {
            if (!STANDARD_KEYS.contains(sectorKey) || rank < 1 || rank > 11) {
                throw new IllegalArgumentException("sector identity/rank is invalid");
            }
            requireScore(rotationScore, "rotationScore");
            requireScore(macroFitScore, "macroFitScore");
            requireScore(relativeStrengthScore, "relativeStrengthScore");
            requireScore(fundamentalScore, "fundamentalScore");
            requireScore(valuationScore, "valuationScore");
            requireScore(earningsRevisionScore, "earningsRevisionScore");
            requireScore(fundFlowScore, "fundFlowScore");
            requireScore(priceBreadthScore, "priceBreadthScore");
            requireScore(crowdingReliefScore, "crowdingReliefScore");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(rotationLabel, "rotationLabel");
            Objects.requireNonNull(expectedLeadershipWindow, "expectedLeadershipWindow");
            datePair(oldestMomentumObservedOn, latestMomentumObservedOn, "momentum");
            paired(revisionObservedOn, revisionCoveragePct, "revision");
            paired(priceBreadthObservedOn, priceBreadthCoveragePct, "price breadth");
            if (fundFlowScore == null != (fundFlowObservedOn == null)) {
                throw new IllegalArgumentException("fund flow score/date must be paired");
            }
        }

        private static void paired(LocalDate date, Integer coverage, String field) {
            if ((date == null) != (coverage == null) || coverage != null && (coverage < 0 || coverage > 100)) {
                throw new IllegalArgumentException(field + " date/coverage is invalid");
            }
        }

        private static void datePair(LocalDate oldest, LocalDate latest, String field) {
            if ((oldest == null) != (latest == null) || oldest != null && oldest.isAfter(latest)) {
                throw new IllegalArgumentException(field + " source dates are invalid");
            }
        }

        private static void requireScore(Integer value, String field) {
            if (value != null && (value < 0 || value > 100)) {
                throw new IllegalArgumentException(field + " is invalid");
            }
        }

        private java.util.stream.Stream<LocalDate> evidenceDates() {
            return java.util.stream.Stream.of(
                    oldestMomentumObservedOn, latestMomentumObservedOn, revisionObservedOn,
                    fundFlowObservedOn, priceBreadthObservedOn).filter(Objects::nonNull);
        }
    }
}
