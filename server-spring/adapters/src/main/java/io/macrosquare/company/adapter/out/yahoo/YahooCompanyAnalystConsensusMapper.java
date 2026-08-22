package io.macrosquare.company.adapter.out.yahoo;

import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Maps one Yahoo quoteSummary response to the legacy weighted recommendation contract. */
final class YahooCompanyAnalystConsensusMapper {

    private YahooCompanyAnalystConsensusMapper() {
    }

    static CompanyAnalystConsensus map(JsonNode root, String expectedSourceSymbol) {
        var quoteSummary = objectField(root, "quoteSummary");
        var results = field(quoteSummary, "result");
        if (!results.isArray() || results.isEmpty()) return null;
        var result = results.get(0);
        if (result == null || !result.isObject()) return null;
        var price = objectField(result, "price");
        var returnedSymbol = field(price, "symbol");
        if (!returnedSymbol.isString()
                || !normalizeSymbol(expectedSourceSymbol).equals(normalizeSymbol(returnedSymbol.stringValue()))) {
            throw new IllegalArgumentException("Yahoo analyst payload returned a different security");
        }

        var recommendationTrend = objectField(result, "recommendationTrend");
        var trends = field(recommendationTrend, "trend");
        if (!trends.isArray() || trends.isEmpty()) return null;

        JsonNode selected = null;
        for (var trend : trends) {
            if (selected == null) selected = trend;
            var period = trend == null ? null : trend.get("period");
            if (period != null && period.isString() && "0m".equals(period.stringValue())) {
                selected = trend;
                break;
            }
        }
        if (selected == null || !selected.isObject()) return null;

        var strongBuy = nonNegativeInteger(selected, "strongBuy");
        var buy = nonNegativeInteger(selected, "buy");
        var hold = nonNegativeInteger(selected, "hold");
        var sell = nonNegativeInteger(selected, "sell");
        var strongSell = nonNegativeInteger(selected, "strongSell");
        var total = strongBuy + buy + hold + sell + strongSell;
        Double analystScore = total == 0
                ? null
                : round((strongBuy * 2.0 + buy - sell - strongSell * 2.0) / total, 3);

        Double upsidePct = null;
        var financialData = result.get("financialData");
        if (financialData != null && financialData.isObject()) {
            var targetMean = rawNumber(financialData.get("targetMeanPrice"));
            var currentPrice = rawNumber(financialData.get("currentPrice"));
            if (targetMean != null && targetMean > 0 && currentPrice != null && currentPrice > 0
                    && !splitLikePriceBasisMismatch(targetMean, currentPrice)) {
                upsidePct = round(((targetMean - currentPrice) / currentPrice) * 100.0, 2);
            }
        }
        var epsTrend = selectEpsTrend(result.get("earningsTrend"));
        var currentEps = epsTrend == null ? null : rawNumber(epsTrend.get("current"));
        var revision7d = epsTrend == null ? null
                : revisionPct(currentEps, rawNumber(epsTrend.get("7daysAgo")));
        var revision30d = epsTrend == null ? null
                : revisionPct(currentEps, rawNumber(epsTrend.get("30daysAgo")));
        var revision90d = epsTrend == null ? null
                : revisionPct(currentEps, rawNumber(epsTrend.get("90daysAgo")));
        return new CompanyAnalystConsensus(
                analystScore, upsidePct, revision7d, revision30d, revision90d);
    }

    private static JsonNode selectEpsTrend(JsonNode earningsTrend) {
        if (earningsTrend == null || !earningsTrend.isObject()) return null;
        var trends = earningsTrend.get("trend");
        if (trends == null || !trends.isArray()) return null;
        JsonNode quarterlyFallback = null;
        for (var trend : trends) {
            if (trend == null || !trend.isObject()) continue;
            var period = trend.get("period");
            var epsTrend = trend.get("epsTrend");
            if (period == null || !period.isString() || epsTrend == null || !epsTrend.isObject()) continue;
            if ("0y".equals(period.stringValue())) return epsTrend;
            if (quarterlyFallback == null && "0q".equals(period.stringValue())) {
                quarterlyFallback = epsTrend;
            }
        }
        return quarterlyFallback;
    }

    private static Double revisionPct(Double current, Double prior) {
        if (current == null || prior == null || Math.abs(prior) < 0.01) return null;
        // Crossing zero is a regime change for which a percentage revision is
        // not economically interpretable. Leave it missing instead of emitting
        // a spectacular but misleading number.
        if (Math.signum(current) != Math.signum(prior)) return null;
        return round(((current - prior) / Math.abs(prior)) * 100.0, 2);
    }

    private static boolean splitLikePriceBasisMismatch(double target, double current) {
        var observed = Math.max(target / current, current / target);
        for (var factor : new double[]{3, 4, 5, 10, 20}) {
            if (Math.abs(observed / factor - 1) <= 0.04) return true;
        }
        return false;
    }

    private static JsonNode objectField(JsonNode parent, String field) {
        var value = field(parent, field);
        if (!value.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return value;
    }

    private static JsonNode field(JsonNode parent, String field) {
        if (parent == null || !parent.isObject() || !parent.has(field)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return parent.get(field);
    }

    private static int nonNegativeInteger(JsonNode parent, String field) {
        var value = field(parent, field);
        if (!value.isNumber()) throw new IllegalArgumentException(field + " must be numeric");
        var number = value.asDouble();
        if (number != Math.rint(number) || number < 0 || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must be a non-negative integer");
        }
        return (int) number;
    }

    private static Double rawNumber(JsonNode value) {
        if (value == null || value.isNull()) return null;
        var raw = value.isObject() ? value.get("raw") : value;
        if (raw == null || !raw.isNumber()) return null;
        var number = raw.asDouble();
        return Double.isFinite(number) ? number : null;
    }

    private static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static String normalizeSymbol(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }
}
