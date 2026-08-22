package io.macrosquare.market.adapter.out.fred;

import io.macrosquare.market.application.model.MarketCollectionBatch;
import io.macrosquare.market.application.port.out.CollectMarketObservationsPort;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.LockSupport;

/**
 * Direct FRED collector with a small idempotent history backfill.
 *
 * <p>The last ten provider observations are returned so newly introduced
 * direction indicators do not need to wait several weekly or quarterly release
 * cycles before they can compare a current value with its prior period.</p>
 */
public final class FredMarketObservationAdapter implements CollectMarketObservationsPort {

    private static final int BACKFILL_LIMIT = 10;
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);
    public static final Map<String, String> SERIES = series();

    private final RestClient restClient;
    private final Clock clock;
    private final String apiKey;
    private final Executor executor;
    private final Map<String, String> series;
    private final Duration retryDelay;

    public FredMarketObservationAdapter(
            RestClient restClient,
            Clock clock,
            String apiKey,
            Executor executor
    ) {
        this(restClient, clock, apiKey, executor, SERIES, RETRY_DELAY);
    }

    FredMarketObservationAdapter(
            RestClient restClient,
            Clock clock,
            String apiKey,
            Executor executor,
            Map<String, String> series
    ) {
        this(restClient, clock, apiKey, executor, series, RETRY_DELAY);
    }

    FredMarketObservationAdapter(
            RestClient restClient,
            Clock clock,
            String apiKey,
            Executor executor,
            Map<String, String> series,
            Duration retryDelay
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.clock = Objects.requireNonNull(clock);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.executor = Objects.requireNonNull(executor);
        this.series = Map.copyOf(Objects.requireNonNull(series));
        this.retryDelay = Objects.requireNonNull(retryDelay);
        if (this.series.isEmpty()) throw new IllegalArgumentException("series must not be empty");
        if (this.retryDelay.isNegative()) throw new IllegalArgumentException("retryDelay must not be negative");
    }

    @Override
    public MarketDataSource source() {
        return MarketDataSource.FRED;
    }

    @Override
    public MarketCollectionBatch collect() {
        var startedAt = clock.instant();
        if (apiKey.isEmpty()) {
            return new MarketCollectionBatch(source(), startedAt, clock.instant(), List.of(),
                    List.of(new MarketCollectionBatch.Failure("FRED", "API key is not configured")));
        }
        var tasks = series.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(
                        () -> fetchWithRetry(entry.getKey(), entry.getValue()), executor))
                .toList();
        var observations = new ArrayList<MarketObservation>();
        var failures = new ArrayList<MarketCollectionBatch.Failure>();
        for (var task : tasks) {
            var result = task.join();
            if (!result.observations().isEmpty()) observations.addAll(result.observations());
            else failures.add(result.failure());
        }
        return new MarketCollectionBatch(source(), startedAt, clock.instant(), observations, failures);
    }

    private Result fetchWithRetry(String key, String seriesId) {
        Result result = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            result = fetch(key, seriesId);
            if (!result.observations().isEmpty() || !result.retryable() || attempt == MAX_ATTEMPTS) {
                return attempt == 1 || !result.observations().isEmpty()
                        ? result
                        : result.withFailureReason(result.failure().reason() + " after " + attempt + " attempts");
            }
            pauseBeforeRetry(attempt);
        }
        return Objects.requireNonNull(result);
    }

    private void pauseBeforeRetry(int completedAttempt) {
        if (retryDelay.isZero()) return;
        long nanos;
        try {
            nanos = Math.multiplyExact(retryDelay.toNanos(), completedAttempt);
        } catch (ArithmeticException ignored) {
            nanos = Long.MAX_VALUE;
        }
        LockSupport.parkNanos(nanos);
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FRED retry interrupted");
        }
    }

    private Result fetch(String key, String seriesId) {
        try {
            var root = restClient.get()
                    .uri(builder -> builder
                            .path("/fred/series/observations")
                            .queryParam("series_id", seriesId)
                            .queryParam("api_key", apiKey)
                            .queryParam("file_type", "json")
                            .queryParam("sort_order", "desc")
                            .queryParam("limit", BACKFILL_LIMIT)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            if (root == null) throw new IllegalArgumentException("empty response");
            var observations = root.get("observations");
            if (observations == null || !observations.isArray()) {
                throw new IllegalArgumentException("observations array is missing");
            }
            var result = new ArrayList<MarketObservation>();
            var byDate = new LinkedHashMap<LocalDate, MarketObservation>();
            var today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            for (var node : observations) {
                var raw = text(node, "value");
                if (".".equals(raw)) continue;
                var value = Double.parseDouble(raw);
                var date = LocalDate.parse(text(node, "date"));
                if (!Double.isFinite(value) || date.isAfter(today) || !plausible(key, value)) continue;
                byDate.put(date, new MarketObservation(key, seriesId, value, date, source()));
            }
            result.addAll(byDate.values());
            if (!result.isEmpty()) {
                result.sort(java.util.Comparator.comparing(MarketObservation::observationDate));
                return Result.success(result);
            }
            return Result.failure(key, "No usable latest observation");
        } catch (RuntimeException error) {
            return Result.failure(key, safeReason(error), retryable(error));
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        if (value == null || !value.isString()) throw new IllegalArgumentException(field + " is missing");
        return value.stringValue();
    }

    private static String safeReason(RuntimeException error) {
        if (error instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        return error instanceof IllegalArgumentException ? "Malformed provider response" : error.getClass().getSimpleName();
    }

    private static boolean retryable(RuntimeException error) {
        if (error instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return status == 408 || status == 425 || status == 429 || status >= 500;
        }
        return error instanceof RestClientException;
    }

    private static boolean plausible(String key, double value) {
        return switch (key) {
            case "DGS10", "DGS30", "T10YIE", "T10Y2Y", "SOFR", "EFFR", "IORB" ->
                    value >= -10 && value <= 30;
            case "VIXCLS" -> value >= 0 && value <= 200;
            case "BAMLH0A0HYM2" -> value >= 0 && value <= 50;
            case "STLFSI4" -> value >= -20 && value <= 20;
            case "UNRATE" -> value >= 0 && value <= 40;
            case "ICSA" -> value >= 0 && value <= 5_000_000;
            case "WALCL", "WRESBAL", "RRPONTSYD", "WDTGAL", "WTREGEN", "WRMFNS",
                    "M2SL", "WM2NS", "INDPRO", "CPI", "PCE" -> value > 0;
            default -> Math.abs(value) <= 1_000_000_000;
        };
    }

    private static Map<String, String> series() {
        var values = new LinkedHashMap<String, String>();
        values.put("DGS10", "DGS10");
        values.put("DGS30", "DGS30");
        values.put("T10YIE", "T10YIE");
        values.put("T10Y2Y", "T10Y2Y");
        values.put("VIXCLS", "VIXCLS");
        values.put("BAMLH0A0HYM2", "BAMLH0A0HYM2");
        values.put("STLFSI4", "STLFSI4");
        values.put("WALCL", "WALCL");
        values.put("WRESBAL", "WRESBAL");
        values.put("RRPONTSYD", "RRPONTSYD");
        values.put("WDTGAL", "WDTGAL");
        // Week-average TGA is retained for audit/backward compatibility only.
        // Point-in-time liquidity calculations use the Wednesday-level WDTGAL.
        values.put("WTREGEN", "WTREGEN");
        values.put("TREASURY_MARKETABLE_ISSUANCE", "BOGZ1FU313161105Q");
        values.put("WRMFNS", "WRMFNS");
        values.put("M2SL", "M2SL");
        values.put("WM2NS", "WM2NS");
        values.put("UNRATE", "UNRATE");
        values.put("ICSA", "ICSA");
        values.put("SOFR", "SOFR");
        values.put("EFFR", "EFFR");
        values.put("IORB", "IORB");
        values.put("INDPRO", "INDPRO");
        values.put("FEDERAL_DEBT_GDP", "GFDEGDQ188S");
        values.put("CPI", "CPIAUCSL");
        values.put("PCE", "PCEPI");
        values.put("FEDERAL_DEFICIT_GDP", "FYFSGDA188S");
        return Map.copyOf(values);
    }

    private record Result(
            List<MarketObservation> observations,
            MarketCollectionBatch.Failure failure,
            boolean retryable
    ) {
        private Result {
            observations = List.copyOf(observations == null ? List.of() : observations);
            if (observations.isEmpty() == (failure == null)) {
                throw new IllegalArgumentException("result must contain observations or a failure");
            }
            if (!observations.isEmpty() && retryable) {
                throw new IllegalArgumentException("successful result must not be retryable");
            }
        }

        static Result success(List<MarketObservation> observations) {
            return new Result(observations, null, false);
        }

        static Result failure(String key, String reason) {
            return failure(key, reason, false);
        }

        static Result failure(String key, String reason, boolean retryable) {
            return new Result(List.of(), new MarketCollectionBatch.Failure(key, reason), retryable);
        }

        Result withFailureReason(String reason) {
            if (failure == null) return this;
            return failure(failure.key(), reason, retryable);
        }
    }
}
