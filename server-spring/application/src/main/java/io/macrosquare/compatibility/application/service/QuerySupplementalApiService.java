package io.macrosquare.compatibility.application.service;

import io.macrosquare.compatibility.application.model.SupplementalApiModels.Document;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.TextPayload;
import io.macrosquare.compatibility.application.port.in.QuerySupplementalApiUseCase;
import io.macrosquare.compatibility.application.port.out.LoadSupplementalApiPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class QuerySupplementalApiService implements QuerySupplementalApiUseCase {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final int MAX_KEYS = 32;
    private final LoadSupplementalApiPort port;

    public QuerySupplementalApiService(LoadSupplementalApiPort port) {
        this.port = Objects.requireNonNull(port);
    }

    @Override
    public Document smartMoney() {
        return port.loadSmartMoney();
    }

    @Override
    public Document sectorBacktest(String years) {
        return port.loadSectorBacktest(parseBoundedInt(years, 5, 3, 5));
    }

    @Override
    public Document bottleneckThemes() {
        return port.loadBottleneckThemes();
    }

    @Override
    public Document bottleneckTheme(String id) {
        return port.loadBottleneckTheme(safeId(id, "id"));
    }

    @Override
    public Document companies(String sort, String query, String themeId, String sectorId, String page, String pageSize) {
        var normalizedSort = boundedText(defaultIfBlank(sort, "buy"), 32, "sort");
        var normalizedQuery = boundedText(Objects.toString(query, "").trim().toUpperCase(), 128, "q");
        var normalizedTheme = optionalSafeId(themeId, "themeId");
        var normalizedSector = optionalSafeId(sectorId, "sectorId");
        return port.loadCompanies(
                normalizedSort,
                normalizedQuery,
                normalizedTheme,
                normalizedSector,
                parseBoundedInt(page, 1, 1, 100_000),
                parseBoundedInt(pageSize, 20, 10, 100)
        );
    }

    @Override
    public Document highlights() {
        return port.loadHighlights();
    }

    @Override
    public Document earnings() {
        return port.loadEarnings();
    }

    @Override
    public Document correlation(String lookback, List<String> keyParameters) {
        return port.loadCorrelation(parseBoundedInt(lookback, 60, 10, 500), parseKeys(keyParameters));
    }

    @Override
    public Document domesticReports() {
        return port.loadDomesticReports();
    }

    @Override
    public Document weeklyReportJson() {
        return port.loadWeeklyReportJson();
    }

    @Override
    public TextPayload weeklyReportText() {
        return port.loadWeeklyReportText();
    }

    @Override
    public Document backtestSummary() {
        return port.loadBacktestSummary();
    }

    @Override
    public Document backtestPortfolio(String years) {
        return port.loadBacktestPortfolio(parseBoundedInt(years, 3, 1, 5));
    }

    @Override
    public Document backtestUserPlan(String years) {
        return port.loadBacktestUserPlan(parseBoundedInt(years, 3, 1, 5));
    }

    private static List<String> parseKeys(List<String> parameters) {
        if (parameters == null || parameters.isEmpty()) return List.of();
        var result = new ArrayList<String>();
        for (var parameter : parameters) {
            if (parameter == null) continue;
            for (var token : parameter.split(",", -1)) {
                var key = token.trim();
                if (key.isEmpty()) continue;
                result.add(boundedText(key, 128, "correlation key"));
                if (result.size() > MAX_KEYS) throw new IllegalArgumentException("at most 32 correlation keys are supported");
            }
        }
        return List.copyOf(result);
    }

    private static String optionalSafeId(String value, String field) {
        return value == null || value.isBlank() ? "" : safeId(value, field);
    }

    private static String safeId(String value, String field) {
        var normalized = boundedText(value == null ? "" : value.trim(), 128, field);
        if (normalized.isEmpty() || normalized.equals(".") || normalized.equals("..") || !SAFE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return normalized;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String boundedText(String value, int maximum, String field) {
        if (value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is too long or contains control characters");
        }
        return value;
    }

    private static int parseBoundedInt(String value, int fallback, int minimum, int maximum) {
        int parsed;
        try {
            parsed = value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            parsed = fallback;
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }
}
