package io.macrosquare.institutional.adapter.out.sec;

import io.macrosquare.institutional.application.port.out.ResolveInstitutionalSecurityIdentitiesPort;
import io.macrosquare.institutional.domain.model.InstitutionalSecurityIdentity;
import io.macrosquare.institutional.domain.model.InstitutionalSecurityObservation;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Conservative CUSIP-to-ticker resolver using the official SEC issuer directory and
 * the Spring-owned research universe. Ambiguous names remain explicitly unmapped.
 */
public final class SecIssuerDirectoryIdentityResolverAdapter
        implements ResolveInstitutionalSecurityIdentitiesPort {

    private static final int MINIMUM_CONFIDENCE = 88;
    private static final Set<String> LEGAL_SUFFIXES = Set.of(
            "THE", "INC", "INCORPORATED", "CORP", "CORPORATION", "CO", "COMPANY",
            "PLC", "LTD", "LIMITED", "LLC", "LP", "SA", "NV", "AG", "GROUP",
            "HOLDING", "HOLDINGS", "HLDG", "HLDGS", "DEL", "DE", "NEW");

    private final RestClient secClient;
    private final ObjectMapper objectMapper;
    private final LoadResearchCatalogPort researchCatalog;
    private final Clock clock;
    private final Duration cacheTtl;
    private volatile CachedDirectory cache;

    public SecIssuerDirectoryIdentityResolverAdapter(
            RestClient secClient,
            ObjectMapper objectMapper,
            LoadResearchCatalogPort researchCatalog,
            Clock clock,
            Duration cacheTtl
    ) {
        this.secClient = Objects.requireNonNull(secClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.researchCatalog = Objects.requireNonNull(researchCatalog);
        this.clock = Objects.requireNonNull(clock);
        this.cacheTtl = Objects.requireNonNull(cacheTtl);
        if (cacheTtl.isNegative() || cacheTtl.isZero()) {
            throw new IllegalArgumentException("cacheTtl must be positive");
        }
    }

    @Override
    public List<InstitutionalSecurityIdentity> resolve(List<InstitutionalSecurityObservation> observations) {
        if (observations == null || observations.isEmpty()) return List.of();
        var directory = directory();
        var sectors = sectorsByTicker();
        var result = new LinkedHashMap<String, InstitutionalSecurityIdentity>();
        for (var observation : observations) {
            var match = bestMatch(observation.issuer(), directory);
            if (match == null) continue;
            result.put(observation.cusip(), new InstitutionalSecurityIdentity(
                    observation.cusip(), match.candidate().ticker(), match.candidate().cik(),
                    observation.issuer(), sectors.getOrDefault(match.candidate().ticker(), ""),
                    observation.reportPeriod(), null, match.confidence(),
                    "SEC_COMPANY_TICKERS_NAME_MATCH"));
        }
        return List.copyOf(result.values());
    }

    private List<IssuerCandidate> directory() {
        var current = cache;
        var now = clock.instant();
        if (current != null && now.isBefore(current.loadedAt().plus(cacheTtl))) return current.values();
        var loaded = fetchDirectory();
        cache = new CachedDirectory(loaded, now);
        return loaded;
    }

    private List<IssuerCandidate> fetchDirectory() {
        return secClient.get().uri("/files/company_tickers.json").accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
                    try (var parser = objectMapper.createParser(response.getBody())) {
                        if (parser.nextToken() != JsonToken.START_OBJECT) {
                            throw new IllegalArgumentException("SEC issuer directory must be an object");
                        }
                        var result = new ArrayList<IssuerCandidate>();
                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            if (parser.currentToken() != JsonToken.PROPERTY_NAME
                                    || parser.nextToken() != JsonToken.START_OBJECT) {
                                throw new IllegalArgumentException("SEC issuer directory entry is invalid");
                            }
                            String ticker = null;
                            String cik = null;
                            String title = null;
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                var field = parser.currentName();
                                var valueToken = parser.nextToken();
                                switch (field) {
                                    case "ticker" -> ticker = parser.getString();
                                    case "title" -> title = parser.getString();
                                    case "cik_str" -> {
                                        var raw = valueToken.isNumeric()
                                                ? parser.getNumberValue().toString() : parser.getString();
                                        cik = "0".repeat(Math.max(0, 10 - raw.length())) + raw;
                                    }
                                    default -> parser.skipChildren();
                                }
                            }
                            if (ticker != null && title != null && cik != null) {
                                result.add(new IssuerCandidate(
                                        ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-'),
                                        cik, title, normalizeName(title)));
                            }
                        }
                        return List.copyOf(result);
                    }
                });
    }

    private Map<String, String> sectorsByTicker() {
        try {
            var result = new HashMap<String, String>();
            for (var sector : researchCatalog.loadSectors().sectors()) {
                for (var ticker : sector.tickers()) {
                    result.putIfAbsent(ticker.toUpperCase(Locale.ROOT), sector.sectorKey());
                }
            }
            return Map.copyOf(result);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static Match bestMatch(String issuer, List<IssuerCandidate> candidates) {
        var normalized = normalizeName(issuer);
        if (normalized.isBlank()) return null;
        var ranked = candidates.stream()
                .map(candidate -> new Match(candidate, confidence(normalized, candidate.normalizedName())))
                .filter(value -> value.confidence() >= MINIMUM_CONFIDENCE)
                .sorted(Comparator.comparingInt(Match::confidence).reversed()
                        .thenComparing(value -> value.candidate().ticker()))
                .limit(2).toList();
        if (ranked.isEmpty()) return null;
        if (ranked.size() > 1 && ranked.getFirst().confidence() == ranked.getLast().confidence()
                && !ranked.getFirst().candidate().ticker().equals(ranked.getLast().candidate().ticker())) {
            return null;
        }
        return ranked.getFirst();
    }

    static int confidence(String left, String right) {
        if (left.equals(right)) return 100;
        var compactLeft = left.replace(" ", "");
        var compactRight = right.replace(" ", "");
        if (compactLeft.equals(compactRight)) return 99;
        var shorter = Math.min(left.length(), right.length());
        if (shorter >= 7 && (left.startsWith(right) || right.startsWith(left))) return 94;
        var leftTokens = new HashSet<>(List.of(left.split(" ")));
        var rightTokens = new HashSet<>(List.of(right.split(" ")));
        var intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        var union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);
        if (intersection.isEmpty()) return 0;
        var containment = intersection.size() / (double) Math.min(leftTokens.size(), rightTokens.size());
        var jaccard = intersection.size() / (double) union.size();
        return (int) Math.round(60 * containment + 35 * jaccard);
    }

    static String normalizeName(String raw) {
        if (raw == null) return "";
        var value = Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT)
                .replace('&', ' ')
                .replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\\s+", " ").trim();
        var tokens = new ArrayList<>(List.of(value.split(" ")));
        while (!tokens.isEmpty() && LEGAL_SUFFIXES.contains(tokens.getLast())) tokens.removeLast();
        return String.join(" ", tokens)
                .replace(" HLDGS", " HOLDINGS")
                .replace(" INTL", " INTERNATIONAL")
                .replaceAll("\\s+", " ").trim();
    }

    private record IssuerCandidate(String ticker, String cik, String title, String normalizedName) {
    }

    private record Match(IssuerCandidate candidate, int confidence) {
    }

    private record CachedDirectory(List<IssuerCandidate> values, Instant loadedAt) {
    }
}
