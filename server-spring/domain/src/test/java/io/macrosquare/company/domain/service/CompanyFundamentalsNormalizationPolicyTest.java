package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyFundamentalsEvidence;
import io.macrosquare.company.domain.model.FinancialFactPoint;
import io.macrosquare.company.domain.model.CompanyMarketValuationEvidence;
import io.macrosquare.company.domain.model.CompanyValuationQuality;
import io.macrosquare.company.domain.model.Ticker;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyFundamentalsNormalizationPolicyTest {

    private final CompanyFundamentalsNormalizationPolicy policy = new CompanyFundamentalsNormalizationPolicy();

    @Test
    void reproducesTheLegacySecFactNormalizationRules() {
        var snapshot = policy.normalize(
                new Ticker("test"),
                "0000000001",
                sampleEvidence(),
                50.0,
                LocalDate.parse("2026-07-19")
        );

        assertEquals("TEST", snapshot.ticker().value());
        assertEquals("2025-12-31", snapshot.asOf());
        assertEquals(500.0, snapshot.revenueTtm());
        assertEquals(125.0, snapshot.operatingIncomeTtm());
        assertEquals(92.0, snapshot.netIncomeTtm());
        assertEquals(94.0, snapshot.freeCashFlowTtm());
        assertEquals(500.0, snapshot.marketCap());
        assertEquals(440.0, snapshot.enterpriseValue());
        assertEquals(25.0, snapshot.revenueGrowthYoY());
        assertEquals(25.0, snapshot.operatingMargin());
        assertEquals(18.8, snapshot.freeCashFlowMargin(), 1e-12);
        assertEquals(-0.12, snapshot.netDebtToRevenue(), 1e-12);
        assertEquals(0.88, snapshot.evToSales(), 1e-12);
        assertEquals(440.0 / 94.0, snapshot.evToFcf(), 1e-12);
        assertEquals(18.4, snapshot.roe(), 1e-12);
        assertEquals(1.75, snapshot.currentRatio(), 1e-12);
        assertEquals(0.15, snapshot.receivablesToRevenue(), 1e-12);
        assertEquals(0.06, snapshot.inventoryToRevenue(), 1e-12);
        assertNull(snapshot.operatingMarginTrend());
        assertNull(snapshot.shareDilutionYoY());
        assertNull(snapshot.stockCompToRevenue());
    }

    @Test
    void fallsBackToAnnualValuesAndRejectsOutOfBoundsRatiosWithoutFabricatingFacts() {
        var revenue = List.of(
                annual("2025-12-31", 100),
                annual("2024-12-31", 80)
        );
        var evidence = new CompanyFundamentalsEvidence(
                revenue,
                List.of(annual("2025-12-31", 150), annual("2024-12-31", 80)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(point("2025-12-31", 99)),
                List.of(point("2025-12-31", 70))
        );

        var snapshot = policy.normalize(
                new Ticker("EDGE"),
                "1",
                evidence,
                null,
                LocalDate.parse("2026-07-19")
        );

        assertEquals(100.0, snapshot.revenueTtm());
        assertEquals(25.0, snapshot.revenueGrowthYoY());
        assertNull(snapshot.operatingMargin());
        assertNull(snapshot.receivables());
        assertNull(snapshot.inventory());
        assertNull(snapshot.marketCap());
        assertNull(snapshot.enterpriseValue());
    }

    @Test
    void usesTheSuppliedDateOnlyWhenAllDatingSeriesAreEmpty() {
        var empty = new CompanyFundamentalsEvidence(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalize(
                new Ticker("NONE"),
                "1",
                empty,
                null,
                LocalDate.parse("2026-07-19")
        );

        assertEquals("2026-07-19", snapshot.asOf());
        assertNull(snapshot.revenueTtm());
        assertEquals(0, new CompanyScoringPolicy().evaluate(snapshot.scoringFinancials()).totalScore());
    }

    @Test
    void ignoresFutureDatedFactsInsteadOfTreatingThemAsCurrentEvidence() {
        var revenue = List.of(
                annual("2027-12-31", 10_000),
                annual("2025-12-31", 100),
                annual("2024-12-31", 80)
        );
        var evidence = new CompanyFundamentalsEvidence(
                revenue,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalize(
                new Ticker("FUTURE"), "1", evidence, null, LocalDate.parse("2026-07-19")
        );

        assertEquals(100.0, snapshot.revenueTtm());
        assertEquals("2025-12-31", snapshot.asOf());
    }

    @Test
    void normalizesRoicEffectiveTaxDilutionAndAccrualQualityFromDirectFacts() {
        var evidence = new CompanyFundamentalsEvidence(
                List.of(
                        quarter("Q1", "2025-03-31", 125), quarter("Q2", "2025-06-30", 125),
                        quarter("Q3", "2025-09-30", 125), quarter("Q4", "2025-12-31", 125)
                ),
                List.of(
                        quarter("Q1", "2025-03-31", 30), quarter("Q2", "2025-06-30", 30),
                        quarter("Q3", "2025-09-30", 30), quarter("Q4", "2025-12-31", 30),
                        annual("2025-12-31", 120)
                ),
                List.of(
                        quarter("Q1", "2025-03-31", 20), quarter("Q2", "2025-06-30", 20),
                        quarter("Q3", "2025-09-30", 20), quarter("Q4", "2025-12-31", 20),
                        annual("2025-12-31", 80)
                ),
                List.of(
                        quarter("Q1", "2025-03-31", 25), quarter("Q2", "2025-06-30", 25),
                        quarter("Q3", "2025-09-30", 25), quarter("Q4", "2025-12-31", 25),
                        annual("2025-12-31", 100)
                ),
                List.of(),
                List.of(annual("2025-12-31", 50), annual("2024-12-31", 40)),
                List.of(annual("2025-12-31", 100), annual("2024-12-31", 80)),
                List.of(
                        annual("2025-12-31", 133.1), annual("2024-12-31", 121),
                        annual("2023-12-31", 110), annual("2022-12-31", 100)
                ),
                List.of(),
                List.of(annual("2025-12-31", 500), annual("2024-12-31", 450)),
                List.of(), List.of(), List.of(), List.of(),
                List.of(
                        quarter("Q1", "2025-03-31", 25), quarter("Q2", "2025-06-30", 25),
                        quarter("Q3", "2025-09-30", 25), quarter("Q4", "2025-12-31", 25),
                        annual("2025-12-31", 100)
                ),
                List.of(
                        quarter("Q1", "2025-03-31", 5), quarter("Q2", "2025-06-30", 5),
                        quarter("Q3", "2025-09-30", 5), quarter("Q4", "2025-12-31", 5),
                        annual("2025-12-31", 20)
                ),
                List.of(annual("2025-12-31", 1_000), annual("2024-12-31", 900))
        );

        var snapshot = policy.normalize(
                new Ticker("QUALITY"), "1", evidence, 10.0, LocalDate.parse("2026-07-19"));

        assertEquals(18.4615, snapshot.roic(), 0.001);
        assertEquals(20.0, snapshot.effectiveTaxRate(), 1e-9);
        assertEquals(false, snapshot.roicEstimated());
        assertEquals(10.0, snapshot.shareDilution3yCagr(), 0.02);
        assertEquals(-2.1053, snapshot.accrualRatio(), 0.001);
    }

    @Test
    void derivesTrueTtmFromAnnualPlusCurrentYtdMinusComparableYtd() {
        var revenue = List.of(
                durationAnnual("2025-01-01", "2025-12-31", 100),
                durationAnnual("2024-01-01", "2024-12-31", 90),
                durationQuarter("Q2", "2024-01-01", "2024-06-30", 40),
                durationQuarter("Q2", "2025-01-01", "2025-06-30", 50),
                durationQuarter("Q2", "2025-04-01", "2025-06-30", 30),
                durationQuarter("Q2", "2026-01-01", "2026-06-30", 70),
                durationQuarter("Q2", "2026-04-01", "2026-06-30", 40)
        );
        var evidence = new CompanyFundamentalsEvidence(
                revenue,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalize(
                new Ticker("TTM"), "1", evidence, null, LocalDate.parse("2026-07-19"));

        assertEquals(120.0, snapshot.revenueTtm());
        // Prior comparable TTM = FY2024 90 + H1 2025 50 - H1 2024 40 = 100.
        // This must not fall back to the stale FY2025/FY2024 growth of 11.1%.
        assertEquals(20.0, snapshot.revenueGrowthYoY(), 1e-12);
    }

    @Test
    void usesRestatedWeightedDilutedSharesInsteadOfTreatingAStockSplitAsDilution() {
        var splitAffectedPointInTimeShares = List.of(
                annual("2025-12-31", 1_060), annual("2024-12-31", 1_040),
                annual("2023-12-31", 102), annual("2022-12-31", 100)
        );
        var splitAdjustedDilutedShares = List.of(
                durationAnnual("2025-01-01", "2025-12-31", 106),
                durationAnnual("2024-01-01", "2024-12-31", 104),
                durationAnnual("2023-01-01", "2023-12-31", 102),
                durationAnnual("2022-01-01", "2022-12-31", 100)
        );
        var evidence = new CompanyFundamentalsEvidence(
                List.of(annual("2025-12-31", 500)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                splitAffectedPointInTimeShares,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), splitAdjustedDilutedShares
        );

        var snapshot = policy.normalize(
                new Ticker("SPLIT"), "1", evidence, 1.0, LocalDate.parse("2026-07-19"));

        assertEquals(1.9231, snapshot.shareDilutionYoY(), 0.001);
        assertEquals(1.9605, snapshot.shareDilution3yCagr(), 0.001);
        assertEquals(1_060.0, snapshot.sharesOutstanding());
    }

    @Test
    void suppressesLongTermDilutionWhenOlderCompanyFactsRemainOnAPreSplitBasis() {
        var partiallyRestatedDilutedShares = List.of(
                durationAnnual("2025-01-01", "2025-12-31", 416),
                durationAnnual("2024-01-01", "2024-12-31", 420),
                durationAnnual("2023-01-01", "2023-12-31", 425),
                durationAnnual("2022-01-01", "2022-12-31", 43)
        );
        var evidence = new CompanyFundamentalsEvidence(
                List.of(annual("2025-12-31", 500)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(point("2025-12-31", 416)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), partiallyRestatedDilutedShares
        );

        var snapshot = policy.normalize(
                new Ticker("SPLIT3Y"), "1", evidence, 1.0, LocalDate.parse("2026-07-19"));

        assertEquals(-0.9524, snapshot.shareDilutionYoY(), 0.001);
        assertNull(snapshot.shareDilution3yCagr());
    }

    @Test
    void independentMarketCapPreventsPreSplitSecSharesFromCorruptingValuation() {
        var evidence = new CompanyFundamentalsEvidence(
                List.of(annual("2025-12-31", 10_000)),
                List.of(), List.of(), List.of(), List.of(),
                List.of(point("2026-03-31", 1_000)),
                List.of(point("2026-03-31", 2_000)),
                List.of(point("2026-03-31", 10)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalizeWithMarketEvidence(
                new Ticker("SPLITMC"),
                "1",
                evidence,
                new CompanyMarketValuationEvidence(
                        100.0,
                        LocalDate.parse("2026-08-05"),
                        10_000.0,
                        LocalDate.parse("2026-08-04"),
                        100.0
                ),
                LocalDate.parse("2026-08-06")
        );

        assertEquals(10_000.0, snapshot.marketCap());
        assertEquals(100.0, snapshot.sharesOutstanding());
        assertEquals(11_000.0, snapshot.enterpriseValue());
        assertEquals(1.1, snapshot.evToSales(), 1e-12);
        assertEquals(10.0, snapshot.valuationQuality().detectedSplitFactor());
        assertEquals(CompanyValuationQuality.MarketCapitalizationBasis.INDEPENDENT_MARKET_CAP,
                snapshot.valuationQuality().basis());
        assertTrue(snapshot.valuationQuality().valuationEligible());
    }

    @Test
    void klaTenForOneRegressionNeverMultipliesAPostSplitQuoteByPreSplitSecShares() {
        var evidence = new CompanyFundamentalsEvidence(
                List.of(annual("2026-06-30", 13_579_476_000.0)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(point("2026-03-31", 130_627_521.0)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalizeWithMarketEvidence(
                new Ticker("KLAC"), "319201", evidence,
                new CompanyMarketValuationEvidence(
                        193.22, LocalDate.parse("2026-08-06"),
                        252_398_500_016.66, LocalDate.parse("2026-08-06"), 193.22),
                LocalDate.parse("2026-08-07"));

        assertEquals(1_306_275_230.39, snapshot.sharesOutstanding(), 0.01);
        assertEquals(252_398_500_016.66, snapshot.marketCap(), 0.01);
        assertEquals(10.0, snapshot.valuationQuality().detectedSplitFactor());
        assertTrue(snapshot.valuationQuality().valuationEligible());
        assertTrue(snapshot.evToSales() > 18.0);
        assertTrue(snapshot.scoringFinancials().evToSales() > 18.0);
    }

    @Test
    void sameDayQuoteAndMarketCapReferenceOnDifferentSplitBasesQuarantinesValuation() {
        var evidence = new CompanyFundamentalsEvidence(
                List.of(annual("2026-06-30", 10_000)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(point("2026-03-31", 100)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalizeWithMarketEvidence(
                new Ticker("BASIS"), "1", evidence,
                new CompanyMarketValuationEvidence(
                        100.0, LocalDate.parse("2026-08-06"),
                        100_000.0, LocalDate.parse("2026-08-06"), 1_000.0),
                LocalDate.parse("2026-08-07"));

        assertFalse(snapshot.valuationQuality().valuationEligible());
        assertNull(snapshot.evToSales());
        assertNull(snapshot.evToFcf());
        assertNull(snapshot.scoringFinancials().evToSales());
        assertTrue(snapshot.valuationQuality().warnings().stream()
                .anyMatch(value -> value.contains("inconsistent price bases")));
    }

    @Test
    void unexplainedLargeShareMismatchQuarantinesValuationInsteadOfPublishingAFalseCheapScore() {
        var evidence = new CompanyFundamentalsEvidence(
                List.of(annual("2026-06-30", 10_000)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(point("2026-03-31", 100)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalizeWithMarketEvidence(
                new Ticker("MISMATCH"), "1", evidence,
                new CompanyMarketValuationEvidence(
                        100.0, LocalDate.parse("2026-08-06"),
                        17_000.0, LocalDate.parse("2026-08-06"), 100.0),
                LocalDate.parse("2026-08-07"));

        assertFalse(snapshot.valuationQuality().valuationEligible());
        assertNull(snapshot.evToSales());
        assertTrue(snapshot.valuationQuality().warnings().stream()
                .anyMatch(value -> value.contains("publication limit")));
    }

    @Test
    void independentMarketCapWithoutItsReferenceCloseIsNeverScored() {
        var evidence = new CompanyFundamentalsEvidence(
                List.of(annual("2026-06-30", 10_000)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(point("2026-03-31", 100)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalizeWithMarketEvidence(
                new Ticker("NOREF"), "1", evidence,
                new CompanyMarketValuationEvidence(
                        100.0, LocalDate.parse("2026-08-06"),
                        10_000.0, LocalDate.parse("2026-08-06")),
                LocalDate.parse("2026-08-07"));

        assertFalse(snapshot.valuationQuality().valuationEligible());
        assertNull(snapshot.marketCap());
        assertNull(snapshot.evToSales());
        assertTrue(snapshot.valuationQuality().warnings().stream()
                .anyMatch(value -> value.contains("reference close")));
    }

    @Test
    void rollsADatedIndependentMarketCapToTheCurrentQuoteWithoutMovingTheShareCount() {
        var evidence = new CompanyFundamentalsEvidence(
                List.of(annual("2025-12-31", 10_000)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(point("2026-03-31", 100)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalizeWithMarketEvidence(
                new Ticker("ROLLMC"), "1", evidence,
                new CompanyMarketValuationEvidence(
                        120.0, LocalDate.parse("2026-08-06"),
                        10_000.0, LocalDate.parse("2026-08-04"), 100.0),
                LocalDate.parse("2026-08-06"));

        assertEquals(100.0, snapshot.sharesOutstanding());
        assertEquals(12_000.0, snapshot.marketCap());
        assertEquals("2026-08-06", snapshot.valuationQuality().marketCapAsOf().toString());
    }

    @Test
    void missingOrStaleIndependentMarketCapSuppressesValuationInProductionPath() {
        var evidence = new CompanyFundamentalsEvidence(
                List.of(annual("2025-12-31", 1_000)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(point("2026-03-31", 10)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalizeWithMarketEvidence(
                new Ticker("STALEMC"),
                "1",
                evidence,
                new CompanyMarketValuationEvidence(
                        100.0,
                        LocalDate.parse("2026-08-05"),
                        900.0,
                        LocalDate.parse("2026-07-01")
                ),
                LocalDate.parse("2026-08-06")
        );

        assertNull(snapshot.marketCap());
        assertNull(snapshot.enterpriseValue());
        assertNull(snapshot.evToSales());
        assertEquals(CompanyValuationQuality.MarketCapitalizationBasis.UNAVAILABLE,
                snapshot.valuationQuality().basis());
    }

    @Test
    void roeUsesAverageEquityAndMarginTrendJoinsTheSameFiscalDates() {
        var evidence = new CompanyFundamentalsEvidence(
                List.of(
                        annual("2025-12-31", 500),
                        annual("2024-12-31", 400),
                        annual("2023-12-31", 1_000)
                ),
                List.of(
                        annual("2025-12-31", 100),
                        annual("2023-12-31", 300),
                        annual("2022-12-31", 50)
                ),
                List.of(annual("2025-12-31", 90)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(annual("2025-12-31", 500), annual("2024-12-31", 400)),
                List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalize(
                new Ticker("ALIGN"), "1", evidence, null, LocalDate.parse("2026-08-06"));

        assertEquals(20.0, snapshot.roe(), 1e-12);
        assertNull(snapshot.operatingMarginTrend());
    }

    @Test
    void staleLegacyConceptCannotBeCombinedWithCurrentRevenueIntoAFakeMargin() {
        var evidence = new CompanyFundamentalsEvidence(
                List.of(durationAnnual("2025-01-01", "2025-12-31", 1_000)),
                List.of(durationAnnual("2018-01-01", "2018-12-31", 250)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalize(
                new Ticker("STALEOP"), "1", evidence, null, LocalDate.parse("2026-08-06"));

        assertEquals(1_000.0, snapshot.revenueTtm());
        assertNull(snapshot.operatingIncomeTtm());
        assertNull(snapshot.operatingMargin());
    }

    @Test
    void staleAnnualTtmCannotBeRevivedByNonContinuousRecentQuarterFacts() {
        var revenue = List.of(
                durationAnnual("2025-01-01", "2025-12-31", 1_000),
                durationQuarter("Q2", "2025-01-01", "2025-06-30", 480),
                durationQuarter("Q2", "2026-01-01", "2026-06-30", 560)
        );
        var operatingIncome = List.of(
                durationAnnual("2023-01-01", "2023-12-31", 100),
                durationQuarter("Q2", "2023-01-01", "2023-06-30", 45),
                durationQuarter("Q2", "2024-01-01", "2024-06-30", 55),
                durationQuarter("Q1", "2025-01-01", "2025-03-31", 30),
                durationQuarter("Q2", "2025-04-01", "2025-06-30", 35),
                durationQuarter("Q1", "2026-01-01", "2026-03-31", 40),
                durationQuarter("Q2", "2026-04-01", "2026-06-30", 45)
        );
        var evidence = new CompanyFundamentalsEvidence(
                revenue, operatingIncome,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalize(
                new Ticker("STALETTM"), "1", evidence, null, LocalDate.parse("2026-08-06"));

        assertEquals(1_080.0, snapshot.revenueTtm());
        assertNull(snapshot.operatingIncomeTtm());
        assertNull(snapshot.operatingMargin());
    }

    @Test
    void legacyQuarterFactsFromDifferentYearsCannotBeSummedIntoTtm() {
        var evidence = new CompanyFundamentalsEvidence(
                List.of(durationAnnual("2025-01-01", "2025-12-31", 1_000)),
                List.of(
                        quarter("Q1", "2023-03-31", 10),
                        quarter("Q2", "2024-06-30", 20),
                        quarter("Q3", "2025-09-30", 30),
                        quarter("Q4", "2026-12-31", 40)
                ),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalize(
                new Ticker("SPARSE"), "1", evidence, null, LocalDate.parse("2027-01-15"));

        assertNull(snapshot.operatingIncomeTtm());
        assertNull(snapshot.operatingMargin());
    }

    @Test
    void newerBalanceSheetPointCannotMakeAnOlderRevenueModelLookCurrent() {
        var evidence = new CompanyFundamentalsEvidence(
                List.of(annual("2026-03-31", 1_000), annual("2025-03-31", 900)),
                List.of(), List.of(), List.of(), List.of(),
                List.of(point("2026-06-30", 200)),
                List.of(point("2026-06-30", 100)),
                List.of(point("2026-07-31", 10)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalize(
                new Ticker("ASOF"), "1", evidence, 10.0, LocalDate.parse("2026-08-06"));

        assertEquals("2026-03-31", snapshot.asOf());
    }

    @Test
    void form20FAnnualFactsParticipateInGrowthAndTtmNormalization() {
        var revenue = List.of(
                new FinancialFactPoint(125, "20-F", "FY", "2025-12-31", "2025-01-01"),
                new FinancialFactPoint(100, "20-F", "FY", "2024-12-31", "2024-01-01")
        );
        var evidence = new CompanyFundamentalsEvidence(
                revenue, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        var snapshot = policy.normalize(
                new Ticker("FPI"), "1", evidence, null, LocalDate.parse("2026-08-06"));

        assertEquals(125.0, snapshot.revenueTtm());
        assertEquals(25.0, snapshot.revenueGrowthYoY());
    }

    @Test
    void derivesRevenueOnlyFromPeriodAlignedOperatingIncomeAndTotalCosts() {
        var evidence = new CompanyFundamentalsEvidence(
                List.of(),
                List.of(annual("2025-12-31", 30), annual("2024-12-31", 20)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(annual("2025-12-31", 70), annual("2024-12-31", 60))
        );

        var snapshot = policy.normalize(
                new Ticker("IDENTITY"), "1", evidence, null, LocalDate.parse("2026-08-06"));

        assertEquals(100.0, snapshot.revenueTtm());
        assertEquals(25.0, snapshot.revenueGrowthYoY());
        assertEquals(30.0, snapshot.operatingMargin());
    }

    private static CompanyFundamentalsEvidence sampleEvidence() {
        return new CompanyFundamentalsEvidence(
                List.of(
                        quarter("Q1", "2025-03-31", 110), quarter("Q2", "2025-06-30", 120),
                        quarter("Q3", "2025-09-30", 130), quarter("Q4", "2025-12-31", 140),
                        annual("2025-12-31", 500), annual("2024-12-31", 400)
                ),
                List.of(
                        quarter("Q1", "2025-03-31", 28), quarter("Q2", "2025-06-30", 30),
                        quarter("Q3", "2025-09-30", 32), quarter("Q4", "2025-12-31", 35)
                ),
                List.of(
                        quarter("Q1", "2025-03-31", 20), quarter("Q2", "2025-06-30", 22),
                        quarter("Q3", "2025-09-30", 24), quarter("Q4", "2025-12-31", 26)
                ),
                List.of(
                        quarter("Q1", "2025-03-31", 30), quarter("Q2", "2025-06-30", 31),
                        quarter("Q3", "2025-09-30", 33), quarter("Q4", "2025-12-31", 35)
                ),
                List.of(
                        quarter("Q1", "2025-03-31", 8), quarter("Q2", "2025-06-30", 8),
                        quarter("Q3", "2025-09-30", 9), quarter("Q4", "2025-12-31", 10)
                ),
                List.of(point("2025-12-31", 150)),
                List.of(point("2025-12-31", 90)),
                List.of(point("2025-12-31", 10)),
                List.of(),
                List.of(point("2025-12-31", 500)),
                List.of(point("2025-12-31", 210)),
                List.of(point("2025-12-31", 120)),
                List.of(point("2025-12-31", 75)),
                List.of(point("2025-12-31", 30))
        );
    }

    private static FinancialFactPoint quarter(String fiscalPeriod, String endDate, double value) {
        return new FinancialFactPoint(value, "10-Q", fiscalPeriod, endDate);
    }

    private static FinancialFactPoint annual(String endDate, double value) {
        return new FinancialFactPoint(value, "10-K", "FY", endDate);
    }

    private static FinancialFactPoint durationAnnual(String startDate, String endDate, double value) {
        return new FinancialFactPoint(value, "10-K", "FY", endDate, startDate);
    }

    private static FinancialFactPoint durationQuarter(
            String fiscalPeriod,
            String startDate,
            String endDate,
            double value
    ) {
        return new FinancialFactPoint(value, "10-Q", fiscalPeriod, endDate, startDate);
    }

    private static FinancialFactPoint point(String endDate, double value) {
        return new FinancialFactPoint(value, null, null, endDate);
    }
}
