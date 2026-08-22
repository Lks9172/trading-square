package io.macrosquare.notification.adapter.out.persistence;

import io.macrosquare.notification.application.port.out.LoadInvestmentCandidatesPort;
import io.macrosquare.notification.domain.BottomCandidateState;
import io.macrosquare.notification.domain.CandidateKind;
import io.macrosquare.notification.domain.InvestmentCandidate;
import io.macrosquare.notification.domain.TechnicalTimingEvidence;
import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Bounded read-only adapter for the handoff projections and complete company universe. */
public final class FileInvestmentCandidateAdapter implements LoadInvestmentCandidatesPort {

    private static final int MAX_COMPANY_FILES = 1000;
    private static final int MAX_CRYPTO_MARKET_AGE_DAYS = 2;
    private static final int MAX_CRYPTO_SUPPORTING_EVIDENCE_AGE_DAYS = 7;
    // CTRA stopped being a publicly tradable security after the 2026 Devon merger.
    // Keep its SEC identity/history elsewhere, but never turn a retained projection
    // into a new-money entry alert.
    private static final Set<String> RETIRED_COMPANY_SYMBOLS = Set.of("CTRA", "EA");

    private final JsonEnvelopeStore store;
    private final Clock clock;

    public FileInvestmentCandidateAdapter(
            JsonEnvelopeStore store
    ) {
        this(store, Clock.systemUTC());
    }

    public FileInvestmentCandidateAdapter(
            JsonEnvelopeStore store,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public List<InvestmentCandidate> loadStartupCandidates() {
        var values = new ArrayList<InvestmentCandidate>();
        appendCached(values, "current-telegram-bottom-company-candidates-v2.json", CandidateKind.COMPANY);
        appendCached(values, "current-telegram-bottom-crypto-candidates-v1.json", CandidateKind.CRYPTO);
        return values.stream()
                .map(this::withCurrentProjection)
                .flatMap(java.util.Optional::stream)
                .filter(FileInvestmentCandidateAdapter::isActive)
                .toList();
    }

    @Override
    public List<InvestmentCandidate> loadScanUniverse() {
        var values = new ArrayList<InvestmentCandidate>();
        store.listValues("company-research-lite-", MAX_COMPANY_FILES).forEach(document ->
                candidate(document.value(), CandidateKind.COMPANY).ifPresent(values::add));
        store.findValue("route_research-crypto_v1.json").ifPresent(root -> {
            var items = root.get("items");
            if (items != null && items.isArray()) {
                for (var item : items) candidate(item, CandidateKind.CRYPTO).ifPresent(values::add);
            }
        });
        return List.copyOf(values);
    }

    private void appendCached(List<InvestmentCandidate> target, String file, CandidateKind kind) {
        store.findValue(file).ifPresent(root -> {
            if (!root.isArray()) return;
            for (var item : root) candidate(item, kind).ifPresent(target::add);
        });
    }

    private java.util.Optional<InvestmentCandidate> withCurrentProjection(InvestmentCandidate value) {
        var file = value.kind() == CandidateKind.COMPANY
                ? "company-research-lite-" + value.symbol().toLowerCase(Locale.ROOT) + ".json"
                : "route_research-crypto-detail_v1_" + value.symbol().toLowerCase(Locale.ROOT) + ".json";
        var current = store.findValue(file).flatMap(root -> candidate(root, value.kind()));
        // A stale/missing crypto detail must never fall back to an old startup cache:
        // crypto entry alerts require the current market and all flow/on-chain series.
        return value.kind() == CandidateKind.CRYPTO ? current : java.util.Optional.of(current.orElse(value));
    }

    private java.util.Optional<InvestmentCandidate> candidate(JsonNode root, CandidateKind expectedKind) {
        try {
            if (!root.isObject()) return java.util.Optional.empty();
            var profile = object(root, "profile");
            var cachedShape = profile == null;
            if (expectedKind == CandidateKind.CRYPTO && !cachedShape && !cryptoDecisionEvidenceIsCurrent(root)) {
                return java.util.Optional.empty();
            }
            var symbol = cachedShape
                    ? text(root, expectedKind == CandidateKind.COMPANY ? "ticker" : "symbol", "")
                    : text(profile, "symbol", text(profile, "ticker", ""));
            if (symbol.isBlank()) return java.util.Optional.empty();
            if (expectedKind == CandidateKind.COMPANY && "MMC".equalsIgnoreCase(symbol)) symbol = "MRSH";
            if (expectedKind == CandidateKind.COMPANY
                    && RETIRED_COMPANY_SYMBOLS.contains(symbol.toUpperCase(Locale.ROOT))) {
                return java.util.Optional.empty();
            }
            var name = cachedShape ? text(root, "name", symbol) : text(profile, "name", symbol);
            var score = cachedShape ? null : object(root, "score");
            var buy = cachedShape ? null : object(root, "buyScore");
            var bottom = cachedShape ? null : object(root, "bottomSignal");
            var confirmed = bottom == null ? null : object(bottom, "confirmedBottom");
            var reversal = cachedShape ? null : object(root, "reversalConfirmation");
            var position = cachedShape ? null : object(root, "positionSizing");
            var sector = cachedShape ? null : object(root, "sectorContext");
            var macdTiming = expectedKind == CandidateKind.COMPANY
                    ? technicalTiming(cachedShape ? object(root, "macdTiming") : object(bottom, "macdMomentum"))
                    : null;

            var state = parseState(cachedShape
                    ? text(root, "confirmedBottomState", "미충족") : text(confirmed, "state", "미충족"));
            var action = cachedShape ? text(root, "action", "")
                    : expectedKind == CandidateKind.CRYPTO
                    ? text(buy, "action", "") : text(position, "action", "");
            var totalScore = cachedShape ? integer(root, "totalScore", 0)
                    : expectedKind == CandidateKind.COMPANY ? integer(score, "totalScore", 0)
                    : cryptoTotal(root);
            var reasons = cachedShape ? texts(root.get("reasons")) : texts(confirmed == null ? null : confirmed.get("reasons"));
            return java.util.Optional.of(new InvestmentCandidate(
                    expectedKind,
                    symbol.toUpperCase(Locale.ROOT),
                    name,
                    cachedShape ? text(root, expectedKind == CandidateKind.COMPANY ? "sectorLabel" : "category", "")
                            : expectedKind == CandidateKind.COMPANY ? text(sector, "label", "") : text(profile, "category", ""),
                    state,
                    cachedShape ? nullableInteger(root, "confirmedBottomScore") : nullableInteger(confirmed, "score"),
                    totalScore,
                    cachedShape ? integer(root, "buyScore", 0) : integer(buy, "buyScore", 0),
                    action,
                    parseDate(cachedShape ? text(root, "signalDate", "") : text(confirmed, "signalDate", "")),
                    cachedShape ? "OFF" : text(reversal, "status", "OFF"),
                    cachedShape ? null : nullableInteger(reversal, "score"),
                    reasons,
                    macdTiming
            ));
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static int cryptoTotal(JsonNode root) {
        var profile = object(root, "profile");
        var bottom = object(root, "bottomUp");
        var moat = object(root, "moat");
        var onchain = object(root, "onchain");
        var supply = object(root, "supplyPressure");
        return (int) Math.round(
                integer(profile, "foundationalScore", 0) * .20
                        + integer(bottom, "networkScore", 0) * .15
                        + integer(bottom, "tokenomicsScore", 0) * .15
                        + integer(bottom, "adoptionScore", 0) * .15
                        + integer(moat, "moatScore", 0) * .10
                        + integer(onchain, "activityScore", 0) * .15
                        + integer(supply, "floatScore", 0) * .10
        );
    }

    private boolean cryptoDecisionEvidenceIsCurrent(JsonNode root) {
        var declaredFreshness = object(root, "freshness");
        if (declaredFreshness != null) {
            var eligible = declaredFreshness.get("eligibleForDecisions");
            if (eligible == null || !eligible.isBoolean() || !eligible.asBoolean()) return false;
        }
        var market = object(root, "market");
        var marketObservedOn = parseDate(text(market, "asOf", ""));
        var supportingObservedOn = oldestLatestSupportingDate(object(root, "trendCharts"));
        var today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return withinAge(marketObservedOn, today, MAX_CRYPTO_MARKET_AGE_DAYS)
                && withinAge(supportingObservedOn, today, MAX_CRYPTO_SUPPORTING_EVIDENCE_AGE_DAYS);
    }

    private static LocalDate oldestLatestSupportingDate(JsonNode charts) {
        if (charts == null) return null;
        var requiredSeries = List.of(
                "btcDominanceProxy30d", "stablecoinMcap30d", "etfNetFlow30d",
                "altSeasonProxy30d", "exchangeNetflowProxy30d"
        );
        var latestDates = new ArrayList<LocalDate>(requiredSeries.size());
        for (var field : requiredSeries) {
            var series = charts.get(field);
            if (series == null || !series.isArray() || series.isEmpty()) return null;
            LocalDate latest = null;
            for (var point : series) {
                var date = parseDate(text(point, "date", ""));
                if (date != null && (latest == null || date.isAfter(latest))) latest = date;
            }
            if (latest == null) return null;
            latestDates.add(latest);
        }
        return latestDates.stream().min(LocalDate::compareTo).orElse(null);
    }

    private static boolean withinAge(LocalDate observedOn, LocalDate today, int maximumAgeDays) {
        if (observedOn == null || observedOn.isAfter(today)) return false;
        return ChronoUnit.DAYS.between(observedOn, today) <= maximumAgeDays;
    }

    private static JsonNode object(JsonNode root, String field) {
        if (root == null) return null;
        var value = root.get(field);
        return value != null && value.isObject() ? value : null;
    }

    private static String text(JsonNode root, String field, String fallback) {
        if (root == null) return fallback;
        var value = root.get(field);
        return value != null && value.isString() ? value.stringValue() : fallback;
    }

    private static int integer(JsonNode root, String field, int fallback) {
        if (root == null) return fallback;
        var value = root.get(field);
        return value != null && value.isNumber() ? value.asInt() : fallback;
    }

    private static Integer nullableInteger(JsonNode root, String field) {
        if (root == null) return null;
        var value = root.get(field);
        return value != null && value.isNumber() ? value.asInt() : null;
    }

    private static List<String> texts(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        var result = new ArrayList<String>();
        for (var item : value) if (item.isString() && !item.stringValue().isBlank()) result.add(item.stringValue());
        return List.copyOf(result);
    }

    private static BottomCandidateState parseState(String value) {
        return switch (value) {
            case "확신" -> BottomCandidateState.CONVICTION;
            case "후보" -> BottomCandidateState.CANDIDATE;
            default -> BottomCandidateState.UNMET;
        };
    }

    private static TechnicalTimingEvidence technicalTiming(JsonNode root) {
        if (root == null) return null;
        var daily = technicalTimeframe(object(root, "daily"));
        var weekly = technicalTimeframe(object(root, "weekly"));
        if (daily == null || weekly == null) return null;
        var provisional = root.get("currentWeekProvisional");
        return new TechnicalTimingEvidence(
                daily,
                weekly,
                provisional != null && provisional.isBoolean() && provisional.asBoolean()
        );
    }

    private static TechnicalTimingEvidence.Timeframe technicalTimeframe(JsonNode root) {
        if (root == null) return null;
        return new TechnicalTimingEvidence.Timeframe(
                parseDate(text(root, "asOf", "")),
                enumValue(TechnicalTimingEvidence.Position.class, text(root, "position", "UNAVAILABLE"),
                        TechnicalTimingEvidence.Position.UNAVAILABLE),
                enumValue(TechnicalTimingEvidence.Cross.class, text(root, "latestCross", "UNAVAILABLE"),
                        TechnicalTimingEvidence.Cross.UNAVAILABLE),
                parseDate(text(root, "crossDate", "")),
                nullableInteger(root, "sessionsSinceCross"),
                enumValue(TechnicalTimingEvidence.Histogram.class, text(root, "histogramState", "UNAVAILABLE"),
                        TechnicalTimingEvidence.Histogram.UNAVAILABLE),
                enumValue(TechnicalTimingEvidence.Divergence.class, text(root, "divergence", "UNAVAILABLE"),
                        TechnicalTimingEvidence.Divergence.UNAVAILABLE),
                parseDate(text(root, "divergenceConfirmedDate", "")),
                nullableInteger(root, "sessionsSinceDivergence"),
                root.get("divergenceActive") != null
                        && root.get("divergenceActive").isBoolean()
                        && root.get("divergenceActive").asBoolean()
        );
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return fallback;
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean isActive(InvestmentCandidate value) {
        return value.kind() != CandidateKind.COMPANY || !RETIRED_COMPANY_SYMBOLS.contains(value.symbol());
    }
}
