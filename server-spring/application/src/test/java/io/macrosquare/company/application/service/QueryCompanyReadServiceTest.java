package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.SearchResult;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.SummaryResult;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.application.model.CompanyResearchSummarySnapshot;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class QueryCompanyReadServiceTest {

    @Test
    void normalizesSearchAndClampsItsLimit() {
        var observedQuery = new AtomicReference<String>();
        var observedLimit = new AtomicInteger();
        var expected = new SearchResult(List.of());
        var service = new QueryCompanyReadService(new StubPort() {
            @Override
            public SearchResult search(String normalizedQuery, int limit) {
                observedQuery.set(normalizedQuery);
                observedLimit.set(limit);
                return expected;
            }
        });

        assertSame(expected, service.search("  brk.b  ", 99));
        assertEquals("BRK-B", observedQuery.get());
        assertEquals(12, observedLimit.get());

        service.search("nvda", 0);
        assertEquals(1, observedLimit.get());
    }

    @Test
    void blankSearchShortCircuitsWithoutTouchingTheLegacyPort() {
        var calls = new AtomicInteger();
        var service = new QueryCompanyReadService(new StubPort() {
            @Override
            public SearchResult search(String normalizedQuery, int limit) {
                calls.incrementAndGet();
                return null;
            }
        });

        assertEquals(List.of(), service.search("   ", 8).items());
        assertEquals(0, calls.get());
    }

    @Test
    void summariesPreserveOrderWhileNormalizingDeduplicatingAndCappingAtTwenty() {
        var observed = new AtomicReference<List<String>>();
        var expected = new SummaryResult(List.of());
        var service = new QueryCompanyReadService(new StubPort() {
            @Override
            public SummaryResult summaries(List<String> normalizedTickers) {
                observed.set(normalizedTickers);
                return expected;
            }
        });
        var requested = new ArrayList<String>();
        requested.add(" nvda ");
        requested.add("");
        requested.add("NVDA");
        for (var index = 1; index <= 24; index++) requested.add("t" + index);

        assertSame(expected, service.summaries(requested));
        assertEquals(20, observed.get().size());
        assertEquals("NVDA", observed.get().getFirst());
        assertEquals("T19", observed.get().getLast());
    }

    @Test
    void emptySummariesShortCircuitWithoutTouchingTheLegacyPort() {
        var calls = new AtomicInteger();
        var service = new QueryCompanyReadService(new StubPort() {
            @Override
            public SummaryResult summaries(List<String> normalizedTickers) {
                calls.incrementAndGet();
                return null;
            }
        });

        assertEquals(List.of(), service.summaries(List.of("", "  ")).items());
        assertEquals(0, calls.get());
    }

    @Test
    void normalizesDetailTickerWithoutChangingLegacyDotNotation() {
        var observed = new AtomicReference<String>();
        var service = new QueryCompanyReadService(new StubPort() {
            @Override
            public Research detail(String normalizedTicker) {
                observed.set(normalizedTicker);
                return null;
            }
        });

        service.detail("  brk.b  ");

        assertEquals("BRK.B", observed.get());
    }

    @Test
    void canonicalizesAFormerTickerBeforeBothReadAndEnrichment() {
        var readTicker = new AtomicReference<String>();
        var enrichmentTicker = new AtomicReference<String>();
        var service = new QueryCompanyReadService(new StubPort() {
            @Override
            public Research detail(String normalizedTicker) {
                readTicker.set(normalizedTicker);
                return null;
            }
        }, (ticker, baseline) -> {
            enrichmentTicker.set(ticker);
            return baseline;
        });

        service.detail("mmc");

        assertEquals("MRSH", readTicker.get());
        assertEquals("MRSH", enrichmentTicker.get());
    }

    @Test
    void summariesReplaceCapturedMetricsWithTheCurrentPersistedProjection() {
        var snapshot = new CompanyResearchSummarySnapshot(
                "PG", LocalDate.parse("2026-06-30"), 400_000_000_000.0,
                3.26, 24.5, 4.1, 78, 55, 82, 71, 80,
                76, "매수 우호", 79, 31, "INDEPENDENT_MARKET_CAP", true, List.of(),
                "CURRENT", LocalDate.parse("2026-06-30"), LocalDate.parse("2026-07-30"),
                "10-Q", 0, List.of(),
                62, 70, 22, 74, "CONVICTION", Instant.parse("2026-08-06T00:00:00Z")
        );
        var repository = new CompanyResearchSummaryRepository() {
            @Override
            public java.util.Optional<CompanyResearchSummarySnapshot> find(String normalizedTicker) {
                return "PG".equals(normalizedTicker) ? java.util.Optional.of(snapshot) : java.util.Optional.empty();
            }

            @Override
            public java.util.Map<String, CompanyResearchSummarySnapshot> findAll() {
                return java.util.Map.of("PG", snapshot);
            }

            @Override
            public void save(CompanyResearchSummarySnapshot value) {
                throw new UnsupportedOperationException();
            }
        };
        var captured = new io.macrosquare.company.application.model.CompanyReadModels.Summary(
                "PG", "Procter & Gamble", 99, 99, "매수 우호",
                java.math.BigDecimal.valueOf(304.97), java.math.BigDecimal.TEN,
                java.math.BigDecimal.ONE, 20, 90, "후보", 80, 60, 50, 40);
        var service = new QueryCompanyReadService(new StubPort() {
            @Override
            public SummaryResult summaries(List<String> normalizedTickers) {
                return new SummaryResult(List.of(captured));
            }
        }, (ticker, baseline) -> baseline, repository,
                Clock.fixed(Instant.parse("2026-08-06T01:00:00Z"), ZoneOffset.UTC));

        var result = service.summaries(List.of("PG")).items().getFirst();

        assertEquals(3.26, result.revenueGrowthYoY().doubleValue(), 1e-9);
        assertEquals(78, result.totalScore());
        assertEquals(76, result.buyScore());
        assertEquals("확신", result.bottomState());
    }

    @Test
    void summariesFailClosedWhenPersistedFundamentalsAreNotCurrent() {
        var snapshot = new CompanyResearchSummarySnapshot(
                "PG", LocalDate.parse("2026-03-31"), 400_000_000_000.0,
                3.26, 24.5, 4.1, null, null, null, null, null,
                null, null, null, null, "INDEPENDENT_MARKET_CAP", true, List.of(),
                "LAGGING", LocalDate.parse("2026-06-30"), LocalDate.parse("2026-07-30"),
                "10-Q", 91, List.of("최신 공시 반영 대기"),
                62, 70, 22, 74, "CONVICTION", Instant.parse("2026-08-06T00:00:00Z")
        );
        var repository = new CompanyResearchSummaryRepository() {
            @Override public java.util.Optional<CompanyResearchSummarySnapshot> find(String ticker) {
                return java.util.Optional.of(snapshot);
            }
            @Override public java.util.Map<String, CompanyResearchSummarySnapshot> findAll() {
                return java.util.Map.of("PG", snapshot);
            }
            @Override public void save(CompanyResearchSummarySnapshot value) {
                throw new UnsupportedOperationException();
            }
        };
        var captured = new io.macrosquare.company.application.model.CompanyReadModels.Summary(
                "PG", "Procter & Gamble", 99, 99, "매수 우호",
                java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                20, 90, "후보", 80, 60, 50, 40);
        var service = new QueryCompanyReadService(new StubPort() {
            @Override public SummaryResult summaries(List<String> tickers) {
                return new SummaryResult(List.of(captured));
            }
        }, (ticker, baseline) -> baseline, repository,
                Clock.fixed(Instant.parse("2026-08-06T01:00:00Z"), ZoneOffset.UTC));

        var result = service.summaries(List.of("PG")).items().getFirst();

        assertEquals(null, result.totalScore());
        assertEquals(null, result.buyScore());
        assertEquals(null, result.revenueGrowthYoY());
        assertEquals("확신", result.bottomState());
    }

    @Test
    void summariesSuppressEveryDerivedMetricWhenTheProjectionIsOlderThanTwoHours() {
        var snapshot = new CompanyResearchSummarySnapshot(
                "PG", LocalDate.parse("2026-06-30"), 400_000_000_000.0,
                3.26, 24.5, 4.1, 78, 55, 82, 71, 80,
                76, "매수 우호", 79, 31, "INDEPENDENT_MARKET_CAP", true, List.of(),
                "CURRENT", LocalDate.parse("2026-06-30"), LocalDate.parse("2026-07-30"),
                "10-Q", 0, List.of(),
                62, 70, 22, 74, "CONVICTION", Instant.parse("2026-08-06T00:00:00Z")
        );
        var repository = new CompanyResearchSummaryRepository() {
            @Override public java.util.Optional<CompanyResearchSummarySnapshot> find(String ticker) {
                return java.util.Optional.of(snapshot);
            }
            @Override public java.util.Map<String, CompanyResearchSummarySnapshot> findAll() {
                return java.util.Map.of("PG", snapshot);
            }
            @Override public void save(CompanyResearchSummarySnapshot value) {
                throw new UnsupportedOperationException();
            }
        };
        var captured = new io.macrosquare.company.application.model.CompanyReadModels.Summary(
                "PG", "Procter & Gamble", 99, 99, "매수 우호",
                java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                20, 90, "확신", 80, 60, 50, 40);
        var service = new QueryCompanyReadService(new StubPort() {
            @Override public SummaryResult summaries(List<String> tickers) {
                return new SummaryResult(List.of(captured));
            }
        }, (ticker, baseline) -> baseline, repository,
                Clock.fixed(Instant.parse("2026-08-06T02:00:00.001Z"), ZoneOffset.UTC));

        var result = service.summaries(List.of("PG")).items().getFirst();

        assertEquals(null, result.totalScore());
        assertEquals(null, result.buyScore());
        assertEquals(null, result.bottomState());
        assertEquals(null, result.priceBottomScore());
        assertEquals(null, result.volumeConfirmationScore());
        assertEquals(null, result.failureRiskScore());
    }

    private static class StubPort implements LoadCompanyReadPort {
        @Override
        public SearchResult search(String normalizedQuery, int limit) {
            return null;
        }

        @Override
        public SummaryResult summaries(List<String> normalizedTickers) {
            return null;
        }

        @Override
        public Research detail(String normalizedTicker) {
            return null;
        }
    }
}
