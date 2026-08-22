package io.macrosquare.compatibility.adapter.out.earnings;

import io.macrosquare.compatibility.adapter.out.json.SupplementalApiJsonMapper;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.ArrayValue;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.Document;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.ObjectValue;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.TextPayload;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.TextValue;
import io.macrosquare.compatibility.application.port.out.LoadSupplementalApiPort;
import io.macrosquare.compatibility.application.port.out.LoadEarningsUniversePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live Nasdaq earnings calendar decorator. All unrelated supplemental read
 * models remain delegated to the Spring-owned projection adapter.
 */
public final class NasdaqEarningsSupplementalApiAdapter implements LoadSupplementalApiPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(NasdaqEarningsSupplementalApiAdapter.class);
    private final LoadSupplementalApiPort fallback;
    private final LoadEarningsUniversePort universe;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Executor executor;
    private final AtomicBoolean refreshActive = new AtomicBoolean();
    private volatile CachedCalendar cache;

    public NasdaqEarningsSupplementalApiAdapter(
            LoadSupplementalApiPort fallback,
            LoadEarningsUniversePort universe,
            RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration cacheTtl,
            Executor executor
    ) {
        this.fallback = Objects.requireNonNull(fallback);
        this.universe = Objects.requireNonNull(universe);
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.cacheTtl = Objects.requireNonNull(cacheTtl);
        this.executor = Objects.requireNonNull(executor);
        if (cacheTtl.isZero() || cacheTtl.isNegative()) throw new IllegalArgumentException("cacheTtl must be positive");
    }

    @Override
    public Document loadEarnings() {
        var current = cache;
        var now = clock.instant();
        if (current != null && now.isBefore(current.loadedAt().plus(cacheTtl))) return current.value();
        refreshInBackground();
        // A direct executor (tests) or a very fast provider can finish before this
        // request returns. Prefer that result without waiting on the normal async path.
        current = cache;
        if (current != null && now.isBefore(current.loadedAt().plus(cacheTtl))) return current.value();

        var fallbackValue = fallback.loadEarnings();
        return isCurrentWindow(fallbackValue, LocalDate.now(clock)) ? fallbackValue : emptyCalendar();
    }

    private void refreshInBackground() {
        if (!refreshActive.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    var refreshed = fetch();
                    if (refreshed != null) cache = new CachedCalendar(refreshed, clock.instant());
                } catch (RuntimeException error) {
                    LOGGER.warn("Nasdaq earnings calendar refresh failed; retaining last-valid projection (errorType={})",
                            error.getClass().getSimpleName());
                } finally {
                    refreshActive.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            refreshActive.set(false);
        }
    }

    private Document fetch() {
        var events = new LinkedHashMap<String, EarningsEvent>();
        var trackedTickers = universe.loadTickers();
        var today = LocalDate.now(clock);
        var successfulDates = 0;
        for (var offset = 0; offset < 5; offset++) {
            var date = today.plusDays(offset);
            try {
                var root = restClient.get()
                        .uri(uri -> uri.path("/api/calendar/earnings").queryParam("date", date).build())
                        .retrieve()
                        .body(tools.jackson.databind.JsonNode.class);
                var rows = root == null || root.get("data") == null ? null : root.get("data").get("rows");
                if (rows != null && rows.isArray()) {
                    for (var row : rows) {
                        var symbol = text(row, "symbol");
                        if (!trackedTickers.contains(symbol)) continue;
                        var event = new EarningsEvent(
                                symbol,
                                defaultText(text(row, "name"), symbol),
                                date.toString(),
                                defaultText(text(row, "time"), "TBD")
                        );
                        events.put(symbol + ':' + date, event);
                    }
                }
                successfulDates++;
            } catch (RuntimeException error) {
                LOGGER.warn("Nasdaq earnings date refresh failed (date={}, errorType={})",
                        date, error.getClass().getSimpleName());
            }
        }
        // A successfully fetched window with no tracked-company earnings is a valid,
        // current empty calendar. Returning null here resurrected a stale migration
        // projection and displayed already-passed earnings dates after every restart.
        if (successfulDates == 0) return null;
        var root = objectMapper.createObjectNode();
        var array = root.putArray("earnings");
        events.values().forEach(event -> {
            var item = array.addObject();
            item.put("ticker", event.ticker());
            item.put("company", event.company());
            item.put("date", event.date());
            item.put("time", event.time());
        });
        root.put("count", events.size());
        return SupplementalApiJsonMapper.document(root, SupplementalApiJsonMapper.Contract.EARNINGS);
    }

    private Document emptyCalendar() {
        var root = objectMapper.createObjectNode();
        root.putArray("earnings");
        root.put("count", 0);
        return SupplementalApiJsonMapper.document(root, SupplementalApiJsonMapper.Contract.EARNINGS);
    }

    private static boolean isCurrentWindow(Document value, LocalDate today) {
        var field = value.root().fields().get("earnings");
        if (!(field instanceof ArrayValue events)) return false;
        var lastDay = today.plusDays(4);
        for (var item : events.values()) {
            if (!(item instanceof ObjectValue event)) return false;
            var dateValue = event.fields().get("date");
            if (!(dateValue instanceof TextValue dateText)) return false;
            try {
                var date = LocalDate.parse(dateText.value());
                if (date.isBefore(today) || date.isAfter(lastDay)) return false;
            } catch (java.time.format.DateTimeParseException ignored) {
                return false;
            }
        }
        return true;
    }

    private static String text(tools.jackson.databind.JsonNode root, String field) {
        var value = root == null ? null : root.get(field);
        return value != null && value.isString() ? value.stringValue().trim() : "";
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override public Document loadSmartMoney() { return fallback.loadSmartMoney(); }
    @Override public Document loadSectorBacktest(int years) { return fallback.loadSectorBacktest(years); }
    @Override public Document loadBottleneckThemes() { return fallback.loadBottleneckThemes(); }
    @Override public Document loadBottleneckTheme(String id) { return fallback.loadBottleneckTheme(id); }
    @Override public Document loadCompanies(String sort, String query, String themeId, String sectorId, int page, int pageSize) {
        return fallback.loadCompanies(sort, query, themeId, sectorId, page, pageSize);
    }
    @Override public Document loadHighlights() { return fallback.loadHighlights(); }
    @Override public Document loadCorrelation(int lookback, List<String> keys) { return fallback.loadCorrelation(lookback, keys); }
    @Override public Document loadDomesticReports() { return fallback.loadDomesticReports(); }
    @Override public Document loadWeeklyReportJson() { return fallback.loadWeeklyReportJson(); }
    @Override public TextPayload loadWeeklyReportText() { return fallback.loadWeeklyReportText(); }
    @Override public Document loadBacktestSummary() { return fallback.loadBacktestSummary(); }
    @Override public Document loadBacktestPortfolio(int years) { return fallback.loadBacktestPortfolio(years); }
    @Override public Document loadBacktestUserPlan(int years) { return fallback.loadBacktestUserPlan(years); }

    private record CachedCalendar(Document value, Instant loadedAt) { }
    private record EarningsEvent(String ticker, String company, String date, String time) { }
}
