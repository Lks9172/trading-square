package io.macrosquare.compatibility.adapter.out.persistence;

import io.macrosquare.compatibility.adapter.out.json.SupplementalApiJsonMapper;
import io.macrosquare.compatibility.adapter.out.json.SupplementalApiJsonMapper.Contract;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.Document;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.TextPayload;
import io.macrosquare.compatibility.application.port.in.SupplementalResourceNotFoundException;
import io.macrosquare.compatibility.application.port.out.LoadSupplementalApiPort;
import io.macrosquare.compatibility.application.port.out.SupplementalApiUnavailableException;
import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;
import io.macrosquare.company.application.model.CompanyResearchSummarySnapshot;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;
import io.macrosquare.market.domain.observation.MarketInputFreshnessPolicy;
import io.macrosquare.shared.adapter.out.catalog.CurrentResearchUniverseTickerRegistry;
import io.macrosquare.shared.adapter.out.catalog.CurrentResearchUniverseTickerRegistry.SectorReplacement;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Spring-owned compatibility projections used after the Node process is retired.
 *
 * <p>Every source document is captured atomically before cutover. Company list
 * filtering, ranking and pagination are performed here so arbitrary UI queries
 * do not require a running legacy process.</p>
 */
public final class FileSupplementalApiAdapter implements LoadSupplementalApiPort {

    private static final int MAX_TEXT_BYTES = 2_000_000;
    private static final String SMART_MONEY_FRESHNESS_KEY = "SMART_MONEY_SCORE";
    private static final long DOMESTIC_REPORT_MAX_AGE_DAYS = 7;
    private static final Duration COMPANY_SUMMARY_MAX_AGE = Duration.ofHours(2);
    private static final MarketInputFreshnessPolicy MARKET_FRESHNESS = new MarketInputFreshnessPolicy();
    private final JsonEnvelopeStore store;
    private final ObjectMapper objectMapper;
    private final CompanyResearchSummaryRepository companySummaries;
    private final Clock clock;

    public FileSupplementalApiAdapter(
            JsonEnvelopeStore store,
            ObjectMapper objectMapper
    ) {
        this(store, objectMapper, null, Clock.systemUTC());
    }

    public FileSupplementalApiAdapter(
            JsonEnvelopeStore store,
            ObjectMapper objectMapper,
            CompanyResearchSummaryRepository companySummaries,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.companySummaries = companySummaries;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Document loadSmartMoney() {
        var captured = required("spring_smart-money_v1.json", "smart money");
        if (!(captured.deepCopy() instanceof ObjectNode root)) {
            throw new IllegalArgumentException("smart money must be an object");
        }
        var insider = root.get("insider");
        var observedOn = insider == null ? null : localDate(text(insider, "lastUpdated"));
        var asOf = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        var eligible = MARKET_FRESHNESS.usableRaw(SMART_MONEY_FRESHNESS_KEY, observedOn, asOf);
        var freshness = root.putObject("freshness");
        if (observedOn == null) freshness.putNull("observedOn");
        else freshness.put("observedOn", observedOn.toString());
        if (observedOn == null || observedOn.isAfter(asOf)) freshness.putNull("ageDays");
        else freshness.put("ageDays", ChronoUnit.DAYS.between(observedOn, asOf));
        freshness.put("maximumAgeDays", MARKET_FRESHNESS.maximumRawAgeDays(SMART_MONEY_FRESHNESS_KEY));
        freshness.put("eligibleForDecisions", eligible);
        freshness.put("status", eligible ? "CURRENT" : observedOn == null ? "UNKNOWN" : "STALE");
        freshness.put("explanation", eligible
                ? "현재 거시 국면 점수에 반영 가능한 최신 관측입니다."
                : "과거 관측은 화면에 보존하지만 현재 거시 국면 점수에는 반영하지 않습니다.");
        return SupplementalApiJsonMapper.document(root, Contract.SMART_MONEY);
    }

    @Override
    public Document loadSectorBacktest(int years) {
        var captured = required("route_research-sectors-backtest_v1_" + years + ".json",
                "sector backtest");
        if (!(captured.deepCopy() instanceof ObjectNode root)) {
            throw new IllegalArgumentException("sector backtest must be an object");
        }
        var methodology = root.get("methodology");
        if (methodology instanceof ObjectNode object) {
            object.put("compatibility", "LEGACY_REFERENCE_ONLY");
            object.put("liveMethodologyMatched", false);
            object.put("warning",
                    "이 결과는 2025-12-31까지의 구형 월간 연구 프레임입니다. "
                            + "현재 실시간 상대강도·확률가중 거시 계산의 성과로 해석하지 않습니다.");
        }
        return SupplementalApiJsonMapper.document(root, Contract.SECTOR_BACKTEST);
    }

    @Override
    public Document loadBottleneckThemes() {
        return document("spring_bottleneck-themes_v1.json", Contract.BOTTLENECK_CATALOG,
                "bottleneck catalog");
    }

    @Override
    public Document loadBottleneckTheme(String id) {
        return document("spring_bottleneck-theme_v1_" + id + ".json", Contract.BOTTLENECK_DETAIL,
                "bottleneck theme " + id);
    }

    @Override
    public Document loadCompanies(
            String sort,
            String query,
            String themeId,
            String sectorId,
            int page,
            int pageSize
    ) {
        var catalog = required("spring_research-companies-catalog_v1.json", "company catalog");
        var source = requiredArray(catalog, "items");
        var current = companySummaries == null ? java.util.Map.<String, CompanyResearchSummarySnapshot>of()
                : companySummaries.findAll();
        var normalizedQuery = query.toUpperCase(Locale.ROOT);
        var filtered = new ArrayList<JsonNode>();
        var seenTickers = new HashSet<String>();
        var sources = new ArrayList<JsonNode>();
        for (var captured : source) {
            var ticker = canonicalTicker(text(captured, "ticker"));
            if (CurrentResearchUniverseTickerRegistry.retired(ticker)) {
                CurrentResearchUniverseTickerRegistry.replacementForRetired(ticker)
                        .map(this::replacementCatalogItem)
                        .ifPresent(sources::add);
                continue;
            }
            sources.add(captured);
        }
        for (var captured : sources) {
            var ticker = canonicalTicker(text(captured, "ticker"));
            var item = currentItem(captured, current);
            if (!themeId.isEmpty() && !contains(requiredArray(item, "themeIds"), themeId, false)) continue;
            if (!sectorId.isEmpty() && !contains(requiredArray(item, "sectorIds"), sectorId, false)) continue;
            if (!normalizedQuery.isEmpty()
                    && !text(item, "ticker").toUpperCase(Locale.ROOT).contains(normalizedQuery)
                    && !text(item, "name").toUpperCase(Locale.ROOT).contains(normalizedQuery)
                    && !contains(requiredArray(item, "themeNames"), normalizedQuery, true)
                    && !contains(requiredArray(item, "sectorNames"), normalizedQuery, true)) continue;
            if (!seenTickers.add(ticker)) continue;
            filtered.add(item);
        }
        filtered.sort(Comparator.comparingDouble((JsonNode item) -> rankValue(item, sort)).reversed()
                .thenComparing(item -> text(item, "ticker")));

        var total = filtered.size();
        var totalPages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        var normalizedPage = Math.min(page, totalPages);
        var start = Math.min(total, (normalizedPage - 1) * pageSize);
        var end = Math.min(total, start + pageSize);

        var result = objectMapper.createObjectNode();
        var items = result.putArray("items");
        filtered.subList(start, end).forEach(items::add);
        result.put("sortKey", sort);
        result.put("total", total);
        result.put("page", normalizedPage);
        result.put("pageSize", pageSize);
        result.put("totalPages", totalPages);
        result.set("themes", requiredArray(catalog, "themes"));
        result.set("sectors", requiredArray(catalog, "sectors"));
        return SupplementalApiJsonMapper.document(result, Contract.COMPANIES);
    }

    private ObjectNode replacementCatalogItem(SectorReplacement replacement) {
        var item = objectMapper.createObjectNode();
        item.put("ticker", replacement.ticker());
        item.put("name", replacement.name());
        item.putNull("peerGroup");
        item.putArray("themeIds");
        item.putArray("themeNames");
        item.putArray("sectorIds").add(replacement.sectorId());
        item.putArray("sectorNames").add(replacement.sectorLabel());
        return item;
    }

    private JsonNode currentItem(
            JsonNode source,
            java.util.Map<String, CompanyResearchSummarySnapshot> summaries
    ) {
        if (!(source.deepCopy() instanceof ObjectNode item)) return source;
        var ticker = canonicalTicker(text(source, "ticker"));
        item.put("ticker", ticker);
        if (companySummaries == null) return item;
        var summary = summaries.get(ticker);
        if (summary == null) {
            clearCompanyMetrics(item);
            item.put("metricsStatus", "PENDING");
            item.putNull("metricsUpdatedAt");
            item.put("error", "현재 Spring 기업 지표 계산 대기 중");
            return item;
        }
        var now = clock.instant();
        var stale = summary.updatedAt().plus(COMPANY_SUMMARY_MAX_AGE).isBefore(now);
        var comparable = summary.scoreComparableAt(now, COMPANY_SUMMARY_MAX_AGE);
        var priceSignalsCurrent = summary.priceSignalsCurrentAt(now, COMPANY_SUMMARY_MAX_AGE);
        put(item, "marketCap", comparable ? summary.marketCap() : null);
        put(item, "totalScore", comparable ? summary.totalScore() : null);
        put(item, "growthScore", comparable ? summary.growthScore() : null);
        put(item, "qualityScore", comparable ? summary.qualityScore() : null);
        put(item, "valuationScore", comparable ? summary.valuationScore() : null);
        put(item, "balanceSheetScore", comparable ? summary.balanceSheetScore() : null);
        put(item, "buyScore", comparable ? summary.buyScore() : null);
        put(item, "buyLabel", comparable ? summary.buyLabel() : null);
        put(item, "appealScore", comparable ? summary.appealScore() : null);
        put(item, "crowdingScore", comparable ? summary.crowdingScore() : null);
        put(item, "revenueGrowthYoY", comparable ? summary.revenueGrowthYoY() : null);
        put(item, "operatingMargin", comparable ? summary.operatingMargin() : null);
        put(item, "evToSales", comparable ? summary.evToSales() : null);
        put(item, "priceBottomScore", priceSignalsCurrent ? summary.priceBottomScore() : null);
        put(item, "volumeConfirmationScore", priceSignalsCurrent ? summary.volumeConfirmationScore() : null);
        put(item, "bottomFailureRiskScore", priceSignalsCurrent ? summary.failureRiskScore() : null);
        put(item, "confirmedBottomScore", priceSignalsCurrent ? summary.confirmedBottomScore() : null);
        put(item, "confirmedBottomState", priceSignalsCurrent ? bottomState(summary.confirmedBottomState()) : null);
        put(item, "fundamentalsAsOf", summary.fundamentalsAsOf() == null
                ? null : summary.fundamentalsAsOf().toString());
        put(item, "valuationBasis", summary.valuationBasis());
        item.put("valuationEligible", summary.valuationEligible());
        var warnings = item.putArray("valuationWarnings");
        summary.valuationWarnings().forEach(warnings::add);
        put(item, "fundamentalsStatus", summary.fundamentalsStatus());
        put(item, "latestPeriodicReportDate", summary.latestPeriodicReportDate() == null
                ? null : summary.latestPeriodicReportDate().toString());
        put(item, "latestPeriodicFilingDate", summary.latestPeriodicFilingDate() == null
                ? null : summary.latestPeriodicFilingDate().toString());
        put(item, "latestPeriodicForm", summary.latestPeriodicForm());
        put(item, "fundamentalsLagDays", summary.fundamentalsLagDays());
        var scoreWarnings = item.putArray("scoreWarnings");
        summary.scoreWarnings().forEach(scoreWarnings::add);
        item.put("metricsUpdatedAt", summary.updatedAt().toString());
        var partial = !comparable;
        item.put("metricsStatus", stale ? "STALE" : partial ? "PARTIAL" : "CURRENT");
        if (partial) {
            item.put("error", summary.scoreWarnings().isEmpty()
                    ? "핵심 재무 지표가 현재 SEC 표준 모델로 비교 불가하여 점수를 보류함"
                    : summary.scoreWarnings().getFirst());
        } else {
            item.remove("error");
        }
        return item;
    }

    private static String canonicalTicker(String ticker) {
        return CurrentResearchUniverseTickerRegistry.canonicalTicker(ticker);
    }

    private static LocalDate localDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static void clearCurrentMetric(ObjectNode item, String field) {
        item.putNull(field);
    }

    private static void clearCompanyMetrics(ObjectNode item) {
        for (var field : List.of(
                "totalScore", "buyScore", "growthScore", "qualityScore", "valuationScore",
                "balanceSheetScore", "buyLabel", "appealScore", "crowdingScore",
                "revenueGrowthYoY", "operatingMargin", "evToSales", "marketCap",
                "priceBottomScore", "volumeConfirmationScore", "bottomFailureRiskScore",
                "confirmedBottomScore", "confirmedBottomState")) {
            clearCurrentMetric(item, field);
        }
    }

    private static void put(ObjectNode item, String field, Number value) {
        if (value == null) item.putNull(field);
        else item.put(field, value.doubleValue());
    }

    private static void put(ObjectNode item, String field, String value) {
        if (value == null || value.isBlank()) item.putNull(field);
        else item.put(field, value);
    }

    private static String bottomState(String value) {
        if (value == null) return null;
        return switch (value) {
            case "CONVICTION" -> "확신";
            case "CANDIDATE" -> "후보";
            case "UNMET" -> "미충족";
            default -> value;
        };
    }

    @Override
    public Document loadHighlights() {
        return document("spring_research-highlights_v1.json", Contract.HIGHLIGHTS, "research highlights");
    }

    @Override
    public Document loadEarnings() {
        return document("spring_earnings_v1.json", Contract.EARNINGS, "earnings");
    }

    @Override
    public Document loadCorrelation(int lookback, List<String> keys) {
        if (!keys.isEmpty()) {
            throw new SupplementalApiUnavailableException(
                    "Custom correlation keys require the native market-history calculation");
        }
        var captured = List.of(30, 60, 120, 250).stream()
                .min(Comparator.comparingInt(value -> Math.abs(value - lookback)))
                .orElse(60);
        return document("spring_correlation_v1_" + captured + ".json", Contract.CORRELATION, "correlation");
    }

    @Override
    public Document loadDomesticReports() {
        var captured = required("spring_domestic-reports_v1.json", "domestic reports");
        if (!(captured.deepCopy() instanceof ObjectNode root)
                || !(root.get("data") instanceof ObjectNode data)) {
            throw new IllegalArgumentException("domestic reports data must be an object");
        }
        var asOf = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate newest = null;
        for (var entry : new ArrayList<>(data.properties())) {
            if (!(entry.getValue() instanceof ObjectNode report)) continue;
            var observedOn = localDate(text(report, "latestDate"));
            if (observedOn == null || observedOn.isAfter(asOf)) {
                report.putNull("daysAgo");
                continue;
            }
            report.put("daysAgo", ChronoUnit.DAYS.between(observedOn, asOf));
            if (newest == null || observedOn.isAfter(newest)) newest = observedOn;
        }
        var freshness = data.putObject("freshness");
        if (newest == null) freshness.putNull("observedOn");
        else freshness.put("observedOn", newest.toString());
        var ageDays = newest == null ? null : ChronoUnit.DAYS.between(newest, asOf);
        if (ageDays == null) freshness.putNull("ageDays");
        else freshness.put("ageDays", ageDays);
        freshness.put("maximumAgeDays", DOMESTIC_REPORT_MAX_AGE_DAYS);
        freshness.put("status", ageDays == null ? "UNKNOWN"
                : ageDays <= DOMESTIC_REPORT_MAX_AGE_DAYS ? "CURRENT" : "STALE");
        freshness.put("usedForInvestmentScores", false);
        freshness.put("eligibleForDecisions", false);
        freshness.put("explanation", "국내 리서치 제목 캐시는 참고 표시 전용이며 투자 점수에는 반영하지 않습니다.");
        return SupplementalApiJsonMapper.document(root, Contract.DOMESTIC_REPORTS);
    }

    @Override
    public Document loadWeeklyReportJson() {
        return document("spring_weekly-report_v1.json", Contract.WEEKLY_REPORT, "weekly report");
    }

    @Override
    public TextPayload loadWeeklyReportText() {
        try {
            return new TextPayload(store.findText("spring_weekly-report_v1.txt", MAX_TEXT_BYTES)
                    .orElseThrow(() -> new SupplementalApiUnavailableException(
                            "Persisted weekly report text is unavailable")));
        } catch (SupplementalApiUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new SupplementalApiUnavailableException("Unable to read persisted weekly report text", error);
        }
    }

    @Override
    public Document loadBacktestSummary() {
        return document("spring_backtest-summary_v1.json", Contract.BACKTEST_SUMMARY, "backtest summary");
    }

    @Override
    public Document loadBacktestPortfolio(int years) {
        return document("spring_backtest-portfolio_v1_" + years + ".json", Contract.BACKTEST_PORTFOLIO,
                "portfolio backtest");
    }

    @Override
    public Document loadBacktestUserPlan(int years) {
        return document("spring_backtest-user-plan_v1_" + years + ".json", Contract.BACKTEST_USER_PLAN,
                "user-plan backtest");
    }

    private Document document(String fileName, Contract contract, String label) {
        return SupplementalApiJsonMapper.document(required(fileName, label), contract);
    }

    private JsonNode required(String fileName, String label) {
        try {
            return store.findValue(fileName).orElseThrow(() ->
                    new SupplementalResourceNotFoundException("Persisted " + label + " is unavailable"));
        } catch (SupplementalResourceNotFoundException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new SupplementalApiUnavailableException("Unable to load persisted " + label, error);
        }
    }

    private static JsonNode requiredArray(JsonNode parent, String field) {
        var node = parent == null ? null : parent.get(field);
        if (node == null || !node.isArray()) throw new IllegalArgumentException(field + " must be an array");
        return node;
    }

    private static String text(JsonNode parent, String field) {
        var node = parent == null ? null : parent.get(field);
        return node != null && node.isString() ? node.stringValue() : "";
    }

    private static boolean contains(JsonNode array, String expected, boolean substring) {
        for (var item : array) {
            if (!item.isString()) continue;
            var value = item.stringValue();
            if (substring ? value.toUpperCase(Locale.ROOT).contains(expected) : value.equals(expected)) return true;
        }
        return false;
    }

    private static double rankValue(JsonNode item, String sort) {
        return switch (sort) {
            case "total" -> number(item, "totalScore", -1);
            case "growth" -> number(item, "revenueGrowthYoY", -999);
            case "margin" -> number(item, "operatingMargin", -999);
            case "value" -> -number(item, "evToSales", 999);
            case "appeal" -> number(item, "appealScore", -1);
            default -> number(item, "buyScore", -1);
        };
    }

    private static double number(JsonNode item, String field, double fallback) {
        var value = item.get(field);
        return value != null && value.isNumber() && Double.isFinite(value.asDouble()) ? value.asDouble() : fallback;
    }
}
