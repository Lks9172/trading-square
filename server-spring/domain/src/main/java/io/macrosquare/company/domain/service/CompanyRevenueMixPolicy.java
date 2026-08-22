package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyRevenueMixAnalysis;
import io.macrosquare.company.domain.model.CompanyRevenueMixBreakdown;
import io.macrosquare.company.domain.model.CompanyRevenueMixDimension;
import io.macrosquare.company.domain.model.CompanyRevenueMixEntry;
import io.macrosquare.company.domain.model.CompanyRevenueMixEvidence;
import io.macrosquare.company.domain.model.CompanyRevenueMixFact;
import io.macrosquare.company.domain.model.CompanyRevenueTotal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Selects additive revenue dimensions and converts their actual values into a
 * validated percentage mix.
 *
 * <p>Filings frequently expose both parent totals and detailed children on the
 * same product axis. The policy therefore finds the most detailed subset whose
 * sum matches consolidated revenue instead of blindly normalizing every tagged
 * value. This prevents common 180-200% double-counting failures.</p>
 */
public final class CompanyRevenueMixPolicy {

    private static final BigDecimal MIN_COVERAGE = new BigDecimal("0.80");
    private static final BigDecimal MAX_COVERAGE = new BigDecimal("1.20");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.0");
    private static final int MAX_SUBSET_FACTS = 18;
    private static final Pattern PURE_GEO_LABEL = Pattern.compile(
            "(?i)^(?:"
                    + "u\\.?s\\.?a?\\.?|united states(?: of america)?|canada|mexico|"
                    + "germany|france|italy|spain|united kingdom|u\\.?k\\.?|"
                    + "china|greater china|japan|(?:south )?korea|taiwan|"
                    + "north america|south america|latin america|americas?|"
                    + "europe|emea|apac|asia(?: pacific)?|"
                    + "middle east|africa|europe middle east(?: and)? africa|"
                    + "international|foreign|other countries|"
                    + "rest of (?:world|asia|asia pacific|europe|americas?)"
                    + ")$"
    );
    private static final Pattern NON_OPERATING = Pattern.compile(
            "(?i).*(elimination|intersegment|inter-segment|corporate non.segment|"
                    + "consolidated|reconciliation|unallocated).*"
    );

    public CompanyRevenueMixAnalysis evaluate(List<CompanyRevenueMixEvidence> evidence) {
        var documents = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        var facts = documents.stream().flatMap(item -> item.facts().stream()).count();
        var candidates = candidates(documents);
        return new CompanyRevenueMixAnalysis(
                best(candidates, candidate -> candidate.category() == CompanyRevenueMixBreakdown.Category.SEGMENT),
                best(candidates, candidate -> candidate.category() == CompanyRevenueMixBreakdown.Category.GEOGRAPHY),
                documents.size(),
                Math.toIntExact(facts)
        );
    }

    private static List<Candidate> candidates(List<CompanyRevenueMixEvidence> evidence) {
        var grouped = new LinkedHashMap<GroupKey, List<CompanyRevenueMixFact>>();
        var totals = new ArrayList<SourcedTotal>();
        for (var document : evidence) {
            document.consolidatedRevenue().forEach(total -> totals.add(new SourcedTotal(document.source(), total)));
            for (var fact : document.facts()) {
                var key = new GroupKey(
                        document.source(), fact.dimension(), fact.dimensionName(), fact.unit(),
                        fact.periodStart(), fact.periodEnd()
                );
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fact);
            }
        }

        var result = new ArrayList<Candidate>();
        for (var entry : grouped.entrySet()) {
            var key = entry.getKey();
            var deduplicated = deduplicate(entry.getValue());
            if (deduplicated.size() < 2) continue;
            var matchingTotals = totals.stream()
                    .filter(total -> total.source().equals(key.source()))
                    .map(SourcedTotal::total)
                    .filter(total -> total.unit().equalsIgnoreCase(key.unit()))
                    .filter(total -> total.periodStart().equals(key.periodStart()))
                    .filter(total -> total.periodEnd().equals(key.periodEnd()))
                    .distinct()
                    .toList();
            Selection bestSelection = null;
            CompanyRevenueTotal bestTotal = null;
            for (var total : matchingTotals) {
                var selected = selectAdditiveFacts(deduplicated, total.value());
                if (selected == null || !withinCoverage(selected.coverage())) continue;
                if (bestSelection == null || compareSelection(selected, bestSelection) < 0) {
                    bestSelection = selected;
                    bestTotal = total;
                }
            }
            if (bestSelection == null || bestTotal == null) continue;

            var category = category(key.dimension(), bestSelection.facts());
            var breakdown = breakdown(category, key, bestTotal, bestSelection);
            result.add(new Candidate(
                    breakdown,
                    coverageError(bestSelection.coverage()),
                    dimensionPriority(category, key.dimension()),
                    periodPriority(key)
            ));
        }
        return List.copyOf(result);
    }

    private static List<CompanyRevenueMixFact> deduplicate(List<CompanyRevenueMixFact> source) {
        var byLabel = new LinkedHashMap<String, CompanyRevenueMixFact>();
        source.stream()
                .filter(fact -> !NON_OPERATING.matcher(fact.label()).matches())
                .forEach(fact -> byLabel.merge(
                        normalizeLabel(fact.label()),
                        fact,
                        (left, right) -> left.value().compareTo(right.value()) >= 0 ? left : right
                ));
        return byLabel.values().stream()
                .sorted(Comparator.comparing(CompanyRevenueMixFact::value).reversed())
                .limit(MAX_SUBSET_FACTS)
                .toList();
    }

    private static Selection selectAdditiveFacts(
            List<CompanyRevenueMixFact> facts,
            BigDecimal consolidatedTotal
    ) {
        if (facts.size() < 2 || consolidatedTotal.signum() <= 0) return null;
        Selection best = null;
        var combinations = 1L << facts.size();
        for (long mask = 1; mask < combinations; mask++) {
            if (Long.bitCount(mask) < 2) continue;
            var chosen = new ArrayList<CompanyRevenueMixFact>();
            var sum = BigDecimal.ZERO;
            for (var index = 0; index < facts.size(); index++) {
                if ((mask & (1L << index)) == 0) continue;
                var fact = facts.get(index);
                chosen.add(fact);
                sum = sum.add(fact.value());
            }
            var coverage = sum.divide(consolidatedTotal, 8, RoundingMode.HALF_UP);
            var candidate = new Selection(List.copyOf(chosen), sum, coverage, coverageError(coverage));
            if (best == null || compareSelection(candidate, best) < 0) best = candidate;
        }
        return best;
    }

    /** Lower comparison value means a more complete and more detailed additive set. */
    private static int compareSelection(Selection left, Selection right) {
        var byError = left.error().compareTo(right.error());
        if (byError != 0) return byError;
        var byDetail = Integer.compare(right.facts().size(), left.facts().size());
        if (byDetail != 0) return byDetail;
        return right.selectedTotal().compareTo(left.selectedTotal());
    }

    private static CompanyRevenueMixBreakdown breakdown(
            CompanyRevenueMixBreakdown.Category category,
            GroupKey key,
            CompanyRevenueTotal total,
            Selection selection
    ) {
        var ordered = selection.facts().stream()
                .sorted(Comparator.comparing(CompanyRevenueMixFact::value).reversed())
                .toList();
        var entries = new ArrayList<CompanyRevenueMixEntry>();
        var roundedSum = BigDecimal.ZERO;
        for (var fact : ordered) {
            var percent = fact.value()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(selection.selectedTotal(), 1, RoundingMode.HALF_UP);
            entries.add(new CompanyRevenueMixEntry(fact.label(), fact.value(), percent));
            roundedSum = roundedSum.add(percent);
        }
        var roundingAdjustment = ONE_HUNDRED.subtract(roundedSum);
        if (roundingAdjustment.signum() != 0) {
            var first = entries.getFirst();
            entries.set(0, new CompanyRevenueMixEntry(
                    first.label(), first.value(), first.percentOfTotal().add(roundingAdjustment)
            ));
        }
        return new CompanyRevenueMixBreakdown(
                category,
                key.dimension(),
                key.dimensionName(),
                key.periodStart(),
                key.periodEnd(),
                key.unit(),
                total.value(),
                selection.selectedTotal(),
                selection.coverage().multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP),
                key.source(),
                entries
        );
    }

    private static CompanyRevenueMixBreakdown best(
            List<Candidate> candidates,
            Predicate<CompanyRevenueMixBreakdown> filter
    ) {
        return candidates.stream()
                .filter(candidate -> filter.test(candidate.breakdown()))
                .sorted(Comparator
                        .comparing((Candidate candidate) -> candidate.breakdown().periodEnd()).reversed()
                        .thenComparing(Candidate::dimensionPriority, Comparator.reverseOrder())
                        .thenComparing(Candidate::periodPriority, Comparator.reverseOrder())
                        .thenComparing(Candidate::coverageError)
                        .thenComparing(candidate -> candidate.breakdown().entries().size(), Comparator.reverseOrder()))
                .map(Candidate::breakdown)
                .findFirst()
                .orElse(null);
    }

    private static CompanyRevenueMixBreakdown.Category category(
            CompanyRevenueMixDimension dimension,
            List<CompanyRevenueMixFact> facts
    ) {
        if (dimension == CompanyRevenueMixDimension.GEOGRAPHY) {
            return CompanyRevenueMixBreakdown.Category.GEOGRAPHY;
        }
        if (dimension == CompanyRevenueMixDimension.REPORTABLE_SEGMENT) {
            var geographyLabels = facts.stream()
                    .filter(fact -> PURE_GEO_LABEL.matcher(fact.label().trim()).matches())
                    .count();
            if (geographyLabels * 5 >= facts.size() * 4L) {
                return CompanyRevenueMixBreakdown.Category.GEOGRAPHY;
            }
        }
        return CompanyRevenueMixBreakdown.Category.SEGMENT;
    }

    private static int dimensionPriority(
            CompanyRevenueMixBreakdown.Category category,
            CompanyRevenueMixDimension dimension
    ) {
        if (category == CompanyRevenueMixBreakdown.Category.GEOGRAPHY) {
            return dimension == CompanyRevenueMixDimension.GEOGRAPHY ? 30 : 20;
        }
        return switch (dimension) {
            case REPORTABLE_SEGMENT -> 30;
            case PRODUCT_OR_SERVICE -> 20;
            case GEOGRAPHY -> 0;
        };
    }

    private static int periodPriority(GroupKey key) {
        var days = ChronoUnit.DAYS.between(key.periodStart(), key.periodEnd());
        if (days >= 70 && days <= 120) return 3;
        if (days >= 300 && days <= 400) return 2;
        return 1;
    }

    private static boolean withinCoverage(BigDecimal coverage) {
        return coverage.compareTo(MIN_COVERAGE) >= 0 && coverage.compareTo(MAX_COVERAGE) <= 0;
    }

    private static BigDecimal coverageError(BigDecimal coverage) {
        return coverage.subtract(BigDecimal.ONE).abs();
    }

    private static String normalizeLabel(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private record GroupKey(
            String source,
            CompanyRevenueMixDimension dimension,
            String dimensionName,
            String unit,
            java.time.LocalDate periodStart,
            java.time.LocalDate periodEnd
    ) {
    }

    private record SourcedTotal(String source, CompanyRevenueTotal total) {
    }

    private record Selection(
            List<CompanyRevenueMixFact> facts,
            BigDecimal selectedTotal,
            BigDecimal coverage,
            BigDecimal error
    ) {
    }

    private record Candidate(
            CompanyRevenueMixBreakdown breakdown,
            BigDecimal coverageError,
            Integer dimensionPriority,
            Integer periodPriority
    ) {
        CompanyRevenueMixBreakdown.Category category() {
            return breakdown.category();
        }
    }
}
