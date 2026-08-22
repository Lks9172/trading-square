package io.macrosquare.notification.adapter.out.persistence;

import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileInvestmentCandidateAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void excludesRetiredCompaniesFromTheLiveEntryScanUniverse() throws Exception {
        var active = objectMapper.readTree(company("XOM"));
        var retired = objectMapper.readTree(company("CTRA"));
        JsonEnvelopeStore store = new JsonEnvelopeStore() {
            @Override
            public Optional<tools.jackson.databind.JsonNode> findValue(String fileName) {
                return Optional.empty();
            }

            @Override
            public List<NamedValue> listValues(String prefix, int limit) {
                return List.of(new NamedValue("xom", active), new NamedValue("ctra", retired));
            }
        };

        var result = new FileInvestmentCandidateAdapter(store).loadScanUniverse();

        assertEquals(List.of("XOM"), result.stream().map(value -> value.symbol()).toList());
    }

    @Test
    void excludesARetiredCompanyEvenWhenItRemainsInTheStartupCache() throws Exception {
        var cached = objectMapper.readTree("""
                [{
                  "ticker":"CTRA",
                  "name":"Coterra Energy",
                  "confirmedBottomState":"확신",
                  "confirmedBottomScore":90,
                  "totalScore":90,
                  "buyScore":90,
                  "action":"STRONG BUY"
                }]
                """);
        JsonEnvelopeStore store = fileName -> fileName.startsWith("current-telegram-bottom-company")
                ? Optional.of(cached) : Optional.empty();

        var result = new FileInvestmentCandidateAdapter(store).loadStartupCandidates();

        assertEquals(List.of(), result);
    }

    @Test
    void excludesCryptoWhenAnyRequiredDecisionSeriesIsStale() throws Exception {
        var stale = objectMapper.readTree(crypto("2026-08-06", "2026-08-06", "2026-07-17"));
        JsonEnvelopeStore store = new JsonEnvelopeStore() {
            @Override
            public Optional<tools.jackson.databind.JsonNode> findValue(String fileName) {
                if (fileName.equals("route_research-crypto_v1.json")) {
                    var catalog = objectMapper.createObjectNode();
                    catalog.set("items", objectMapper.createArrayNode().add(stale));
                    return Optional.of(catalog);
                }
                return Optional.empty();
            }

            @Override
            public List<NamedValue> listValues(String prefix, int limit) {
                return List.of();
            }
        };

        var result = new FileInvestmentCandidateAdapter(store, fixedClock()).loadScanUniverse();

        assertEquals(List.of(), result);
    }

    @Test
    void includesCryptoOnlyWhenMarketAndEveryRequiredDecisionSeriesAreCurrent() throws Exception {
        var current = objectMapper.readTree(crypto("2026-08-06", "2026-08-06", "2026-08-01"));
        JsonEnvelopeStore store = new JsonEnvelopeStore() {
            @Override
            public Optional<tools.jackson.databind.JsonNode> findValue(String fileName) {
                if (fileName.equals("route_research-crypto_v1.json")) {
                    var catalog = objectMapper.createObjectNode();
                    catalog.set("items", objectMapper.createArrayNode().add(current));
                    return Optional.of(catalog);
                }
                return Optional.empty();
            }

            @Override
            public List<NamedValue> listValues(String prefix, int limit) {
                return List.of();
            }
        };

        var result = new FileInvestmentCandidateAdapter(store, fixedClock()).loadScanUniverse();

        assertEquals(List.of("BTC"), result.stream().map(value -> value.symbol()).toList());
    }

    @Test
    void doesNotFallBackToAStaleCryptoStartupCacheWhenCurrentDetailIsMissing() throws Exception {
        var cached = objectMapper.readTree("""
                [{"symbol":"BTC","name":"Bitcoin","confirmedBottomState":"확신",
                  "confirmedBottomScore":90,"totalScore":90,"buyScore":90,"action":"STRONG BUY"}]
                """);
        JsonEnvelopeStore store = fileName -> fileName.startsWith("current-telegram-bottom-crypto")
                ? Optional.of(cached) : Optional.empty();

        var result = new FileInvestmentCandidateAdapter(store, fixedClock()).loadStartupCandidates();

        assertEquals(List.of(), result);
    }

    @Test
    void rejectsOutOfRangeCapturedScoresInsteadOfClampingThemIntoAQualifyingCandidate() throws Exception {
        var corrupt = objectMapper.readTree(company("BROKEN").replace(
                "\"totalScore\":80", "\"totalScore\":1000"));
        JsonEnvelopeStore store = new JsonEnvelopeStore() {
            @Override
            public Optional<tools.jackson.databind.JsonNode> findValue(String fileName) {
                return Optional.empty();
            }

            @Override
            public List<NamedValue> listValues(String prefix, int limit) {
                return List.of(new NamedValue("broken", corrupt));
            }
        };

        assertEquals(List.of(), new FileInvestmentCandidateAdapter(store).loadScanUniverse());
    }

    @Test
    void mapsCurrentCompanyMacdProjectionIntoNotificationOwnedEvidence() throws Exception {
        var company = objectMapper.readTree(company("NVDA").replace(
                "\"confirmedBottom\":{\"state\":\"확신\", \"score\":80}",
                "\"confirmedBottom\":{\"state\":\"확신\", \"score\":80},"
                        + "\"macdMomentum\":{"
                        + "\"daily\":{\"asOf\":\"2026-08-20\",\"position\":\"ABOVE_SIGNAL\","
                        + "\"latestCross\":\"BULLISH_CROSS\",\"crossDate\":\"2026-08-04\","
                        + "\"sessionsSinceCross\":12,\"histogramState\":\"CONTRACTING_POSITIVE\","
                        + "\"divergence\":\"BULLISH\",\"divergenceConfirmedDate\":\"2026-07-01\","
                        + "\"sessionsSinceDivergence\":35,\"divergenceActive\":false},"
                        + "\"weekly\":{\"asOf\":\"2026-08-20\",\"position\":\"ABOVE_SIGNAL\","
                        + "\"latestCross\":\"BULLISH_CROSS\",\"crossDate\":\"2026-08-07\","
                        + "\"sessionsSinceCross\":2,\"histogramState\":\"CONTRACTING_POSITIVE\","
                        + "\"divergence\":\"NONE\",\"divergenceActive\":false},"
                        + "\"currentWeekProvisional\":true}"));
        JsonEnvelopeStore store = new JsonEnvelopeStore() {
            @Override
            public Optional<tools.jackson.databind.JsonNode> findValue(String fileName) {
                return Optional.empty();
            }

            @Override
            public List<NamedValue> listValues(String prefix, int limit) {
                return List.of(new NamedValue("nvda", company));
            }
        };

        var result = new FileInvestmentCandidateAdapter(store).loadScanUniverse().getFirst();

        assertEquals("BULLISH_CROSS", result.technicalTiming().daily().latestCross().name());
        assertEquals(12, result.technicalTiming().daily().periodsSinceCross());
        assertEquals(true, result.technicalTiming().currentWeekProvisional());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC);
    }

    private static String company(String symbol) {
        return """
                {
                  "profile":{"symbol":"%s", "name":"Test"},
                  "score":{"totalScore":80},
                  "buyScore":{"buyScore":80},
                  "bottomSignal":{"confirmedBottom":{"state":"확신", "score":80}},
                  "reversalConfirmation":{"status":"ON", "score":80},
                  "positionSizing":{"action":"BUY"},
                  "sectorContext":{"label":"Energy"}
                }
                """.formatted(symbol);
    }

    private static String crypto(String marketDate, String commonDate, String etfDate) {
        return """
                {
                  "profile":{"symbol":"BTC", "name":"Bitcoin", "category":"디지털 금", "foundationalScore":90},
                  "market":{"asOf":"%s"},
                  "bottomUp":{"networkScore":90,"tokenomicsScore":90,"adoptionScore":90},
                  "moat":{"moatScore":90},
                  "onchain":{"activityScore":90},
                  "supplyPressure":{"floatScore":90},
                  "buyScore":{"buyScore":90,"action":"STRONG BUY"},
                  "bottomSignal":{"confirmedBottom":{"state":"확신","score":90,"signalDate":"2026-08-05","reasons":[]}},
                  "trendCharts":{
                    "btcDominanceProxy30d":[{"date":"%s","value":1}],
                    "stablecoinMcap30d":[{"date":"%s","value":1}],
                    "etfNetFlow30d":[{"date":"%s","value":1}],
                    "altSeasonProxy30d":[{"date":"%s","value":1}],
                    "exchangeNetflowProxy30d":[{"date":"%s","value":1}]
                  }
                }
                """.formatted(marketDate, commonDate, commonDate, etfDate, commonDate, commonDate);
    }
}
