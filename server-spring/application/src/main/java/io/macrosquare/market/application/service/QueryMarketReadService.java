package io.macrosquare.market.application.service;

import io.macrosquare.market.application.model.MarketReadModels.Document;
import io.macrosquare.market.application.port.in.QueryMarketReadUseCase;
import io.macrosquare.market.application.port.out.LoadMarketReadPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class QueryMarketReadService implements QueryMarketReadUseCase {

    static final int MAX_HISTORY_TOKEN_LENGTH = 128;
    static final int MAX_SERIES_KEYS = 32;
    static final int MAX_SERIES_KEY_LENGTH = 160;
    static final int MAX_RANGE_TOKEN_LENGTH = 16;
    private static final Pattern SAFE_HISTORY_TOKEN = Pattern.compile("^[A-Za-z0-9._-]+$");

    private final LoadMarketReadPort marketReadPort;

    public QueryMarketReadService(LoadMarketReadPort marketReadPort) {
        this.marketReadPort = Objects.requireNonNull(marketReadPort);
    }

    @Override
    public Document latestSnapshot() {
        return marketReadPort.loadLatestSnapshot();
    }

    @Override
    public Document historyCoverage() {
        return marketReadPort.loadHistoryCoverage();
    }

    @Override
    public Document history(String source, String key) {
        return marketReadPort.loadHistory(normalizeHistoryToken(source, "source"), normalizeHistoryToken(key, "key"));
    }

    @Override
    public Document historySeries(List<String> keyParameters, String range, String interval) {
        var keys = parseKeys(keyParameters);
        return marketReadPort.loadHistorySeries(
                keys,
                legacyDefault(range, "1Y", "range"),
                legacyDefault(interval, "1D", "interval")
        );
    }

    private static String normalizeHistoryToken(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        var normalized = value.trim();
        if (normalized.equals(".") || normalized.equals("..")
                || normalized.length() > MAX_HISTORY_TOKEN_LENGTH
                || !SAFE_HISTORY_TOKEN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return normalized;
    }

    private static List<String> parseKeys(List<String> keyParameters) {
        if (keyParameters == null || keyParameters.isEmpty()) return List.of();
        var keys = new ArrayList<String>();
        for (var parameter : keyParameters) {
            if (parameter == null) continue;
            for (var rawKey : parameter.split(",", -1)) {
                var key = rawKey.trim();
                if (key.isEmpty()) continue;
                if (key.length() > MAX_SERIES_KEY_LENGTH || key.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("history series key is too long or contains control characters");
                }
                keys.add(key);
                if (keys.size() > MAX_SERIES_KEYS) {
                    throw new IllegalArgumentException("history series supports at most " + MAX_SERIES_KEYS + " keys");
                }
            }
        }
        return List.copyOf(keys);
    }

    private static String legacyDefault(String value, String fallback, String field) {
        var normalized = value == null || value.isEmpty() ? fallback : value;
        if (normalized.length() > MAX_RANGE_TOKEN_LENGTH || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is too long or contains control characters");
        }
        return normalized;
    }
}
