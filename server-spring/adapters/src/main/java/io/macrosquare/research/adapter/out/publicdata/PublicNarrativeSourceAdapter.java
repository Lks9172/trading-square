package io.macrosquare.research.adapter.out.publicdata;

import io.macrosquare.research.application.model.NarrativeThemeDefinition;
import io.macrosquare.research.application.port.out.CollectNarrativeSourcesPort;
import io.macrosquare.research.domain.narrative.NarrativeSourceQuality;
import io.macrosquare.research.domain.narrative.NarrativeSourceReading;
import io.macrosquare.research.domain.narrative.NarrativeSourceStatus;
import io.macrosquare.research.domain.narrative.NarrativeTheme;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class PublicNarrativeSourceAdapter implements CollectNarrativeSourcesPort {

    private static final String GOOGLE_NEWS = "GOOGLE_NEWS_7D";
    private static final String WIKIMEDIA = "WIKIMEDIA_7D";
    private static final String YOUTUBE = "YOUTUBE_30D";
    private static final Map<NarrativeTheme, String> WIKIMEDIA_ARTICLES = articles();

    private final RestClient googleNews;
    private final RestClient wikimedia;
    private final RestClient youtube;
    private final ObjectMapper objectMapper;
    private final ObjectStorage objectStorage;
    private final Clock clock;
    private final String youtubeApiKey;
    private final Duration interRequestDelay;
    private final long maximumResponseBytes;

    public PublicNarrativeSourceAdapter(
            RestClient googleNews,
            RestClient wikimedia,
            RestClient youtube,
            ObjectMapper objectMapper,
            ObjectStorage objectStorage,
            Clock clock,
            String youtubeApiKey,
            Duration interRequestDelay,
            long maximumResponseBytes
    ) {
        this.googleNews = Objects.requireNonNull(googleNews);
        this.wikimedia = Objects.requireNonNull(wikimedia);
        this.youtube = Objects.requireNonNull(youtube);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.objectStorage = objectStorage;
        this.clock = Objects.requireNonNull(clock);
        this.youtubeApiKey = youtubeApiKey == null ? "" : youtubeApiKey.trim();
        this.interRequestDelay = Objects.requireNonNull(interRequestDelay);
        if (interRequestDelay.isNegative()) throw new IllegalArgumentException("interRequestDelay must not be negative");
        if (maximumResponseBytes < 1_024 || maximumResponseBytes > 10_000_000) {
            throw new IllegalArgumentException("maximumResponseBytes is out of range");
        }
        this.maximumResponseBytes = maximumResponseBytes;
    }

    @Override
    public List<NarrativeSourceReading> collect(List<NarrativeThemeDefinition> themes) {
        var readings = new ArrayList<NarrativeSourceReading>(themes.size() * 3);
        for (var theme : themes) {
            readings.add(safely(theme, GOOGLE_NEWS, "Google News 7D", NarrativeSourceQuality.PUBLIC_FEED,
                    () -> googleNews(theme)));
            pause();
            readings.add(safely(theme, WIKIMEDIA, "Wikipedia 관심도", NarrativeSourceQuality.PUBLIC_API,
                    () -> wikimedia(theme)));
            pause();
            readings.add(youtubeApiKey.isBlank()
                    ? missing(theme, YOUTUBE, "YouTube 30D", NarrativeSourceQuality.VERIFIED_API,
                            "YouTube API 키가 설정되지 않아 이 소스는 점수에서 제외됩니다.")
                    : safely(theme, YOUTUBE, "YouTube 30D", NarrativeSourceQuality.VERIFIED_API,
                            () -> youtube(theme)));
            if (!youtubeApiKey.isBlank()) pause();
        }
        return List.copyOf(readings);
    }

    private NarrativeSourceReading googleNews(NarrativeThemeDefinition theme) throws Exception {
        var query = theme.externalQueries().newsQuery();
        var bytes = fetchBounded(googleNews.get().uri(builder -> builder
                        .path("/rss/search")
                        .queryParam("q", query)
                        .queryParam("hl", "en-US")
                        .queryParam("gl", "US")
                        .queryParam("ceid", "US:en")
                        .build()));
        var parsed = parseGoogleNews(bytes, clock.instant());
        var sourceUrl = "https://news.google.com/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        var rawKey = storeRaw(GOOGLE_NEWS, theme.theme(), bytes, "application/rss+xml");
        return available(
                theme, GOOGLE_NEWS, "Google News 7D", NarrativeSourceQuality.PUBLIC_FEED,
                (double) parsed.last7d(), scoreNews(parsed.last7d()),
                "7D %d건 / 30D %d건 / feed %d건".formatted(
                        parsed.last7d(), parsed.last30d(), parsed.total()),
                sourceUrl, hash(bytes), rawKey);
    }

    private NarrativeSourceReading wikimedia(NarrativeThemeDefinition theme) throws Exception {
        var article = WIKIMEDIA_ARTICLES.get(theme.theme());
        var end = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(2);
        var start = end.minusDays(35);
        var bytes = fetchBounded(wikimedia.get().uri(builder -> builder
                        .path("/api/rest_v1/metrics/pageviews/per-article/en.wikipedia/all-access/user/{article}/daily/{start}/{end}")
                        .build(article, dateHour(start), dateHour(end))));
        var parsed = parseWikimedia(bytes);
        if (parsed == null) {
            return missing(theme, WIKIMEDIA, "Wikipedia 관심도", NarrativeSourceQuality.PUBLIC_API,
                    "비교 가능한 Wikipedia pageview 구간이 없습니다.");
        }
        var rawKey = storeRaw(WIKIMEDIA, theme.theme(), bytes, "application/json");
        var sourceUrl = "https://en.wikipedia.org/wiki/" + article;
        return available(
                theme, WIKIMEDIA, "Wikipedia 관심도", NarrativeSourceQuality.PUBLIC_API,
                parsed.momentumPct(), scoreMomentum(parsed.momentumPct()),
                "최근 7D 일평균 %.0f회 / 이전 21D %.0f회 / 변화 %+.1f%%".formatted(
                        parsed.recentAverage(), parsed.previousAverage(), parsed.momentumPct()),
                sourceUrl, hash(bytes), rawKey);
    }

    private NarrativeSourceReading youtube(NarrativeThemeDefinition theme) throws Exception {
        var query = theme.externalQueries().youtubeQuery();
        var publishedAfter = clock.instant().minus(Duration.ofDays(30)).toString();
        var bytes = fetchBounded(youtube.get().uri(builder -> builder
                        .path("/youtube/v3/search")
                        .queryParam("part", "snippet")
                        .queryParam("type", "video")
                        .queryParam("maxResults", 25)
                        .queryParam("q", query)
                        .queryParam("publishedAfter", publishedAfter)
                        .queryParam("order", "date")
                        .build())
                .header("X-Goog-Api-Key", youtubeApiKey));
        var total = objectMapper.readTree(bytes).path("pageInfo").path("totalResults").asInt(-1);
        if (total < 0) {
            return missing(theme, YOUTUBE, "YouTube 30D", NarrativeSourceQuality.VERIFIED_API,
                    "YouTube API 응답에 totalResults가 없습니다.");
        }
        var rawKey = storeRaw(YOUTUBE, theme.theme(), bytes, "application/json");
        var sourceUrl = "https://www.youtube.com/results?search_query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        return available(
                theme, YOUTUBE, "YouTube 30D", NarrativeSourceQuality.VERIFIED_API,
                (double) total, scoreYoutube(total), "30D 검색 결과 %d건 (공식 API)".formatted(total),
                sourceUrl, hash(bytes), rawKey);
    }

    private NarrativeSourceReading safely(
            NarrativeThemeDefinition theme,
            String sourceKey,
            String label,
            NarrativeSourceQuality quality,
            CheckedSupplier action
    ) {
        try {
            return action.get();
        } catch (Exception error) {
            return failed(theme, sourceKey, label, quality,
                    "수집 실패: " + error.getClass().getSimpleName());
        }
    }

    private NarrativeSourceReading available(
            NarrativeThemeDefinition theme,
            String sourceKey,
            String label,
            NarrativeSourceQuality quality,
            double value,
            double score,
            String detail,
            String sourceUrl,
            String contentHash,
            String rawObjectKey
    ) {
        var observedAt = clock.instant();
        return new NarrativeSourceReading(
                theme.theme(), sourceKey, label, LocalDate.ofInstant(observedAt, ZoneOffset.UTC), observedAt,
                quality, NarrativeSourceStatus.AVAILABLE, value, score, detail, sourceUrl,
                contentHash, rawObjectKey);
    }

    private NarrativeSourceReading missing(
            NarrativeThemeDefinition theme,
            String sourceKey,
            String label,
            NarrativeSourceQuality quality,
            String detail
    ) {
        return unavailable(theme, sourceKey, label, quality, NarrativeSourceStatus.MISSING, detail);
    }

    private NarrativeSourceReading failed(
            NarrativeThemeDefinition theme,
            String sourceKey,
            String label,
            NarrativeSourceQuality quality,
            String detail
    ) {
        return unavailable(theme, sourceKey, label, quality, NarrativeSourceStatus.FAILED, detail);
    }

    private NarrativeSourceReading unavailable(
            NarrativeThemeDefinition theme,
            String sourceKey,
            String label,
            NarrativeSourceQuality quality,
            NarrativeSourceStatus status,
            String detail
    ) {
        var observedAt = clock.instant();
        var canonical = "%s|%s|%s|%s|%s".formatted(
                theme.theme().id(), sourceKey, LocalDate.ofInstant(observedAt, ZoneOffset.UTC), status, detail);
        return new NarrativeSourceReading(
                theme.theme(), sourceKey, label, LocalDate.ofInstant(observedAt, ZoneOffset.UTC), observedAt,
                quality, status, null, 5, detail, "", hash(canonical.getBytes(StandardCharsets.UTF_8)), "");
    }

    private byte[] fetchBounded(RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((sent, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
            try (var input = response.getBody()) {
                if (input == null) throw new IllegalArgumentException("empty source response");
                var bytes = input.readNBytes(Math.toIntExact(maximumResponseBytes + 1));
                if (bytes.length == 0) throw new IllegalArgumentException("empty source response");
                if (bytes.length > maximumResponseBytes) {
                    throw new IllegalArgumentException("source response too large");
                }
                return bytes;
            } catch (IOException error) {
                throw new IllegalStateException("unable to read narrative source response", error);
            }
        });
    }

    private String storeRaw(
            String sourceKey,
            NarrativeTheme theme,
            byte[] bytes,
            String contentType
    ) {
        if (objectStorage == null) return "";
        var digest = hash(bytes);
        var date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        var extension = contentType.contains("json") ? "json" : "xml";
        var key = "source-documents/narrative/%s/%s/%s-%s.%s".formatted(
                sourceKey.toLowerCase(Locale.ROOT), theme.id(), date, digest.substring(0, 16), extension);
        // The key is content-addressed for a theme/day. Avoid creating another
        // MinIO version when a retry receives the same payload; find() also
        // validates the relational active pointer and object checksum.
        if (objectStorage.find(key, maximumResponseBytes).isEmpty()) {
            objectStorage.put(key, bytes, contentType, Map.of(
                    "source", sourceKey,
                    "theme", theme.id(),
                    "sha256", digest
            ));
        }
        return key;
    }

    private void pause() {
        if (interRequestDelay.isZero()) return;
        try {
            Thread.sleep(interRequestDelay);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("narrative source collection interrupted", error);
        }
    }

    static NewsCounts parseGoogleNews(byte[] xml, Instant now) throws Exception {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
        var total = 0;
        var last7d = 0;
        var last30d = 0;
        var inItem = false;
        while (reader.hasNext()) {
            var event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                var name = reader.getLocalName();
                if ("item".equalsIgnoreCase(name)) {
                    inItem = true;
                    total++;
                } else if (inItem && "pubDate".equalsIgnoreCase(name)) {
                    var publishedAt = parseRfc822(reader.getElementText());
                    if (publishedAt != null) {
                        var age = Duration.between(publishedAt, now);
                        if (!age.isNegative() && age.compareTo(Duration.ofDays(30)) <= 0) last30d++;
                        if (!age.isNegative() && age.compareTo(Duration.ofDays(7)) <= 0) last7d++;
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "item".equalsIgnoreCase(reader.getLocalName())) {
                inItem = false;
            }
        }
        reader.close();
        return new NewsCounts(total, last7d, last30d);
    }

    private WikimediaMomentum parseWikimedia(byte[] bytes) throws Exception {
        var points = new ArrayList<ViewPoint>();
        JsonNode items = objectMapper.readTree(bytes).path("items");
        if (!items.isArray()) return null;
        for (var item : items) {
            var timestamp = item.path("timestamp").stringValue("");
            var views = item.path("views").asLong(-1);
            if (timestamp.length() < 8 || views < 0) continue;
            points.add(new ViewPoint(LocalDate.parse(timestamp.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE), views));
        }
        points.sort(Comparator.comparing(ViewPoint::date));
        if (points.size() < 28) return null;
        var recent = points.subList(points.size() - 7, points.size()).stream().mapToLong(ViewPoint::views).average().orElse(0);
        var previous = points.subList(points.size() - 28, points.size() - 7).stream()
                .mapToLong(ViewPoint::views).average().orElse(0);
        if (previous <= 0) return null;
        return new WikimediaMomentum(recent, previous, (recent / previous - 1) * 100);
    }

    private static Instant parseRfc822(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static double scoreNews(int value) {
        if (value >= 80) return 9;
        if (value >= 35) return 7;
        if (value >= 15) return 5.5;
        return 3.5;
    }

    private static double scoreYoutube(int value) {
        if (value >= 1_000) return 9;
        if (value >= 300) return 7;
        if (value >= 80) return 5.5;
        return 3.5;
    }

    private static double scoreMomentum(double value) {
        if (value >= 50) return 9;
        if (value >= 20) return 7.5;
        if (value >= 5) return 6;
        if (value >= -10) return 5;
        if (value >= -30) return 3.5;
        return 2;
    }

    private static String dateHour(LocalDate value) {
        return value.format(DateTimeFormatter.BASIC_ISO_DATE) + "00";
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static Map<NarrativeTheme, String> articles() {
        var values = new EnumMap<NarrativeTheme, String>(NarrativeTheme.class);
        values.put(NarrativeTheme.AI_POWER, "Artificial_intelligence");
        values.put(NarrativeTheme.GRID_CAPEX, "Electrical_grid");
        values.put(NarrativeTheme.DEFENSE_REARM, "Defense_industry");
        values.put(NarrativeTheme.FINANCE_LIQUIDITY, "Banking");
        values.put(NarrativeTheme.ENERGY_SUPPLY, "Petroleum_industry");
        values.put(NarrativeTheme.DIGITAL_ATTENTION, "Digital_media");
        values.put(NarrativeTheme.CONSUMER_DEMAND, "Consumer_spending");
        values.put(NarrativeTheme.CONSUMER_DEFENSIVE, "Fast-moving_consumer_goods");
        values.put(NarrativeTheme.MATERIALS_REFLATION, "Raw_material");
        values.put(NarrativeTheme.REAL_ASSETS_RATE, "Real_estate_investment_trust");
        values.put(NarrativeTheme.SAFEHAVEN_GOLD, "Gold");
        return Map.copyOf(values);
    }

    record NewsCounts(int total, int last7d, int last30d) {
    }

    private record WikimediaMomentum(double recentAverage, double previousAverage, double momentumPct) {
    }

    private record ViewPoint(LocalDate date, long views) {
    }

    @FunctionalInterface
    private interface CheckedSupplier {
        NarrativeSourceReading get() throws Exception;
    }
}
