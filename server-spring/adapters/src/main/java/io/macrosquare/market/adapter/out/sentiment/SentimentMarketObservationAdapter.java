package io.macrosquare.market.adapter.out.sentiment;

import io.macrosquare.market.application.model.MarketCollectionBatch;
import io.macrosquare.market.application.port.out.CollectMarketObservationsPort;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/** Direct CBOE/AAII/NAAIM sentiment collector. */
public final class SentimentMarketObservationAdapter implements CollectMarketObservationsPort {

    private static final List<String> OPTION_TICKERS = List.of("_SPX", "SPY", "QQQ");
    private static final Pattern CALL = Pattern.compile("C\\d{8}$");
    private static final Pattern PUT = Pattern.compile("P\\d{8}$");
    private static final Pattern AAII_ITEM = Pattern.compile(
            "<item>.*?<title><!\\[CDATA\\[(?:AAII Sentiment Survey:.*?|Sentiment Survey:.*?)]]></title>"
                    + ".*?<pubDate>(.*?)</pubDate>.*?<content:encoded><!\\[CDATA\\[(.*?)]]></content:encoded>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BULLISH = Pattern.compile(
            "(?:Bullish:\\s*|Bullish sentiment.{0,400}?\\bto\\s*)([0-9.]+)%",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BEARISH = Pattern.compile(
            "(?:Bearish:\\s*|Bearish sentiment.{0,400}?\\bto\\s*)([0-9.]+)%",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NAAIM_DATE = Pattern.compile("^(\\d{2})/(\\d{2})/(\\d{4})$");
    private static final int NAAIM_CURRENT_MAXIMUM_AGE_DAYS = 14;
    private static final String NAAIM_DELAYED_REASON = "Provider data is delayed beyond decision freshness";
    private static final long MAX_PROVIDER_BYTES = 32L * 1024L * 1024L;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final URI cboeBaseUrl;
    private final URI aaiiFeedUrl;
    private final URI naaimUrl;
    private final Clock clock;
    private final Executor executor;

    public SentimentMarketObservationAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            URI cboeBaseUrl,
            URI aaiiFeedUrl,
            URI naaimUrl,
            Clock clock,
            Executor executor
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.cboeBaseUrl = https(cboeBaseUrl, "cboeBaseUrl");
        this.aaiiFeedUrl = https(aaiiFeedUrl, "aaiiFeedUrl");
        this.naaimUrl = https(naaimUrl, "naaimUrl");
        this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public MarketDataSource source() {
        return MarketDataSource.SENTIMENT;
    }

    @Override
    public MarketCollectionBatch collect() {
        var startedAt = clock.instant();
        var observations = new ArrayList<MarketObservation>();
        var failures = new ArrayList<MarketCollectionBatch.Failure>();

        collectOne("PC_RATIO", this::fetchPutCallRatio, observations, failures);
        collectOne("AAII_BULL_BEAR_SPREAD", this::fetchAaiiSpread, observations, failures);
        collectOne("NAAIM_EXPOSURE", this::fetchNaaimExposure, observations, failures);

        return new MarketCollectionBatch(source(), startedAt, clock.instant(), observations, failures);
    }

    private MarketObservation fetchPutCallRatio() {
        var tasks = OPTION_TICKERS.stream()
                .map(ticker -> CompletableFuture.supplyAsync(() -> fetchOptionVolume(ticker), executor))
                .toList();
        var resolved = new ArrayList<OptionVolume>();
        for (var task : tasks) {
            try {
                resolved.add(task.join());
            } catch (CompletionException ignored) {
                // Partial option-chain coverage is explicitly allowed, as in the legacy collector.
            }
        }
        var newest = resolved.stream().map(OptionVolume::date).max(LocalDate::compareTo).orElse(null);
        var sameDate = resolved.stream().filter(value -> value.date().equals(newest)).toList();
        var callVolume = sameDate.stream().mapToLong(OptionVolume::callVolume).sum();
        var putVolume = sameDate.stream().mapToLong(OptionVolume::putVolume).sum();
        if (sameDate.isEmpty() || callVolume <= 0) {
            throw new IllegalArgumentException("option chain coverage is empty");
        }
        var ratio = Math.round((putVolume / (double) callVolume) * 1_000d) / 1_000d;
        if (!Double.isFinite(ratio) || ratio < 0 || ratio > 10) {
            throw new IllegalArgumentException("put/call ratio is outside its safety bound");
        }
        return new MarketObservation(
                "PC_RATIO",
                "CBOE:CHAIN:" + sameDate.size(),
                ratio,
                acceptedDate(newest, 7, "CBOE option-chain date"),
                source()
        );
    }

    private OptionVolume fetchOptionVolume(String ticker) {
        var uri = cboeBaseUrl.resolve("options/" + ticker + ".json");
        var bytes = getBytes(uri, MediaType.APPLICATION_JSON);
        try {
            var root = objectMapper.readTree(bytes);
            var options = root == null ? null : root.at("/data/options");
            if (options == null || !options.isArray()) throw new IllegalArgumentException("options are missing");
            long calls = 0;
            long puts = 0;
            for (var option : options) {
                var symbolNode = option.get("option");
                var volumeNode = option.get("volume");
                if (symbolNode == null || !symbolNode.isString() || volumeNode == null || !volumeNode.isNumber()) continue;
                var rawVolume = volumeNode.asDouble();
                if (!Double.isFinite(rawVolume) || rawVolume < 0 || rawVolume > 1_000_000_000) continue;
                var volume = Math.round(rawVolume);
                var symbol = symbolNode.stringValue();
                if (CALL.matcher(symbol).find()) calls += volume;
                else if (PUT.matcher(symbol).find()) puts += volume;
            }
            var timestamp = root.get("timestamp");
            // Provider time is evidence. Falling back to the collection date
            // makes an undated/stale option chain look current and can create
            // a false put/call contrarian signal.
            if (timestamp == null || !timestamp.isString() || timestamp.stringValue().isBlank()) {
                throw new IllegalArgumentException("option-chain timestamp is missing");
            }
            var date = parseLeadingDate(timestamp.stringValue());
            return new OptionVolume(calls, puts, acceptedDate(date, 7, "CBOE option-chain date"));
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("option chain is malformed", error);
        }
    }

    private MarketObservation fetchAaiiSpread() {
        var xml = new String(getBytes(aaiiFeedUrl, MediaType.APPLICATION_XML), StandardCharsets.UTF_8);
        var item = AAII_ITEM.matcher(xml);
        if (!item.find()) throw new IllegalArgumentException("AAII survey item is missing");
        var published = DateTimeFormatter.RFC_1123_DATE_TIME.parse(item.group(1).trim(), java.time.ZonedDateTime::from)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        var content = item.group(2);
        var bullish = requiredMatch(BULLISH, content, "AAII bullish");
        var bearish = requiredMatch(BEARISH, content, "AAII bearish");
        if (bullish < 0 || bullish > 100 || bearish < 0 || bearish > 100) {
            throw new IllegalArgumentException("AAII percentages are outside 0..100");
        }
        var spread = Math.round((bullish - bearish) * 100d) / 100d;
        return new MarketObservation(
                "AAII_BULL_BEAR_SPREAD",
                "AAII:SUBSTACK",
                spread,
                acceptedDate(published, 14, "AAII publication date"),
                source()
        );
    }

    private MarketObservation fetchNaaimExposure() {
        var html = new String(getBytes(naaimUrl, MediaType.TEXT_HTML), StandardCharsets.UTF_8);
        var rows = ROW.matcher(html);
        var parsed = new ArrayList<NaaimObservation>();
        while (rows.find()) {
            var cells = new ArrayList<String>();
            var cellMatcher = CELL.matcher(rows.group(1));
            while (cellMatcher.find()) {
                cells.add(decodeBasicEntities(TAG.matcher(cellMatcher.group(1)).replaceAll("")).trim());
            }
            if (cells.size() < 2) continue;
            var date = NAAIM_DATE.matcher(cells.getFirst());
            if (!date.matches()) continue;
            try {
                var value = Math.round(Double.parseDouble(cells.get(1).replace(",", "")) * 100d) / 100d;
                if (!Double.isFinite(value) || value < -300 || value > 300) continue;
                parsed.add(new NaaimObservation(
                        LocalDate.of(Integer.parseInt(date.group(3)), Integer.parseInt(date.group(1)),
                                Integer.parseInt(date.group(2))),
                        value
                ));
            } catch (NumberFormatException | java.time.DateTimeException ignored) {
                // Continue to the next table row.
            }
        }
        var latest = parsed.stream().max(Comparator.comparing(NaaimObservation::date))
                .orElseThrow(() -> new IllegalArgumentException("NAAIM table has no valid row"));
        var today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (latest.date().isBefore(today.minusDays(NAAIM_CURRENT_MAXIMUM_AGE_DAYS))) {
            // Since 2026-08-01 NAAIM's public table intentionally carries a
            // three-month delay. Persisting that row as current exposure would
            // be look-ahead-safe but financially stale, so fail closed with a
            // distinct operational reason. A licensed/current endpoint can be
            // supplied through NAAIM_EXPOSURE_URL without changing the domain.
            throw new ProviderDataDelayedException();
        }
        var observedOn = acceptedDate(
                latest.date(), NAAIM_CURRENT_MAXIMUM_AGE_DAYS, "NAAIM observation date");
        return new MarketObservation(
                "NAAIM_EXPOSURE",
                "NAAIM",
                latest.value(),
                observedOn,
                source()
        );
    }

    private byte[] getBytes(URI uri, MediaType mediaType) {
        try {
            var bytes = restClient.get().uri(uri).accept(mediaType).retrieve().body(byte[].class);
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_PROVIDER_BYTES) {
                throw new IllegalArgumentException("provider payload is empty or exceeds its bound");
            }
            return bytes;
        } catch (RestClientResponseException error) {
            throw error;
        }
    }

    private void collectOne(
            String key,
            java.util.function.Supplier<MarketObservation> collector,
            List<MarketObservation> observations,
            List<MarketCollectionBatch.Failure> failures
    ) {
        try {
            observations.add(collector.get());
        } catch (RuntimeException error) {
            failures.add(new MarketCollectionBatch.Failure(
                    key,
                    safeReason(error),
                    failureKind(error)
            ));
        }
    }

    private static MarketCollectionBatch.FailureKind failureKind(RuntimeException error) {
        var root = error instanceof CompletionException && error.getCause() instanceof RuntimeException runtime
                ? runtime : error;
        return root instanceof ProviderDataDelayedException
                ? MarketCollectionBatch.FailureKind.PROVIDER_POLICY_UNAVAILABLE
                : MarketCollectionBatch.FailureKind.SOURCE_GAP;
    }

    private static double requiredMatch(Pattern pattern, String value, String field) {
        var matcher = pattern.matcher(value);
        if (!matcher.find()) throw new IllegalArgumentException(field + " is missing");
        var number = Double.parseDouble(matcher.group(1));
        if (!Double.isFinite(number)) throw new IllegalArgumentException(field + " is invalid");
        return number;
    }

    private static LocalDate parseLeadingDate(String value) {
        if (value == null || value.length() < 10) throw new IllegalArgumentException("provider date is malformed");
        return LocalDate.parse(value.substring(0, 10));
    }

    private LocalDate acceptedDate(LocalDate date, int maximumAgeDays, String field) {
        var today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (date == null || date.isAfter(today) || date.isBefore(today.minusDays(maximumAgeDays))) {
            throw new IllegalArgumentException(field + " is outside the accepted window");
        }
        return date;
    }

    private static String decodeBasicEntities(String value) {
        return value.replace("&nbsp;", " ").replace("&#8211;", "-").replace("&minus;", "-")
                .replace("&amp;", "&");
    }

    private static URI https(URI uri, String field) {
        if (uri == null || !uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(field + " must be an absolute HTTPS URI");
        }
        return uri;
    }

    private static String safeReason(RuntimeException error) {
        if (error instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        var root = error instanceof CompletionException && error.getCause() instanceof RuntimeException runtime
                ? runtime : error;
        if (root instanceof ProviderDataDelayedException) return NAAIM_DELAYED_REASON;
        return root instanceof IllegalArgumentException ? "Malformed provider response" : root.getClass().getSimpleName();
    }

    private record OptionVolume(long callVolume, long putVolume, LocalDate date) {
    }

    private record NaaimObservation(LocalDate date, double value) {
    }

    private static final class ProviderDataDelayedException extends RuntimeException {
        private ProviderDataDelayedException() {
            super(NAAIM_DELAYED_REASON);
        }
    }
}
