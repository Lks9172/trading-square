package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.CurrentSectorRotationAssessment;
import io.macrosquare.research.application.port.in.CaptureSectorRotationSnapshotUseCase;
import io.macrosquare.research.application.port.in.EvaluateSectorRotationOutcomesUseCase;
import io.macrosquare.research.application.port.out.LoadSectorRotationPriceWindowPort;
import io.macrosquare.research.application.port.out.SectorRotationValidationRepository;
import io.macrosquare.research.domain.rotation.SectorRotationCompositeSnapshot;
import io.macrosquare.research.domain.rotation.SectorRotationPolicy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Captures one immutable live composite per completed common ETF session. */
public final class CaptureSectorRotationSnapshotService implements CaptureSectorRotationSnapshotUseCase {

    private static final List<String> MACRO_RAW_KEYS = List.of(
            "T10Y2Y", "WTI", "DXY", "STLFSI4", "BAMLH0A0HYM2",
            "DGS10", "T10YIE", "RRPONTSYD", "WDTGAL", "WRMFNS", "WRESBAL", "M2SL", "WM2NS");
    private static final int MAX_ANCHOR_AGE_DAYS = 7;
    private static final Set<String> STANDARD_SECTOR_KEYS = Set.of(
            "SECTOR_XLK", "SECTOR_XLF", "SECTOR_XLE", "SECTOR_XLV", "SECTOR_XLI",
            "SECTOR_XLY", "SECTOR_XLC", "SECTOR_XLB", "SECTOR_XLRE", "SECTOR_XLU", "SECTOR_XLP");

    private final SectorRotationValidationRepository repository;
    private final LoadSectorRotationPriceWindowPort prices;
    private final EvaluateSectorRotationOutcomesUseCase outcomes;

    public CaptureSectorRotationSnapshotService(
            SectorRotationValidationRepository repository,
            LoadSectorRotationPriceWindowPort prices,
            EvaluateSectorRotationOutcomesUseCase outcomes
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.prices = Objects.requireNonNull(prices);
        this.outcomes = Objects.requireNonNull(outcomes);
    }

    @Override
    public boolean capture(CurrentSectorRotationAssessment assessment) {
        Objects.requireNonNull(assessment);
        var rotationItems = assessment.rotation().sectors();
        if (assessment.universeSize() != 11
                || rotationItems.size() != 11
                || !rotationItems.stream().map(io.macrosquare.research.domain.rotation.SectorRotationItem::key)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()).equals(STANDARD_SECTOR_KEYS)) {
            // Partial current coverage is a valid user-facing assessment but
            // not a complete cross-sectional OOS sample. Skip it deliberately
            // instead of constructing an invalid ledger record and emitting an
            // ERROR on every refresh.
            outcomes.evaluate(48);
            return false;
        }
        var calculatedAt = Instant.parse(assessment.calculatedAt());
        var signalAsOfDate = LocalDate.ofInstant(calculatedAt, java.time.ZoneOffset.UTC);
        var priceAnchorOn = prices.latestCompletedCommonDate(calculatedAt).orElse(null);
        if (priceAnchorOn == null || ChronoUnit.DAYS.between(priceAnchorOn, signalAsOfDate) > MAX_ANCHOR_AGE_DAYS) {
            outcomes.evaluate(48);
            return false;
        }
        // Derived macro fields are stamped with their calculation date. Persist
        // the actual dated raw inputs that feed real yield and liquidity instead.
        var macroDates = dates(assessment.rawObservedOn(), MACRO_RAW_KEYS);
        var items = new ArrayList<SectorRotationCompositeSnapshot.Item>();
        var rank = 0;
        for (var item : assessment.rotation().sectors()) {
            rank++;
            var profile = assessment.profiles().get(item.key());
            if (profile == null) throw new IllegalStateException("sector profile is missing: " + item.key());
            // The common completed total-return session is the availability
            // boundary for all four momentum calculations. Their derived
            // document date is a calculation stamp and must not masquerade as
            // the underlying market observation date.
            var momentumDates = new DateRange(priceAnchorOn, priceAnchorOn);
            var revision = profile.currentRevisionBreadth();
            var flow = profile.currentFundFlow();
            var breadth = profile.currentPriceBreadth();
            items.add(new SectorRotationCompositeSnapshot.Item(
                    item.key(), rank, item.rotationScore(), item.macroFitScore(),
                    item.relativeStrengthScore(), item.fundamentalScore(), item.valuationScore(),
                    item.earningsRevisionScore(), item.flowScore(),
                    breadth == null ? null : breadth.score(), item.crowdingReliefScore(), item.state(),
                    item.rotationLabel(), item.expectedLeadershipWindow(), momentumDates.oldest(),
                    momentumDates.latest(), revision == null ? null : revision.latestObservedOn(),
                    revision == null ? null : revision.coveragePct(),
                    flow == null ? null : flow.observedOn(),
                    breadth == null ? null : breadth.latestObservedOn(),
                    breadth == null ? null : breadth.coveragePct()));
        }
        var snapshot = new SectorRotationCompositeSnapshot(
                deterministicRunId(SectorRotationPolicy.METHODOLOGY_VERSION, priceAnchorOn), calculatedAt,
                signalAsOfDate, priceAnchorOn, SectorRotationPolicy.METHODOLOGY_VERSION,
                assessment.rotation().regime(),
                assessment.rotation().confidence(), assessment.currentMomentumCoverage(),
                assessment.totalReturnCoverage(), assessment.universeSize(), macroDates.oldest(),
                macroDates.latest(), items);
        var appended = repository.append(snapshot);
        outcomes.evaluate(48);
        return appended;
    }

    private static DateRange dates(Map<String, LocalDate> values, List<String> keys) {
        return dates(values, keys, Map.of(), List.of());
    }

    private static DateRange dates(
            Map<String, LocalDate> first,
            List<String> firstKeys,
            Map<String, LocalDate> second,
            List<String> secondKeys
    ) {
        var values = java.util.stream.Stream.concat(
                        firstKeys.stream().map(first::get), secondKeys.stream().map(second::get))
                .filter(Objects::nonNull).sorted().toList();
        return values.isEmpty() ? new DateRange(null, null)
                : new DateRange(values.getFirst(), values.getLast());
    }

    private static UUID deterministicRunId(String methodologyVersion, LocalDate priceAnchorOn) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(
                    (methodologyVersion + ":" + priceAnchorOn).getBytes(StandardCharsets.UTF_8));
            var buffer = ByteBuffer.wrap(digest);
            var high = buffer.getLong();
            var low = buffer.getLong();
            high = (high & 0xffffffffffff0fffL) | 0x0000000000004000L;
            low = (low & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new UUID(high, low);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private record DateRange(LocalDate oldest, LocalDate latest) {}
}
