package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.QueryCompanyReadUseCase;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@RestController
public final class CompanyReadController {

    private static final int DEFAULT_SEARCH_LIMIT = 8;
    private static final int MAX_SEARCH_LIMIT = 12;
    private static final Pattern LEGACY_INTEGER_PREFIX = Pattern.compile("^\\s*([+-]?\\d+)");

    private final QueryCompanyReadUseCase queryCompanyRead;
    private final CompanyResearchPayloadCache researchPayloadCache;

    public CompanyReadController(QueryCompanyReadUseCase queryCompanyRead, ObjectMapper objectMapper) {
        this.queryCompanyRead = Objects.requireNonNull(queryCompanyRead);
        this.researchPayloadCache = new CompanyResearchPayloadCache(objectMapper);
    }

    @GetMapping("/api/company-search")
    public CompanyReadApiResponse.Search search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String limit
    ) {
        return CompanyReadApiResponse.Search.from(queryCompanyRead.search(q, parseLegacyLimit(limit)));
    }

    @GetMapping("/api/company-summaries")
    public CompanyReadApiResponse.Summaries summaries(@RequestParam(required = false) String tickers) {
        return CompanyReadApiResponse.Summaries.from(queryCompanyRead.summaries(splitTickers(tickers)));
    }

    @GetMapping("/api/company/{ticker}")
    public ResponseEntity<byte[]> detail(@PathVariable String ticker) {
        var payload = researchPayloadCache.detail(ticker, queryCompanyRead.detail(ticker));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);
    }

    static int parseLegacyLimit(String value) {
        if (value == null) return DEFAULT_SEARCH_LIMIT;
        var matcher = LEGACY_INTEGER_PREFIX.matcher(value);
        if (!matcher.find()) return DEFAULT_SEARCH_LIMIT;
        var parsed = new BigInteger(matcher.group(1));
        if (parsed.signum() == 0) return DEFAULT_SEARCH_LIMIT;
        if (parsed.compareTo(BigInteger.ONE) < 0) return 1;
        if (parsed.compareTo(BigInteger.valueOf(MAX_SEARCH_LIMIT)) > 0) return MAX_SEARCH_LIMIT;
        return parsed.intValue();
    }

    private static List<String> splitTickers(String tickers) {
        if (tickers == null || tickers.isEmpty()) return List.of();
        return Arrays.asList(tickers.split(",", -1));
    }
}
