package io.macrosquare.company.adapter.out.yahoo;

import io.macrosquare.company.application.model.CompanyMarketCapitalization;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;

final class YahooCompanyMarketCapitalizationMapper {

    private YahooCompanyMarketCapitalizationMapper() {
    }

    static CompanyMarketCapitalization map(
            JsonNode root,
            String requestedTicker,
            String expectedSourceSymbol
    ) {
        var timeseries = root == null ? null : root.get("timeseries");
        var results = timeseries == null ? null : timeseries.get("result");
        if (results == null || !results.isArray()) {
            throw new IllegalArgumentException("Yahoo market-cap response has no result array");
        }
        CompanyMarketCapitalization latest = null;
        for (var result : results) {
            requireMatchingSymbol(result.get("meta"), expectedSourceSymbol);
            var values = result.get("trailingMarketCap");
            if (values == null || !values.isArray()) continue;
            for (var item : values) {
                var dateNode = item.get("asOfDate");
                var reported = item.get("reportedValue");
                var raw = reported == null ? null : reported.get("raw");
                if (dateNode == null || !dateNode.isString() || raw == null || !raw.isNumber()) continue;
                var value = raw.doubleValue();
                if (!Double.isFinite(value) || value <= 0) continue;
                final LocalDate date;
                try {
                    date = LocalDate.parse(dateNode.stringValue());
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (latest == null || date.isAfter(latest.date())) {
                    latest = new CompanyMarketCapitalization(requestedTicker, value, date);
                }
            }
        }
        if (latest == null) throw new IllegalArgumentException("Yahoo market-cap response has no usable observation");
        return latest;
    }

    private static void requireMatchingSymbol(JsonNode meta, String expectedSourceSymbol) {
        var symbols = meta == null ? null : meta.get("symbol");
        var expected = normalize(expectedSourceSymbol);
        var matches = false;
        if (symbols != null && symbols.isArray()) {
            for (var symbol : symbols) {
                if (symbol != null && symbol.isString() && expected.equals(normalize(symbol.stringValue()))) {
                    matches = true;
                    break;
                }
            }
        } else if (symbols != null && symbols.isString()) {
            matches = expected.equals(normalize(symbols.stringValue()));
        }
        if (!matches) throw new IllegalArgumentException("Yahoo market-cap response symbol mismatch");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT).replace('.', '-');
    }
}
