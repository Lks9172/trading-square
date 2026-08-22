package io.macrosquare.market.adapter.out.krx;

import io.macrosquare.market.application.model.MarketCollectionBatch;
import io.macrosquare.market.application.port.out.CollectMarketObservationsPort;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Keyless KOSPI investor-flow collector backed by Naver Finance's KRX aggregate table.
 * The provider publishes ten rows per page, so six bounded pages are read to form a real
 * sixty-trading-day history. Provider HTML and EUC-KR decoding remain in this adapter.
 */
public final class NaverKrxInvestorFlowAdapter implements CollectMarketObservationsPort {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final Charset EUC_KR = Charset.forName("EUC-KR");
    private static final int PAGE_COUNT = 6;
    private static final int EXPECTED_CELLS = 10;
    private static final int MAX_PAGE_BYTES = 1_048_576;
    private static final DateTimeFormatter QUERY_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter ROW_DATE = new DateTimeFormatterBuilder()
            .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
            .appendLiteral('.')
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendLiteral('.')
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .toFormatter();
    private static final Pattern ROW = Pattern.compile(
            "<tr[^>]*>\\s*<td[^>]*class\\s*=\\s*[\\\"'][^\\\"']*\\bdate2\\b[^\\\"']*[\\\"'][^>]*>"
                    + "(.*?)</td>(.*?)</tr>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<td[^>]*>(.*?)</td>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NUMBER = Pattern.compile("[+-]?\\d[\\d,]*");

    private static final List<Series> SERIES = List.of(
            new Series("KOSPI_INDIVIDUAL_NET_1D", "INDIVIDUAL", 0),
            new Series("KOSPI_FOREIGN_NET_1D", "FOREIGN", 1),
            new Series("KOSPI_INSTITUTION_NET_1D", "INSTITUTION", 2),
            new Series("KOSPI_FINANCIAL_NET_1D", "FINANCIAL_INVESTMENT", 3),
            new Series("KOSPI_INSURANCE_NET_1D", "INSURANCE", 4),
            new Series("KOSPI_INVESTMENT_TRUST_NET_1D", "INVESTMENT_TRUST", 5),
            new Series("KOSPI_BANK_NET_1D", "BANK", 6),
            new Series("KOSPI_OTHER_FINANCIAL_NET_1D", "OTHER_FINANCIAL", 7),
            new Series("KOSPI_PENSION_NET_1D", "PENSION", 8),
            new Series("KOSPI_OTHER_CORP_NET_1D", "OTHER_CORPORATION", 9)
    );

    private final RestClient restClient;
    private final URI url;
    private final Clock clock;

    public NaverKrxInvestorFlowAdapter(RestClient restClient, URI url, Clock clock) {
        this.restClient = Objects.requireNonNull(restClient);
        if (url == null || !url.isAbsolute() || !"https".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalArgumentException("KRX investor-flow URL must be an absolute HTTPS URI");
        }
        this.url = url;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public MarketDataSource source() {
        return MarketDataSource.KRX;
    }

    @Override
    public MarketCollectionBatch collect() {
        var startedAt = clock.instant();
        var targetDate = LocalDate.now(clock.withZone(KOREA));
        var byDate = new LinkedHashMap<LocalDate, FlowRow>();
        var failures = new ArrayList<MarketCollectionBatch.Failure>();

        for (var page = 1; page <= PAGE_COUNT; page++) {
            try {
                for (var row : fetchPage(targetDate, page)) byDate.put(row.date(), row);
            } catch (RuntimeException error) {
                failures.add(new MarketCollectionBatch.Failure(
                        "KOSPI_INVESTOR_FLOW_PAGE_" + page, safeReason(error)));
            }
        }

        var rows = byDate.values().stream()
                .filter(row -> !row.date().isAfter(targetDate))
                .sorted(Comparator.comparing(FlowRow::date))
                .toList();
        if (rows.isEmpty() && failures.isEmpty()) {
            failures.add(new MarketCollectionBatch.Failure(
                    "KOSPI_INVESTOR_FLOW", "Malformed provider response"));
        }

        var observations = new ArrayList<MarketObservation>(rows.size() * SERIES.size());
        for (var row : rows) {
            for (var series : SERIES) {
                observations.add(new MarketObservation(
                        series.key(),
                        "NAVER_FINANCE:KOSPI:" + series.providerSuffix(),
                        row.values().get(series.cellIndex()),
                        row.date(),
                        source()
                ));
            }
        }
        return new MarketCollectionBatch(
                source(), startedAt, clock.instant(), observations, failures);
    }

    private List<FlowRow> fetchPage(LocalDate targetDate, int page) {
        var requestUri = UriComponentsBuilder.fromUri(url)
                .replaceQueryParam("bizdate", QUERY_DATE.format(targetDate))
                .replaceQueryParam("sosok", "01")
                .replaceQueryParam("page", page)
                .build(true)
                .toUri();
        var body = restClient.get()
                .uri(requestUri)
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(byte[].class);
        if (body == null || body.length == 0 || body.length > MAX_PAGE_BYTES) {
            throw new IllegalArgumentException("investor-flow payload size is invalid");
        }
        return parse(new String(body, EUC_KR));
    }

    private static List<FlowRow> parse(String html) {
        var rows = new ArrayList<FlowRow>();
        var matcher = ROW.matcher(html);
        while (matcher.find()) {
            var date = LocalDate.parse(plainText(matcher.group(1)), ROW_DATE);
            var values = new ArrayList<Double>(EXPECTED_CELLS);
            var cells = CELL.matcher(matcher.group(2));
            while (cells.find()) values.add(parseNumber(cells.group(1)));
            if (values.size() != EXPECTED_CELLS) {
                throw new IllegalArgumentException("investor-flow row schema changed");
            }
            rows.add(new FlowRow(date, List.copyOf(values)));
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("investor-flow rows are missing");
        return List.copyOf(rows);
    }

    private static double parseNumber(String html) {
        var value = plainText(html)
                .replace('\u2212', '-')
                .replace('\u2013', '-')
                .replace(" ", "");
        if (!NUMBER.matcher(value).matches()) {
            throw new IllegalArgumentException("investor-flow number is invalid");
        }
        return Double.parseDouble(value.replace(",", ""));
    }

    private static String plainText(String html) {
        return TAG.matcher(html)
                .replaceAll("")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&minus;", "-")
                .replace("&#8722;", "-")
                .trim();
    }

    private static String safeReason(RuntimeException error) {
        if (error instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        return error instanceof IllegalArgumentException
                ? "Malformed provider response"
                : error.getClass().getSimpleName();
    }

    private record Series(String key, String providerSuffix, int cellIndex) { }

    private record FlowRow(LocalDate date, List<Double> values) { }
}
