package io.macrosquare.company.adapter.out.sec;

import io.macrosquare.company.domain.model.CompanyFundamentalsEvidence;
import io.macrosquare.company.domain.model.FinancialFactPoint;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * Streaming SEC Company Facts parser.
 *
 * <p>Only the XBRL tags observed by the legacy normalizer are materialized.
 * The rest of the potentially multi-megabyte payload is skipped without first
 * building a Jackson tree.</p>
 */
final class SecCompanyFactsMapper {

    private static final String US_GAAP = "us-gaap";
    private static final String DEI = "dei";

    private static final Set<String> US_GAAP_TAGS = Set.of(
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            "RevenueFromContractWithCustomerIncludingAssessedTax",
            "Revenues",
            "RevenuesNetOfInterestExpense",
            "SalesRevenueNet",
            "RegulatedAndUnregulatedOperatingRevenue",
            "RealEstateRevenueNet",
            "OperatingIncomeLoss",
            "CostsAndExpenses",
            "NetIncomeLoss",
            "NetCashProvidedByUsedInOperatingActivities",
            "PaymentsToAcquirePropertyPlantAndEquipment",
            "CashAndCashEquivalentsAtCarryingValue",
            "CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents",
            "LongTermDebtAndFinanceLeaseObligations",
            "LongTermDebtAndCapitalLeaseObligations",
            "LongTermDebt",
            "ShareBasedCompensation",
            "StockBasedCompensation",
            "StockholdersEquity",
            "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest",
            "AssetsCurrent",
            "LiabilitiesCurrent",
            "AccountsReceivableNetCurrent",
            "ReceivablesNetCurrent",
            "InventoryNet",
            "InventoriesNetOfReserves",
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest",
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments",
            "IncomeTaxExpenseBenefit",
            "Assets",
            "WeightedAverageNumberOfDilutedSharesOutstanding",
            "WeightedAverageNumberOfSharesOutstandingBasic"
    );
    private static final Set<String> DEI_TAGS = Set.of("EntityCommonStockSharesOutstanding");
    private static final Set<String> SHARE_TAGS = Set.of(
            "EntityCommonStockSharesOutstanding",
            "WeightedAverageNumberOfDilutedSharesOutstanding",
            "WeightedAverageNumberOfSharesOutstandingBasic"
    );

    private SecCompanyFactsMapper() {
    }

    static CompanyFundamentalsEvidence map(JsonParser parser) {
        return map(parser, null);
    }

    static CompanyFundamentalsEvidence map(JsonParser parser, String expectedCik) {
        Objects.requireNonNull(parser, "parser");
        var token = parser.currentToken() == null ? parser.nextToken() : parser.currentToken();
        if (token != JsonToken.START_OBJECT) {
            throw new IllegalArgumentException("SEC company facts must be an object");
        }

        var factsFound = false;
        String returnedCik = null;
        var values = new LinkedHashMap<FactKey, FactSeries>();
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "SEC company facts root");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if ("cik".equals(name)) {
                if (valueToken != null && valueToken.isNumeric()) returnedCik = parser.getValueAsString();
                else if (valueToken == JsonToken.VALUE_STRING) returnedCik = parser.getString();
                else parser.skipChildren();
            } else if ("facts".equals(name)) {
                values.clear();
                factsFound = valueToken == JsonToken.START_OBJECT;
                if (factsFound) parseFacts(parser, values);
                else parser.skipChildren();
            } else {
                parser.skipChildren();
            }
        }
        if (!factsFound) {
            throw new IllegalArgumentException("SEC company facts payload must contain a facts object");
        }
        if (expectedCik != null && !normalizeCik(expectedCik).equals(normalizeCik(returnedCik))) {
            throw new IllegalArgumentException("SEC company facts CIK did not match the request");
        }

        return new CompanyFundamentalsEvidence(
                select(values, US_GAAP,
                        "Revenues",
                        "RevenuesNetOfInterestExpense",
                        "SalesRevenueNet",
                        "RegulatedAndUnregulatedOperatingRevenue",
                        "RealEstateRevenueNet",
                        "RevenueFromContractWithCustomerExcludingAssessedTax",
                        "RevenueFromContractWithCustomerIncludingAssessedTax"),
                select(values, US_GAAP, "OperatingIncomeLoss"),
                select(values, US_GAAP, "NetIncomeLoss"),
                select(values, US_GAAP, "NetCashProvidedByUsedInOperatingActivities"),
                select(values, US_GAAP, "PaymentsToAcquirePropertyPlantAndEquipment"),
                select(values, US_GAAP, "CashAndCashEquivalentsAtCarryingValue", "CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents"),
                select(values, US_GAAP,
                        "LongTermDebtAndFinanceLeaseObligations",
                        "LongTermDebtAndCapitalLeaseObligations",
                        "LongTermDebt"),
                select(values, DEI, "EntityCommonStockSharesOutstanding"),
                select(values, US_GAAP, "ShareBasedCompensation", "StockBasedCompensation"),
                select(values, US_GAAP, "StockholdersEquity", "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"),
                select(values, US_GAAP, "AssetsCurrent"),
                select(values, US_GAAP, "LiabilitiesCurrent"),
                select(values, US_GAAP, "AccountsReceivableNetCurrent", "ReceivablesNetCurrent"),
                select(values, US_GAAP, "InventoryNet", "InventoriesNetOfReserves"),
                select(
                        values,
                        US_GAAP,
                        "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest",
                        "IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments"
                ),
                select(values, US_GAAP, "IncomeTaxExpenseBenefit"),
                select(values, US_GAAP, "Assets"),
                select(values, US_GAAP,
                        "WeightedAverageNumberOfDilutedSharesOutstanding",
                        "WeightedAverageNumberOfSharesOutstandingBasic"),
                select(values, US_GAAP, "CostsAndExpenses")
        );
    }

    private static String normalizeCik(String value) {
        if (value == null) return "";
        var digits = value.replaceAll("\\D+", "");
        if (digits.isEmpty() || digits.length() > 10) return "";
        return "0".repeat(10 - digits.length()) + digits;
    }

    private static void parseFacts(JsonParser parser, Map<FactKey, FactSeries> values) {
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "SEC facts");
            var taxonomy = parser.currentName();
            var valueToken = parser.nextToken();
            var selectedTags = selectedTags(taxonomy);
            if (selectedTags == null) {
                parser.skipChildren();
                continue;
            }

            values.keySet().removeIf(key -> taxonomy.equals(key.taxonomy()));
            if (valueToken == JsonToken.START_OBJECT) parseTaxonomy(parser, taxonomy, selectedTags, values);
            else parser.skipChildren();
        }
    }

    private static void parseTaxonomy(
            JsonParser parser,
            String taxonomy,
            Set<String> selectedTags,
            Map<FactKey, FactSeries> values
    ) {
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "SEC taxonomy");
            var tag = parser.currentName();
            var valueToken = parser.nextToken();
            if (!selectedTags.contains(tag)) {
                parser.skipChildren();
                continue;
            }

            var key = new FactKey(taxonomy, tag);
            if (valueToken == JsonToken.START_OBJECT) {
                var series = parseFact(parser, expectedUnit(tag));
                if (series.available()) values.put(key, series);
                else values.remove(key);
            } else {
                values.remove(key);
                parser.skipChildren();
            }
        }
    }

    private static FactSeries parseFact(JsonParser parser, String expectedUnit) {
        var result = FactSeries.unavailable();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "SEC fact");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if ("units".equals(name) && valueToken == JsonToken.START_OBJECT) {
                result = parseUnits(parser, expectedUnit);
            } else {
                if ("units".equals(name)) result = FactSeries.unavailable();
                parser.skipChildren();
            }
        }
        return result;
    }

    private static FactSeries parseUnits(JsonParser parser, String expectedUnit) {
        var units = new LinkedHashMap<String, UnitSeries>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "SEC fact units");
            var unitName = parser.currentName();
            var valueToken = parser.nextToken();
            if (valueToken == JsonToken.START_ARRAY) units.put(unitName, parseUnitArray(parser));
            else {
                units.put(unitName, UnitSeries.unavailable());
                parser.skipChildren();
            }
        }
        return java.util.Optional.ofNullable(units.get(expectedUnit))
                .filter(UnitSeries::nonEmptyArray)
                .map(unit -> new FactSeries(true, compactForNormalization(unit.points())))
                .orElseGet(FactSeries::unavailable);
    }

    private static String expectedUnit(String tag) {
        return SHARE_TAGS.contains(tag) ? "shares" : "USD";
    }

    private static UnitSeries parseUnitArray(JsonParser parser) {
        var points = new ArrayList<FinancialFactPoint>();
        var nonEmpty = false;
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            nonEmpty = true;
            if (token == JsonToken.START_OBJECT) {
                var point = parsePoint(parser);
                if (point != null) points.add(point);
            } else {
                parser.skipChildren();
            }
        }
        return new UnitSeries(nonEmpty, points);
    }

    private static FinancialFactPoint parsePoint(JsonParser parser) {
        Double value = null;
        var form = (String) null;
        var fiscalPeriod = (String) null;
        var endDate = (String) null;
        var startDate = (String) null;
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "SEC fact point");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            switch (name) {
                case "val" -> value = valueToken != null && valueToken.isNumeric() ? parser.getDoubleValue() : null;
                case "form" -> form = valueToken == JsonToken.VALUE_STRING ? parser.getString() : null;
                case "fp" -> fiscalPeriod = valueToken == JsonToken.VALUE_STRING ? parser.getString() : null;
                case "end" -> endDate = valueToken == JsonToken.VALUE_STRING ? parser.getString() : null;
                case "start" -> startDate = valueToken == JsonToken.VALUE_STRING ? parser.getString() : null;
                default -> {
                }
            }
            parser.skipChildren();
        }
        return value == null ? null : new FinancialFactPoint(value, form, fiscalPeriod, endDate, startDate);
    }

    private static Set<String> selectedTags(String taxonomy) {
        return switch (taxonomy) {
            case US_GAAP -> US_GAAP_TAGS;
            case DEI -> DEI_TAGS;
            default -> null;
        };
    }

    private static List<FinancialFactPoint> select(
            Map<FactKey, FactSeries> values,
            String taxonomy,
            String... tags
    ) {
        FactSeries selected = null;
        SeriesFreshness selectedFreshness = null;
        for (var tag : tags) {
            var series = values.get(new FactKey(taxonomy, tag));
            if (series == null || !series.available() || series.points().isEmpty()) continue;
            var freshness = freshness(series);
            if (selected == null || freshness.compareTo(selectedFreshness) > 0) {
                selected = series;
                selectedFreshness = freshness;
            }
        }
        return selected == null ? List.of() : selected.points();
    }

    private static SeriesFreshness freshness(FactSeries series) {
        var annualPeriods = series.points().stream()
                .filter(SecCompanyFactsMapper::isAnnualFact)
                .map(FinancialFactPoint::endDate)
                .filter(Objects::nonNull)
                .distinct()
                .limit(2)
                .count();
        // A newly adopted XBRL tag can be a few weeks fresher while containing
        // only one quarter. Selecting it over the issuer's established total-
        // revenue tag makes TTM revenue, growth and every valuation multiple
        // disappear. Prefer a series capable of reconstructing history; once
        // the new tag has two FYs or four actual quarter ends it can win on
        // freshness normally.
        var usableHistory = annualPeriods >= 2 || hasContinuousFourQuarterWindow(series.points()) ? 1 : 0;
        var reported = series.points().stream()
                .filter(point -> isPeriodicReport(point.form()))
                .map(FinancialFactPoint::endDate)
                .filter(Objects::nonNull)
                .max(String::compareTo);
        if (reported.isPresent()) return new SeriesFreshness(usableHistory, 1, reported.get());
        var any = series.points().stream()
                .map(FinancialFactPoint::endDate)
                .filter(Objects::nonNull)
                .max(String::compareTo)
                .orElse("");
        return new SeriesFreshness(usableHistory, 0, any);
    }

    private static boolean hasContinuousFourQuarterWindow(List<FinancialFactPoint> points) {
        var sorted = points.stream()
                .filter(point -> isPeriodicReport(point.form()))
                .filter(point -> point.startDate() != null && point.endDate() != null)
                .filter(point -> durationBetween(point, 70, 120))
                .sorted(Comparator.comparing(FinancialFactPoint::endDate).reversed())
                .toList();
        var periods = new LinkedHashSet<String>();
        var quarters = new ArrayList<FinancialFactPoint>();
        for (var point : sorted) {
            if (!periods.add(periodKey(point))) continue;
            quarters.add(point);
            if (quarters.size() == 4) break;
        }
        if (quarters.size() < 4) return false;
        try {
            var firstStart = LocalDate.parse(quarters.getLast().startDate());
            var lastEnd = LocalDate.parse(quarters.getFirst().endDate());
            var coverage = ChronoUnit.DAYS.between(firstStart, lastEnd);
            return coverage >= 300 && coverage <= 430;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * The normalizer needs both standalone and year-to-date duration facts to
     * construct a true trailing-twelve-month value. Keep bounded, distinct
     * business periods rather than collapsing all facts with the same end date;
     * a Q2 standalone fact and a Q2 YTD fact intentionally share that date.
     */
    private static List<FinancialFactPoint> compactForNormalization(List<FinancialFactPoint> points) {
        var retained = new LinkedHashSet<Integer>();
        retainLatest(points, point -> point.endDate() != null, 1,
                FinancialFactPoint::endDate, retained);
        retainLatest(points, point -> point.endDate() != null
                        && point.startDate() != null
                        && isQuarterlyReport(point.form()), 12,
                SecCompanyFactsMapper::periodKey, retained);
        retainLatest(points, point -> point.endDate() != null
                        && point.startDate() == null
                        && isQuarterlyReport(point.form()), 4,
                FinancialFactPoint::endDate, retained);
        retainLatest(points, point -> point.endDate() != null
                        && isAnnualReport(point.form())
                        && "FY".equals(point.fiscalPeriod())
                        && durationBetween(point, 70, 120), 4,
                SecCompanyFactsMapper::periodKey, retained);
        retainLatest(
                points,
                point -> point.endDate() != null
                        && isAnnualReport(point.form())
                        && "FY".equals(point.fiscalPeriod())
                        && (point.startDate() == null || durationBetween(point, 300, 430)),
                4,
                SecCompanyFactsMapper::periodKey,
                retained
        );
        return IntStream.range(0, points.size())
                .filter(retained::contains)
                .mapToObj(points::get)
                .toList();
    }

    private static void retainLatest(
            List<FinancialFactPoint> points,
            Predicate<FinancialFactPoint> predicate,
            int limit,
            Function<FinancialFactPoint, String> distinctKey,
            LinkedHashSet<Integer> retained
    ) {
        var indices = IntStream.range(0, points.size())
                .filter(index -> predicate.test(points.get(index)))
                .boxed()
                .sorted((left, right) -> {
                    var byDate = points.get(right).endDate().compareTo(points.get(left).endDate());
                    // SEC arrays append later filings/restatements for the
                    // same period; prefer the last occurrence.
                    return byDate != 0 ? byDate : Integer.compare(right, left);
                })
                .toList();
        var periods = new LinkedHashSet<String>();
        for (var index : indices) {
            if (!periods.add(distinctKey.apply(points.get(index)))) continue;
            retained.add(index);
            if (periods.size() == limit) break;
        }
    }

    private static String periodKey(FinancialFactPoint point) {
        return (point.startDate() == null ? "" : point.startDate()) + "|" + point.endDate();
    }

    private static boolean durationBetween(FinancialFactPoint point, long minimum, long maximum) {
        try {
            var days = ChronoUnit.DAYS.between(
                    LocalDate.parse(point.startDate()), LocalDate.parse(point.endDate())) + 1;
            return days >= minimum && days <= maximum;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isPeriodicReport(String form) {
        return isAnnualReport(form) || isQuarterlyReport(form);
    }

    private static boolean isAnnualReport(String form) {
        return "10-K".equals(form) || "20-F".equals(form);
    }

    private static boolean isAnnualFact(FinancialFactPoint point) {
        if (!isAnnualReport(point.form()) || !"FY".equals(point.fiscalPeriod())) return false;
        return point.startDate() == null || durationBetween(point, 300, 430);
    }

    private static boolean isQuarterlyReport(String form) {
        return "10-Q".equals(form) || "6-K".equals(form);
    }

    private static void requireToken(JsonToken actual, JsonToken expected, String context) {
        if (actual != expected) throw new IllegalArgumentException(context + " has an invalid JSON shape");
    }

    private record FactKey(String taxonomy, String tag) {
    }

    private record FactSeries(boolean available, List<FinancialFactPoint> points) {
        private FactSeries {
            points = List.copyOf(points);
        }

        static FactSeries unavailable() {
            return new FactSeries(false, List.of());
        }
    }

    private record UnitSeries(boolean nonEmptyArray, List<FinancialFactPoint> points) {
        private UnitSeries {
            points = List.copyOf(points);
        }

        static UnitSeries unavailable() {
            return new UnitSeries(false, List.of());
        }
    }

    private record SeriesFreshness(
            int usableHistory,
            int reported,
            String endDate
    ) implements Comparable<SeriesFreshness> {
        @Override
        public int compareTo(SeriesFreshness other) {
            var byUsableHistory = Integer.compare(usableHistory, other.usableHistory);
            if (byUsableHistory != 0) return byUsableHistory;
            var byReported = Integer.compare(reported, other.reported);
            return byReported != 0 ? byReported : endDate.compareTo(other.endDate);
        }
    }
}
