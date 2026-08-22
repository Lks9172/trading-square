package io.macrosquare.company.adapter.out.sec;

import io.macrosquare.company.application.model.CompanyIdentity;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Streaming parser for {@code company_tickers.json}. The SEC payload and its
 * manual compatibility aliases remain an infrastructure concern.
 */
final class SecCompanyIdentityMapper {

    private static final Map<String, CompanyIdentity> MANUAL_OVERRIDES = Map.ofEntries(
            // The SEC ticker directory temporarily omitted AEP after its 2026
            // registrant metadata refresh. Keep the stable issuer identity so
            // a directory publishing gap cannot remove an active utility from
            // the current-metric universe.
            entry("AEP", "0000004904", "AMERICAN ELECTRIC POWER CO INC"),
            entry("SPR", "0001364885", "Spirit AeroSystems Holdings, Inc."),
            entry("MRSH", "0000062709", "Marsh & McLennan Companies, Inc."),
            entry("MMC", "0000062709", "Marsh & McLennan Companies, Inc."),
            entry("CTRA", "0000858470", "Coterra Energy Inc."),
            entry("SQ", "0001512673", "Block, Inc."),
            entry("ABB", "0001091587", "ABB Ltd"),
            entry("TGI", "0001021162", "TRIUMPH GROUP INC"),
            entry("HOLX", "0000859737", "HOLOGIC INC"),
            entry("PARA", "0000813828", "PARAMOUNT GLOBAL"),
            entry("IPG", "0000051644", "INTERPUBLIC GROUP OF COMPANIES, INC."),
            entry("ATUS", "0001702780", "ALTICE USA, INC."),
            entry("TGNA", "0000039899", "TEGNA INC"),
            entry("EDR", "0001766363", "ENDEAVOR GROUP HOLDINGS, INC."),
            entry("BK", "0001390777", "Bank of New York Mellon Corp"),
            entry("DFS", "0001393612", "DISCOVER FINANCIAL SERVICES"),
            entry("MRO", "0000101778", "MARATHON OIL CORP"),
            entry("HES", "0000004447", "HESS CORP"),
            entry("PXD", "0001038357", "PIONEER NATURAL RESOURCES CO"),
            entry("CIVI", "0001509589", "CIVITAS RESOURCES, INC."),
            entry("VTLE", "0001528129", "VITAL ENERGY, INC."),
            entry("WRK", "0002005951", "Smurfit Westrock plc"),
            entry("SEE", "0001012100", "SEALED AIR CORP/DE"),
            entry("ALE", "0000066756", "ALLETE INC"),
            entry("WBA", "0001618921", "WALGREENS BOOTS ALLIANCE, INC."),
            entry("K", "0000055067", "KELLANOVA"),
            entry("PEAK", "0000765880", "HEALTHPEAK PROPERTIES, INC.")
    );

    /**
     * Successor registrants can appear in the ticker directory before their
     * first quarterly XBRL history is published. Candidates are ordered so the
     * application automatically switches to the successor as soon as core
     * financial facts become available.
     *
     * <p>XOM completed its Texas redomiciliation on 2026-07-01. SEC currently
     * maps XOM to successor CIK 0002115436 while historical Company Facts remain
     * under predecessor CIK 0000034088.</p>
     */
    private static final Map<String, ContinuityOverride> FUNDAMENTALS_CONTINUITY = Map.of(
            "XOM", new ContinuityOverride("0002115436", List.of("0000034088"))
    );

    private SecCompanyIdentityMapper() {
    }

    static Map<String, CompanyIdentity> map(JsonParser parser) {
        Objects.requireNonNull(parser, "parser");
        var token = parser.currentToken() == null ? parser.nextToken() : parser.currentToken();
        if (token != JsonToken.START_OBJECT) {
            throw new IllegalArgumentException("SEC ticker directory must be an object");
        }

        var identities = new LinkedHashMap<String, CompanyIdentity>();
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "SEC ticker directory");
            var valueToken = parser.nextToken();
            if (valueToken != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("SEC ticker directory entry must be an object");
            }
            var identity = parseIdentity(parser);
            identities.put(identity.ticker(), identity);
        }
        if (identities.isEmpty()) {
            throw new IllegalArgumentException("SEC ticker directory must not be empty");
        }
        MANUAL_OVERRIDES.forEach(identities::putIfAbsent);
        FUNDAMENTALS_CONTINUITY.forEach((ticker, continuity) -> {
            var current = identities.get(ticker);
            if (current == null || !current.registryCik().equals(continuity.registryCik())) return;
            var candidates = new java.util.ArrayList<String>();
            candidates.add(current.registryCik());
            candidates.addAll(continuity.predecessorCiks());
            identities.put(ticker, new CompanyIdentity(
                    current.ticker(), current.registryCik(), current.title(), candidates
            ));
        });
        return Map.copyOf(identities);
    }

    static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        return ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }

    private static CompanyIdentity parseIdentity(JsonParser parser) {
        String cik = null;
        String ticker = null;
        String title = null;
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "SEC ticker directory entry");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            switch (name) {
                case "cik_str" -> cik = cik(valueToken, parser);
                case "ticker" -> ticker = text(valueToken, parser, "ticker");
                case "title" -> title = text(valueToken, parser, "title");
                default -> parser.skipChildren();
            }
        }
        if (cik == null || ticker == null || title == null) {
            throw new IllegalArgumentException("SEC ticker directory entry is incomplete");
        }
        return new CompanyIdentity(normalizeTicker(ticker), cik, title);
    }

    private static String cik(JsonToken token, JsonParser parser) {
        final String raw;
        if (token != null && token.isNumeric()) raw = parser.getNumberValue().toString();
        else if (token == JsonToken.VALUE_STRING) raw = parser.getString();
        else throw new IllegalArgumentException("SEC CIK must be numeric");
        var digits = raw.replaceAll("\\D+", "");
        if (digits.length() > 10) throw new IllegalArgumentException("SEC CIK exceeds ten digits");
        return "0".repeat(10 - digits.length()) + digits;
    }

    private static String text(JsonToken token, JsonParser parser, String field) {
        if (token != JsonToken.VALUE_STRING) {
            throw new IllegalArgumentException("SEC " + field + " must be text");
        }
        return parser.getString();
    }

    private static Map.Entry<String, CompanyIdentity> entry(String ticker, String cik, String title) {
        return Map.entry(ticker, new CompanyIdentity(ticker, cik, title));
    }

    private static void requireToken(JsonToken actual, JsonToken expected, String context) {
        if (actual != expected) throw new IllegalArgumentException(context + " has an invalid JSON shape");
    }

    private record ContinuityOverride(String registryCik, List<String> predecessorCiks) {
    }
}
