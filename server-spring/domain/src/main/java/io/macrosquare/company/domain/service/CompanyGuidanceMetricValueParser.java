package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyGuidanceMetricValue;
import io.macrosquare.company.domain.model.CompanyGuidanceMetricValue.Unit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure parser responsible only for typed numeric guidance ranges and units. */
final class CompanyGuidanceMetricValueParser {

    private static final int MAX_CLAUSE_CHARACTERS = 320;
    private static final MathContext CALCULATION_CONTEXT = new MathContext(16, RoundingMode.HALF_UP);
    private static final String NUMBER = "(-?\\d+(?:\\.\\d+)?)";
    private static final String MAGNITUDE = "(billion|bn|million|mn|[bm])";
    private static final String CURRENCY = "(?:us\\$|usd\\s*|\\$|€|eur\\s*)?";

    CompanyGuidanceMetricValue parseGeneric(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var normalized = normalizeClause(raw);
        var monetary = hasUsdEvidence(normalized);
        var value = monetary ? parseUsd(normalized) : parsePercent(normalized);
        if (value == null) value = monetary ? parsePercent(normalized) : parseUsd(normalized);
        if (value == null) value = parseBps(normalized);
        return value == null ? otherValue(normalized) : value;
    }

    CompanyGuidanceMetricValue parseMargin(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var normalized = normalizeClause(raw);
        var value = parsePercent(normalized);
        if (value == null) value = parseBps(normalized);
        return value == null ? otherValue(normalized) : value;
    }

    CompanyGuidanceMetricValue parseMonetaryFirst(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var normalized = normalizeClause(raw);
        var value = parseCurrentGuidanceTableValue(normalized);
        if (value == null) value = parseLatestQuarterlyGuidanceTableValue(normalized);
        if (value == null) value = parseUpdatedGuidancePercentTableValue(normalized);
        if (value == null) value = parseUsd(normalized);
        if (value == null) value = parsePercent(normalized);
        if (value == null) value = parseBps(normalized);
        return value == null ? otherValue(normalized) : value;
    }

    CompanyGuidanceMetricValue parseGrowthFirst(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var normalized = normalizeClause(raw);
        var value = Pattern.compile("\\b(?:growth|change)\\s+guidance\\b|\\bguidance\\b.{0,50}\\b(?:growth|change)\\b")
                .matcher(normalized)
                .find()
                ? parsePercent(normalized)
                : null;
        if (value == null) value = parseMonetaryFirst(normalized);
        return value;
    }

    /**
     * Earnings-release tables commonly use Result / Current Guidance / Prior Guidance.
     * Bind to the revenue row and select its second monetary cell so a generic range
     * matcher cannot drift into the following EBITDA row.
     */
    private static CompanyGuidanceMetricValue parseCurrentGuidanceTableValue(String raw) {
        var lower = raw.toLowerCase(Locale.ROOT).replace('–', '-').replace('—', '-');
        var header = Pattern.compile("\\bcurrent guidance\\s+prior guidance\\b").matcher(lower);
        if (!header.find()) return null;
        var metric = Pattern.compile("\\b(?:revenue|net sales|total sales|top[- ]line)\\b")
                .matcher(lower);
        if (!metric.find(header.end())) return null;

        var rowEnd = lower.length();
        var nextMetric = Pattern.compile(
                "\\b(?:adjusted\\s+)?(?:ebitda|ebit|operating expenses?|earnings per share|eps|free cash flow|fcf|"
                        + "capex|capital expenditures?|gross margins?|operating margins?)\\b"
        ).matcher(lower);
        if (nextMetric.find(metric.end())) rowEnd = nextMetric.start();

        var amounts = new ArrayList<TableAmount>(3);
        var amountMatcher = Pattern.compile(
                "([~><]\\s*)?(us\\$|usd\\s*|\\$|€|eur\\s*)\\s*(-?\\d[\\d,]*(?:\\.\\d+)?)"
                        + "\\s*(billion|bn|million|mn|[bm])?\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(lower.substring(metric.end(), rowEnd));
        while (amountMatcher.find() && amounts.size() < 3) {
            amounts.add(new TableAmount(
                    amountMatcher.group(2),
                    amountMatcher.group(3),
                    amountMatcher.group(4),
                    amountMatcher.start(),
                    amountMatcher.end()
            ));
        }
        if (amounts.size() < 3) return null;

        var current = amounts.get(1);
        var magnitude = current.magnitude();
        if (magnitude == null) {
            if (Pattern.compile("\\((?:\\$\\s+)?in millions?[^)]*\\)").matcher(lower).find()) {
                magnitude = "million";
            } else if (Pattern.compile("\\((?:\\$\\s+)?in billions?[^)]*\\)").matcher(lower).find()) {
                magnitude = "billion";
            }
        }
        var value = scaled(current.number().replace(",", ""), magnitude);
        var currency = current.currency().trim();
        var unit = currency.contains("€") || currency.startsWith("eur") ? Unit.EUR : Unit.USD;
        return new CompanyGuidanceMetricValue(bounded(raw), value, value, unit);
    }

    private static CompanyGuidanceMetricValue parseLatestQuarterlyGuidanceTableValue(String raw) {
        var lower = raw.toLowerCase(Locale.ROOT).replace('–', '-').replace('—', '-');
        if (!Pattern.compile("\\bquarterly guidance\\s*(?:&|and)\\s*results\\b").matcher(lower).find()) {
            return null;
        }
        var metric = Pattern.compile("\\b(?:revenue|net sales|total sales|top[- ]line)\\b").matcher(lower);
        if (!metric.find()) return null;
        var rowEnd = guidanceRowEnd(lower, metric.end());
        var row = lower.substring(metric.end(), rowEnd);
        var amounts = monetaryTableAmounts(row);
        if (amounts.size() < 3) return null;
        var current = amounts.get(amounts.size() - 1);
        var point = tablePoint(raw, current, null);
        var tolerance = trailingAbsoluteTolerance(row.substring(current.end()));
        if (tolerance == null) return point;
        var delta = scaled(tolerance.number(), tolerance.magnitude());
        return new CompanyGuidanceMetricValue(
                bounded(raw),
                normalize(point.min().subtract(delta)),
                normalize(point.max().add(delta)),
                point.unit()
        );
    }

    private static CompanyGuidanceMetricValue parseUpdatedGuidancePercentTableValue(String raw) {
        var lower = raw.toLowerCase(Locale.ROOT).replace('–', '-').replace('—', '-');
        if (!Pattern.compile("\\boriginal\\s+updated\\s+low\\s+high\\s+low\\s+high\\b")
                .matcher(lower).find()) {
            return null;
        }
        var metric = Pattern.compile("\\bprojected (?:revenue|net sales|total sales) change\\b").matcher(lower);
        if (!metric.find()) return null;
        var percentages = new ArrayList<BigDecimal>(4);
        var matcher = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*%").matcher(lower.substring(metric.end()));
        while (matcher.find() && percentages.size() < 4) percentages.add(decimal(matcher.group(1)));
        if (percentages.size() < 4) return null;
        return percent(
                raw,
                percentages.get(percentages.size() - 2),
                percentages.get(percentages.size() - 1)
        );
    }

    private static int guidanceRowEnd(String text, int afterMetric) {
        var matcher = Pattern.compile(
                "\\b(?:adjusted\\s+)?(?:ebitda|ebit|operating expenses?|earnings per share|eps|free cash flow|fcf|"
                        + "capex|capital expenditures?|gross margins?|operating margins?)\\b"
        ).matcher(text);
        return matcher.find(afterMetric) ? matcher.start() : text.length();
    }

    private static ArrayList<TableAmount> monetaryTableAmounts(String row) {
        var amounts = new ArrayList<TableAmount>();
        var matcher = Pattern.compile(
                "([~><]\\s*)?(us\\$|usd\\s*|\\$|€|eur\\s*)\\s*(-?\\d[\\d,]*(?:\\.\\d+)?)"
                        + "\\s*(billion|bn|million|mn|[bm])?\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(row);
        while (matcher.find()) {
            var prefix = row.substring(Math.max(0, matcher.start() - 10), matcher.start());
            if (Pattern.compile("(?:\\+/-|±)\\s*$").matcher(prefix).find()) continue;
            amounts.add(new TableAmount(
                    matcher.group(2),
                    matcher.group(3),
                    matcher.group(4),
                    matcher.start(),
                    matcher.end()
            ));
        }
        return amounts;
    }

    private static CompanyGuidanceMetricValue tablePoint(
            String raw,
            TableAmount amount,
            String fallbackMagnitude
    ) {
        var value = scaled(
                amount.number().replace(",", ""),
                amount.magnitude() == null ? fallbackMagnitude : amount.magnitude()
        );
        var currency = amount.currency().trim();
        var unit = currency.contains("€") || currency.startsWith("eur") ? Unit.EUR : Unit.USD;
        return new CompanyGuidanceMetricValue(bounded(raw), value, value, unit);
    }

    private static CompanyGuidanceMetricValue parsePercent(String raw) {
        var lower = raw.toLowerCase(Locale.ROOT).replace(',', ' ').replace('–', '-').replace('—', '-');
        var band = Pattern.compile(
                "\\b(low|mid|high)\\s*[- ]?(single[- ]digit(?:s)?|double[- ]digit(?:s)?|teens|20s|30s|40s|50s|60s)\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(lower);
        if (band.find()) {
            var range = bandRange(band.group(1), band.group(2));
            return percent(raw, range.min(), range.max());
        }

        var range = Pattern.compile(
                NUMBER + "\\s*(?:%|percent)?\\s*(?:to|and|-)\\s*" + NUMBER + "\\s*(?:%|percent)",
                Pattern.CASE_INSENSITIVE
        ).matcher(lower);
        if (range.find()) {
            return applyPercentTolerance(
                    raw,
                    decimal(range.group(1)),
                    decimal(range.group(2)),
                    lower
            );
        }
        var between = Pattern.compile(
                "\\bbetween\\s+" + NUMBER + "\\s*(?:%|percent)?\\s*(?:and|to)\\s*"
                        + NUMBER + "\\s*(?:%|percent)",
                Pattern.CASE_INSENSITIVE
        ).matcher(lower);
        if (between.find()) {
            return applyPercentTolerance(
                    raw,
                    decimal(between.group(1)),
                    decimal(between.group(2)),
                    lower
            );
        }

        var lowerBound = Pattern.compile(
                "\\b(?:at least|greater than|more than|above)\\s+" + NUMBER + "\\s*(?:%|percent)",
                Pattern.CASE_INSENSITIVE
        ).matcher(lower);
        if (lowerBound.find()) return percent(raw, decimal(lowerBound.group(1)), null);

        var upperBound = Pattern.compile(
                "\\b(?:up to|less than|below)\\s+" + NUMBER + "\\s*(?:%|percent)",
                Pattern.CASE_INSENSITIVE
        ).matcher(lower);
        if (upperBound.find()) return percent(raw, null, decimal(upperBound.group(1)));

        var single = Pattern.compile(NUMBER + "\\s*(?:%|percent)", Pattern.CASE_INSENSITIVE).matcher(lower);
        if (single.find()) {
            var value = decimal(single.group(1));
            return applyPercentTolerance(raw, value, value, lower);
        }
        return null;
    }

    private static CompanyGuidanceMetricValue parseUsd(String raw) {
        var lower = raw.toLowerCase(Locale.ROOT).replace(",", "").replace('–', '-').replace('—', '-');
        var between = Pattern.compile(
                "\\bbetween\\s*" + CURRENCY + "\\s*" + NUMBER + "\\s*" + MAGNITUDE + "?\\s*"
                        + "(?:and|to)\\s*" + CURRENCY + "\\s*" + NUMBER + "\\s*" + MAGNITUDE + "?\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(lower);
        if (between.find() && containsCurrencyEvidence(between.group())) {
            var left = scaledWithContext(between.group(1), firstText(between.group(2), between.group(4)), lower);
            var right = scaledWithContext(between.group(3), firstText(between.group(4), between.group(2)), lower);
            return applyUsdTolerance(raw, min(left, right), max(left, right), lower);
        }

        var range = Pattern.compile(
                CURRENCY + "\\s*" + NUMBER + "\\s*" + MAGNITUDE + "?\\s*(?:to|and|-)\\s*"
                        + CURRENCY + "\\s*" + NUMBER + "\\s*" + MAGNITUDE + "?\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(lower);
        if (range.find() && containsCurrencyEvidence(range.group())) {
            var left = scaledWithContext(range.group(1), firstText(range.group(2), range.group(4)), lower);
            var right = scaledWithContext(range.group(3), firstText(range.group(4), range.group(2)), lower);
            return applyUsdTolerance(raw, min(left, right), max(left, right), lower);
        }

        var lowerBound = Pattern.compile(
                "\\b(?:at least|greater than|more than|above)\\s*" + CURRENCY + "\\s*" + NUMBER
                        + "\\s*" + MAGNITUDE + "?\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(lower);
        if (lowerBound.find() && containsCurrencyEvidence(lowerBound.group())) {
            return usd(raw, scaledWithContext(lowerBound.group(1), lowerBound.group(2), lower), null);
        }

        var upperBound = Pattern.compile(
                "\\b(?:up to|less than|below)\\s*" + CURRENCY + "\\s*" + NUMBER
                        + "\\s*" + MAGNITUDE + "?\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(lower);
        if (upperBound.find() && containsCurrencyEvidence(upperBound.group())) {
            return usd(raw, null, scaledWithContext(upperBound.group(1), upperBound.group(2), lower));
        }

        var single = Pattern.compile(
                "(?:us\\$|usd\\s*|\\$|€|eur\\s*)\\s*" + NUMBER + "\\s*" + MAGNITUDE
                        + "?\\b|\\b" + NUMBER + "\\s*" + MAGNITUDE + "\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(lower);
        if (single.find()) {
            var number = firstText(single.group(1), single.group(3));
            var magnitude = firstText(single.group(2), single.group(4));
            var value = scaledWithContext(number, magnitude, lower);
            return applyUsdTolerance(raw, value, value, lower);
        }
        return null;
    }

    private static CompanyGuidanceMetricValue parseBps(String raw) {
        var lower = raw.toLowerCase(Locale.ROOT).replace('–', '-').replace('—', '-');
        var range = Pattern.compile(
                NUMBER + "\\s*(?:to|and|-)\\s*" + NUMBER + "\\s*(?:bps|basis points?)",
                Pattern.CASE_INSENSITIVE
        ).matcher(lower);
        if (range.find()) return bps(raw, decimal(range.group(1)), decimal(range.group(2)));
        var single = Pattern.compile(NUMBER + "\\s*(?:bps|basis points?)", Pattern.CASE_INSENSITIVE).matcher(lower);
        if (single.find()) {
            var value = decimal(single.group(1));
            return bps(raw, value, value);
        }
        return null;
    }

    private static CompanyGuidanceMetricValue applyPercentTolerance(
            String raw,
            BigDecimal min,
            BigDecimal max,
            String normalized
    ) {
        var ordered = ordered(min, max);
        min = ordered.min();
        max = ordered.max();
        var bps = tolerance(normalized, "(?:bps|basis points?)");
        if (bps != null) {
            var delta = bps.divide(BigDecimal.valueOf(100), CALCULATION_CONTEXT);
            return percent(raw, normalize(min.subtract(delta)), normalize(max.add(delta)));
        }
        var percentagePoints = tolerance(normalized, "(?:percentage points?|points?|%)");
        if (percentagePoints != null) {
            return percent(
                    raw,
                    normalize(min.subtract(percentagePoints)),
                    normalize(max.add(percentagePoints))
            );
        }
        return percent(raw, min, max);
    }

    private static CompanyGuidanceMetricValue applyUsdTolerance(
            String raw,
            BigDecimal min,
            BigDecimal max,
            String normalized
    ) {
        var ordered = ordered(min, max);
        min = ordered.min();
        max = ordered.max();
        var absolute = absoluteUsdTolerance(normalized);
        if (absolute != null) {
            var delta = scaledWithContext(absolute.number(), absolute.magnitude(), normalized).abs();
            return usd(raw, normalize(min.subtract(delta)), normalize(max.add(delta)));
        }
        var percentage = tolerance(normalized, "%");
        if (percentage == null) return usd(raw, min, max);
        var ratio = percentage.divide(BigDecimal.valueOf(100), CALCULATION_CONTEXT);
        return usd(
                raw,
                normalize(min.multiply(BigDecimal.ONE.subtract(ratio), CALCULATION_CONTEXT)),
                normalize(max.multiply(BigDecimal.ONE.add(ratio), CALCULATION_CONTEXT))
        );
    }

    private static BigDecimal tolerance(String text, String unitPattern) {
        var matcher = Pattern.compile(
                "(?:plus or minus|\\+/-|±)\\s*" + NUMBER + "\\s*" + unitPattern,
                Pattern.CASE_INSENSITIVE
        ).matcher(text);
        return matcher.find() ? decimal(matcher.group(1)).abs() : null;
    }

    private static AbsoluteTolerance absoluteUsdTolerance(String text) {
        var matcher = Pattern.compile(
                "(?:plus or minus|\\+/-|±)\\s*(?:us\\$|usd\\s*|\\$|€|eur\\s*)?\\s*"
                        + NUMBER + "\\s*" + MAGNITUDE + "?\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(text);
        if (!matcher.find() || !containsCurrencyEvidence(matcher.group())) return null;
        return new AbsoluteTolerance(matcher.group(1), matcher.group(2));
    }

    private static AbsoluteTolerance trailingAbsoluteTolerance(String text) {
        var matcher = Pattern.compile(
                "^\\s*(?:plus or minus|\\+/-|±)\\s*(?:us\\$|usd\\s*|\\$|€|eur\\s*)?\\s*"
                        + NUMBER + "\\s*" + MAGNITUDE + "?\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(text);
        return matcher.find() ? new AbsoluteTolerance(matcher.group(1), matcher.group(2)) : null;
    }

    private static CompanyGuidanceMetricValue otherValue(String raw) {
        return new CompanyGuidanceMetricValue(bounded(raw), null, null, Unit.OTHER);
    }

    private static CompanyGuidanceMetricValue percent(String raw, BigDecimal min, BigDecimal max) {
        var ordered = ordered(min, max);
        return new CompanyGuidanceMetricValue(bounded(raw), ordered.min(), ordered.max(), Unit.PERCENT);
    }

    private static CompanyGuidanceMetricValue usd(String raw, BigDecimal min, BigDecimal max) {
        var unit = Pattern.compile("€|\\beur\\b", Pattern.CASE_INSENSITIVE).matcher(raw).find()
                ? Unit.EUR
                : Unit.USD;
        var ordered = ordered(min, max);
        return new CompanyGuidanceMetricValue(bounded(raw), ordered.min(), ordered.max(), unit);
    }

    private static CompanyGuidanceMetricValue bps(String raw, BigDecimal min, BigDecimal max) {
        var ordered = ordered(min, max);
        return new CompanyGuidanceMetricValue(bounded(raw), ordered.min(), ordered.max(), Unit.BPS);
    }

    private static boolean hasUsdEvidence(String text) {
        return text.indexOf('$') >= 0 || text.indexOf('€') >= 0
                || Pattern.compile("\\b(?:usd|eur)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()
                || Pattern.compile("\\b(?:billion|bn|million|mn)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private static boolean containsCurrencyEvidence(String text) {
        return hasUsdEvidence(text);
    }

    private static Band bandRange(String zone, String bucket) {
        var normalizedBucket = bucket.toLowerCase(Locale.ROOT).replace('-', ' ')
                .replaceAll("\\s+", " ").trim();
        int low;
        int high;
        switch (normalizedBucket) {
            case "single digit", "single digits" -> {
                low = 1;
                high = 9;
            }
            case "double digit", "double digits", "teens" -> {
                low = 10;
                high = 19;
            }
            case "20s" -> {
                low = 20;
                high = 29;
            }
            case "30s" -> {
                low = 30;
                high = 39;
            }
            case "40s" -> {
                low = 40;
                high = 49;
            }
            case "50s" -> {
                low = 50;
                high = 59;
            }
            case "60s" -> {
                low = 60;
                high = 69;
            }
            default -> throw new IllegalArgumentException("unsupported guidance band: " + bucket);
        }
        return switch (zone.toLowerCase(Locale.ROOT)) {
            case "low" -> new Band(BigDecimal.valueOf(low), BigDecimal.valueOf(low + 3));
            case "mid" -> new Band(BigDecimal.valueOf(low + 3), BigDecimal.valueOf(low + 6));
            case "high" -> new Band(BigDecimal.valueOf(high - 3), BigDecimal.valueOf(high));
            default -> throw new IllegalArgumentException("unsupported guidance zone: " + zone);
        };
    }

    private static BigDecimal scaled(String raw, String magnitude) {
        var value = decimal(raw);
        if (magnitude == null) return value;
        return switch (magnitude.toLowerCase(Locale.ROOT)) {
            case "billion", "bn", "b" -> normalize(value.multiply(BigDecimal.valueOf(1_000_000_000L)));
            case "million", "mn", "m" -> normalize(value.multiply(BigDecimal.valueOf(1_000_000L)));
            default -> value;
        };
    }

    private static BigDecimal scaledWithContext(String raw, String magnitude, String context) {
        if (magnitude != null) return scaled(raw, magnitude);
        if (Pattern.compile("\\((?:\\$\\s+)?in millions?[^)]*\\)", Pattern.CASE_INSENSITIVE)
                .matcher(context).find()) {
            return scaled(raw, "million");
        }
        if (Pattern.compile("\\((?:\\$\\s+)?in billions?[^)]*\\)", Pattern.CASE_INSENSITIVE)
                .matcher(context).find()) {
            return scaled(raw, "billion");
        }
        return scaled(raw, null);
    }

    private static BigDecimal decimal(String raw) {
        return normalize(new BigDecimal(raw));
    }

    private static BigDecimal normalize(BigDecimal value) {
        if (value == null) return null;
        var normalized = value.setScale(Math.min(Math.max(value.scale(), 0), 6), RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }

    private static BigDecimal min(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static BigDecimal max(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private static Bounds ordered(BigDecimal left, BigDecimal right) {
        var normalizedLeft = normalize(left);
        var normalizedRight = normalize(right);
        if (normalizedLeft == null || normalizedRight == null
                || normalizedLeft.compareTo(normalizedRight) <= 0) {
            return new Bounds(normalizedLeft, normalizedRight);
        }
        return new Bounds(normalizedRight, normalizedLeft);
    }

    private static String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String normalizeClause(String text) {
        return text.replace('\u00a0', ' ')
                // SEC HTML-to-text can split a decimal as "3. 5%". Repair only
                // short numeric tokens that are immediately followed by a
                // financial unit, avoiding sentence boundaries such as "2026. 5%".
                .replaceAll(
                        "(?<!\\d)(\\d{1,3})\\.\\s+(\\d{1,3})(?=\\s*(?:%|(?:bps|basis points?|billion|bn|million|mn|[bm])\\b))",
                        "$1.$2"
                )
                .replaceAll("[\\t\\r\\f ]+", " ")
                .replaceAll(" *\\n *", "\\n")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String bounded(String text) {
        var normalized = normalizeClause(text);
        return normalized.length() <= MAX_CLAUSE_CHARACTERS
                ? normalized
                : normalized.substring(0, MAX_CLAUSE_CHARACTERS);
    }

    private record Band(BigDecimal min, BigDecimal max) {
    }

    private record Bounds(BigDecimal min, BigDecimal max) {
    }

    private record TableAmount(String currency, String number, String magnitude, int start, int end) {
    }

    private record AbsoluteTolerance(String number, String magnitude) {
    }
}
