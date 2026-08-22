package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyGuidanceMetric.Direction;
import io.macrosquare.company.domain.model.CompanyGuidanceMetricValue.Unit;
import io.macrosquare.company.domain.model.CompanyGuidanceSummary.Stance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyGuidanceParsingPolicyTest {

    private final CompanyGuidanceParsingPolicy policy = new CompanyGuidanceParsingPolicy();

    @Test
    void preservesLegacyNaturalLanguageAndBoundedValueCharacterization() {
        var lowDouble = policy.parseValue("revenue growth expected in the low double digits");
        assertEquals(Unit.PERCENT, lowDouble.unit());
        assertDecimal("10", lowDouble.min());
        assertDecimal("13", lowDouble.max());

        var midForties = policy.parseValue("gross margin is expected in the mid-40s");
        assertEquals(Unit.PERCENT, midForties.unit());
        assertDecimal("43", midForties.min());
        assertDecimal("46", midForties.max());

        var capex = policy.parseValue("capex between $3 and $4 billion this year");
        assertEquals(Unit.USD, capex.unit());
        assertDecimal("3000000000", capex.min());
        assertDecimal("4000000000", capex.max());

        var atLeast = policy.parseValue("operating margin at least 15%");
        assertDecimal("15", atLeast.min());
        assertNull(atLeast.max());

        var upTo = policy.parseValue("capex up to $750 million");
        assertNull(upTo.min());
        assertDecimal("750000000", upTo.max());
    }

    @Test
    void preservesCurrencyPrefixedRangeBoundsAndCurrencyUnit() {
        var tsm = policy.parseValue(
                "Revenue is expected to be between US$44.6 billion and US$45.8 billion"
        );
        assertEquals(Unit.USD, tsm.unit());
        assertDecimal("44600000000", tsm.min());
        assertDecimal("45800000000", tsm.max());

        var asml = policy.parseValue(
                "Full-year revenue is expected to be between €43 billion and €45 billion"
        );
        assertEquals(Unit.EUR, asml.unit());
        assertDecimal("43000000000", asml.min());
        assertDecimal("45000000000", asml.max());
    }

    @Test
    void recognizesDirectionsBeforeMetricsThatTheLegacyOneWayRegexMissed() {
        var summary = policy.summarize(
                "The company raised revenue guidance to low double digits, "
                        + "affirmed gross margin in the mid-40s and expects capex between $3 and $4 billion."
        );

        assertEquals(Stance.MIXED, summary.stance());
        assertEquals(Direction.RAISED, summary.revenue().direction());
        assertEquals(Direction.AFFIRMED, summary.margin().direction());
        assertEquals(Direction.MENTIONED, summary.capex().direction());
        assertDecimal("10", summary.revenue().value().min());
        assertDecimal("13", summary.revenue().value().max());
        assertDecimal("43", summary.margin().value().min());
        assertDecimal("46", summary.margin().value().max());
        assertDecimal("3000000000", summary.capex().value().min());
        assertDecimal("4000000000", summary.capex().value().max());
        assertEquals(3, summary.structuredMetricCount());
    }

    @Test
    void handlesLoweredAndAffirmedCashFlowAndCapexGuidance() {
        var summary = policy.summarize(
                "Management lowered free cash flow guidance to between $1.8 and $2.0 billion; "
                        + "the company maintained capital spending guidance at approximately $750 million."
        );

        assertEquals(Stance.MIXED, summary.stance());
        assertEquals(Direction.LOWERED, summary.freeCashFlow().direction());
        assertDecimal("1800000000", summary.freeCashFlow().value().min());
        assertDecimal("2000000000", summary.freeCashFlow().value().max());
        assertEquals(Direction.AFFIRMED, summary.capex().direction());
        assertDecimal("750000000", summary.capex().value().min());
        assertDecimal("750000000", summary.capex().value().max());
    }

    @Test
    void prefersOutlookOverEarlierActualResultsAndCalculatesPlusMinusBounds() {
        var summary = policy.summarize(
                "Record revenue was $81.6 billion, up 85% from a year ago. "
                        + "Outlook for the second quarter of fiscal 2027 is as follows: "
                        + "• Revenue is expected to be $91.0 billion, plus or minus 2%. "
                        + "• GAAP and non-GAAP gross margins are expected to be 74.9% and 75.0%, "
                        + "respectively, plus or minus 50 basis points. "
                        + "Stock-based compensation expense for the first quarter is expected to have "
                        + "a 0.1% impact on non-GAAP gross margin and $1.9 billion in operating expenses."
        );

        assertEquals(Stance.UNCLEAR, summary.stance());
        assertEquals(Direction.MENTIONED, summary.revenue().direction());
        assertEquals(Unit.USD, summary.revenue().value().unit());
        assertDecimal("89180000000", summary.revenue().value().min());
        assertDecimal("92820000000", summary.revenue().value().max());
        assertEquals(Unit.PERCENT, summary.margin().value().unit());
        assertDecimal("74.4", summary.margin().value().min());
        assertDecimal("75.5", summary.margin().value().max());
        assertNull(summary.capex());
        assertNull(summary.freeCashFlow());
    }

    @Test
    void prefersNearbyStructuredOutlookOverTablesAndQualitativeCaveatsInNormalizedSecText() {
        var summary = policy.summarize(
                "Gross margin 75.0 % 75.1 % 60.8 % Operating expenses $7,449 $6,666 $4,993 "
                        + "Outlook NVIDIA's outlook for the second quarter of fiscal 2027 is as follows: "
                        + "• Revenue is expected to be $91.0 billion, plus or minus 2%. "
                        + "NVIDIA is not assuming any Data Center compute revenue from China in its outlook. "
                        + "• GAAP and non-GAAP gross margins are expected to be 74.9% and 75.0%, "
                        + "respectively, plus or minus 50 basis points."
        );

        assertTrue(summary.revenue().text().contains("$91.0 billion"));
        assertDecimal("89180000000", summary.revenue().value().min());
        assertDecimal("92820000000", summary.revenue().value().max());
        assertTrue(summary.margin().text().contains("gross margins are expected"));
        assertDecimal("74.4", summary.margin().value().min());
        assertDecimal("75.5", summary.margin().value().max());
    }

    @Test
    void detectsDirectionWhenTheActionFollowsTheMetric() {
        var summary = policy.summarize(
                "Revenue guidance was lowered to 4% to 6%. "
                        + "The company reiterated its operating margin outlook at approximately 20%."
        );

        assertEquals(Stance.MIXED, summary.stance());
        assertEquals(Direction.LOWERED, summary.revenue().direction());
        assertDecimal("4", summary.revenue().value().min());
        assertDecimal("6", summary.revenue().value().max());
        assertEquals(Direction.AFFIRMED, summary.margin().direction());
        assertDecimal("20", summary.margin().value().min());
        assertDecimal("20", summary.margin().value().max());
    }

    @Test
    void classifiesPresentParticipleGuidanceActionsFromEarningsReleaseHeadlines() {
        var raised = policy.summarize(
                "Increasing full year 2026 diluted net income per share guidance in the range of $10.50 to $11.00. "
                        + "We expect third quarter 2026 consolidated net sales to be up a mid to high-single digit percentage."
        );
        assertEquals(Stance.RAISED, raised.stance());

        var lowered = policy.summarize(
                "Lowering full year 2026 adjusted earnings guidance because of weaker demand. "
                        + "We expect fourth-quarter revenue growth of 3% to 4%."
        );
        assertEquals(Stance.LOWERED, lowered.stance());

        var historical = policy.summarize(
                "Revenue increased 12% in the reported quarter. We expect next-quarter revenue growth of 4% to 5%."
        );
        assertEquals(Stance.UNCLEAR, historical.stance());
    }

    @Test
    void classifiesReaffirmingAndPriorRangeComparisons() {
        var affirmed = policy.summarize(
                "We are reaffirming 2026 guidance of mid-single-digit or greater organic revenue growth."
        );
        assertEquals(Stance.AFFIRMED, affirmed.stance());
        assertEquals(Direction.AFFIRMED, affirmed.revenue().direction());

        var raised = policy.summarize(
                "Axon expects full-year 2026 revenue growth in a range of 32% to 34%, "
                        + "an increase from 30% to 32% previously."
        );
        assertEquals(Stance.RAISED, raised.stance());
        assertEquals(Direction.RAISED, raised.revenue().direction());

        var lowered = policy.summarize(
                "We expect full-year revenue growth of 3% to 4%, a decrease from 5% to 6% previously."
        );
        assertEquals(Stance.LOWERED, lowered.stance());
        assertEquals(Direction.LOWERED, lowered.revenue().direction());
    }

    @Test
    void rejectsHistoricalDefinitionsAndSafeHarborBoilerplate() {
        var summary = policy.summarize(
                "Revenue was a record $81.6 billion for the quarter ended April 26. "
                        + "Free cash flow is calculated as net cash less purchases of property and equipment. "
                        + "Forward-looking statements may contain words such as outlook and forecast."
        );

        assertFalse(summary.relevant());
        assertEquals(0, summary.structuredMetricCount());
        assertTrue(summary.evidence().isEmpty());
    }

    @Test
    void rejectsDeferredRevenueActualMovementsForecastComparisonsAndCashFlowTables() {
        var summary = policy.summarize(
                "The Company expects 64% of total deferred revenue to be realized within a year. "
                        + "Devices revenue increased 1% and was relatively unchanged in constant currency. "
                        + "AI revenue of $10.8 billion grew 143% year-over-year, above our forecast. "
                        + "Free cash flow $8,010 $7,466 $6,013. "
                        + "Fiscal quarter ending May 3 expected average diluted share count was 4,100. "
                        + "Guidance Net Revenue (US$ billions) 40.20 39.0-40.2 35.90 +12.0%. "
                        + "Free Cash Flow (non-GAAP) 2,699 5,566 8,840. "
                        + "The company expects to fund the highest-return free cash flow generative projects."
        );

        assertNull(summary.revenue());
        assertNull(summary.margin());
        assertNull(summary.capex());
        assertNull(summary.freeCashFlow());
        assertFalse(summary.relevant());
    }

    @Test
    void selectsExplicitCompanyOutlookInsteadOfHeadlineActualsOrFxAssumptions() {
        var asml = policy.summarize(
                "ASML reports €9.3 billion total net sales in Q2 2026. "
                        + "ASML increases its outlook and expects full-year 2026 total net sales "
                        + "to be between €43 billion and €45 billion."
        );
        assertEquals(Unit.EUR, asml.revenue().value().unit());
        assertDecimal("43000000000", asml.revenue().value().min());
        assertDecimal("45000000000", asml.revenue().value().max());

        var meta = policy.summarize(
                "Our guidance assumes foreign currency is an approximately 2% tailwind to revenue growth. "
                        + "We expect second-quarter total revenue to be in the range of "
                        + "$53.5 billion to $56.5 billion."
        );
        assertEquals(Unit.USD, meta.revenue().value().unit());
        assertDecimal("53500000000", meta.revenue().value().min());
        assertDecimal("56500000000", meta.revenue().value().max());
    }

    @Test
    void classifiesPriorRangeChangesAndRejectsEnumeratedSafeHarborTopics() {
        var meta = policy.summarize(
                "We expect full year 2026 total expenses to remain unchanged from our prior outlook. "
                        + "We anticipate 2026 capital expenditures to be in the range of "
                        + "$125-145 billion, increased from our prior range of $115-135 billion."
        );
        assertEquals(Stance.MIXED, meta.stance());
        assertEquals(Direction.RAISED, meta.capex().direction());
        assertDecimal("125000000000", meta.capex().value().min());
        assertDecimal("145000000000", meta.capex().value().max());

        var exxon = policy.summarize(
                "Cash capital expenditures totaled $6.2 billion, consistent with the company's "
                        + "full-year guidance range of $27-$29 billion."
        );
        assertEquals(Stance.AFFIRMED, exxon.stance());
        assertEquals(Direction.AFFIRMED, exxon.capex().direction());
        assertDecimal("27000000000", exxon.capex().value().min());
        assertDecimal("29000000000", exxon.capex().value().max());

        var safeHarbor = policy.summarize(
                "Forward-looking topics include gross margin growth plans, outlook including expected "
                        + "sales of market segments and geographies, and expected financial results."
        );
        assertFalse(safeHarbor.relevant());
        assertNull(safeHarbor.margin());
    }

    @Test
    void normalizesDescendingRangesBeforeApplyingTolerance() {
        var margin = policy.parseValue(
                "Gross margin is expected to be 6% to 4%, plus or minus 50 basis points."
        );
        assertEquals(Unit.PERCENT, margin.unit());
        assertDecimal("3.5", margin.min());
        assertDecimal("6.5", margin.max());

        var basisPoints = policy.parseValue(
                "The expected impact is 150 to 100 basis points."
        );
        assertEquals(Unit.BPS, basisPoints.unit());
        assertDecimal("100", basisPoints.min());
        assertDecimal("150", basisPoints.max());
    }

    @Test
    void treatsAReportedToleranceAsAnAbsoluteDistance() {
        var margin = policy.parseValue(
                "Gross margin is expected to be 40%, plus or minus -2 percentage points."
        );

        assertEquals(Unit.PERCENT, margin.unit());
        assertDecimal("38", margin.min());
        assertDecimal("42", margin.max());
    }

    @Test
    void bindsCapexToTheValueAfterItsLabelInsteadOfThePrecedingEpsRange() {
        var summary = policy.summarize(
                "The company's outlook remains unchanged. Adjusted EPS $2.75 to $2.85 Unchanged "
                        + "Capital expenditures Approximately 3.5% of net sales."
        );

        assertEquals(Direction.AFFIRMED, summary.capex().direction());
        assertEquals(Unit.PERCENT, summary.capex().value().unit());
        assertDecimal("3.5", summary.capex().value().min());
        assertDecimal("3.5", summary.capex().value().max());
    }

    @Test
    void repairsSecWhitespaceInsideAPercentageDecimalWithoutJoiningYearBoundaries() {
        var summary = policy.summarize(
                "The Company issues guidance for FY27 with net sales expected to grow "
                        + "3. 5% to 4.5% in constant currency."
        );

        assertDecimal("3.5", summary.revenue().value().min());
        assertDecimal("4.5", summary.revenue().value().max());

        var sentenceBoundary = policy.parseValue("Fiscal 2026. 5% growth is expected.");
        assertDecimal("5", sentenceBoundary.min());
        assertDecimal("5", sentenceBoundary.max());
    }

    @Test
    void rejectsContractRevenueAccountingPolicyAsForwardGuidance() {
        var summary = policy.summarize(
                "We recognize estimated contract revenue and resulting income based on the measurement "
                        + "of progress toward completion as a percentage of the total project."
        );

        assertFalse(summary.relevant());
        assertNull(summary.revenue());
        assertEquals(Stance.UNCLEAR, summary.stance());
    }

    @Test
    void keepsDirectionalHeadlineWhenADistantNonGaapFootnoteSharesTheSameHtmlClause() {
        var summary = policy.summarize(
                "The Company maintained its sales and GAAP earnings per share guidance and updated its "
                        + "gross profit margin guidance for full year 2026. "
                        + "The Company now expects gross profit margin to be roughly flat (versus down previously) "
                        + "and still expects net sales to be up 2% to 6%. "
                        + "This table contains a large amount of unrelated historical content before it finally "
                        + "states that an asterisk indicates a non-GAAP financial measure."
        );

        assertEquals(Stance.MIXED, summary.stance());
        assertEquals(Direction.RAISED, summary.margin().direction());
        assertEquals(Direction.MENTIONED, summary.revenue().direction());
    }

    @Test
    void bindsRevenueToCurrentGuidanceColumnInsteadOfFollowingEbitdaRange() {
        var summary = policy.summarize(
                "2026 Guidance BWXT raised its 2026 guidance for revenue, adjusted EBITDA, non-GAAP EPS, "
                        + "and free cash flow. (In millions, except per share amounts) Year Ended Year Ending "
                        + "Year Ending December 31, 2025 December 31, 2026 December 31, 2026 Results "
                        + "Current Guidance Prior Guidance Revenue $3,198 ~$3,800 >$3,750 "
                        + "Adjusted EBITDA $574 $662 - $672 $650 - $665."
        );

        assertEquals(Stance.RAISED, summary.stance());
        assertEquals(Direction.MENTIONED, summary.revenue().direction());
        assertEquals(Unit.USD, summary.revenue().value().unit());
        assertDecimal("3800000000", summary.revenue().value().min());
        assertDecimal("3800000000", summary.revenue().value().max());
    }

    @Test
    void bindsRevenueToLatestQuarterlyGuidanceColumn() {
        var summary = policy.summarize(
                "Results Quarterly Guidance & Results Q1 FYE27 Guidance Q1 FYE27 Results "
                        + "Q2 FYE27 Guidance Revenue $1.26bn +/- $50m $1.289bn $1.38bn +/- $50m "
                        + "Non-GAAP operating expense ~$760m $733m ~$780m."
        );

        assertDecimal("1330000000", summary.revenue().value().min());
        assertDecimal("1430000000", summary.revenue().value().max());
    }

    @Test
    void bindsRevenueToUpdatedLowHighColumns() {
        var summary = policy.summarize(
                "The Company expects the following full year outlook: Original Updated Low High Low High "
                        + "Projected revenue change 0.4% 2.4% 1.1% 2.1% "
                        + "Projected opex change 2.7% 4.9% 3.0% 4.0%."
        );

        assertDecimal("1.1", summary.revenue().value().min());
        assertDecimal("2.1", summary.revenue().value().max());
    }

    @Test
    void keepsLeadingNaturalLanguageRevenueBandAheadOfUnrelatedBps() {
        var summary = policy.summarize(
                "We are reaffirming 2026 guidance of mid-single-digit or greater organic revenue growth, "
                        + "70-80 basis points of adjusted margin expansion."
        );

        assertEquals(Unit.PERCENT, summary.revenue().value().unit());
        assertDecimal("4", summary.revenue().value().min());
        assertDecimal("7", summary.revenue().value().max());
    }

    @Test
    void prefersFcfGrowthPercentageOverDistantDebtAmount() {
        var summary = policy.summarize(
                "The company lowered free cash flow growth guidance to approximately 4% to 5% year over year "
                        + "to reflect the impact of the $25 billion debt issuance."
        );

        assertEquals(Unit.PERCENT, summary.freeCashFlow().value().unit());
        assertDecimal("4", summary.freeCashFlow().value().min());
        assertDecimal("5", summary.freeCashFlow().value().max());
    }

    @Test
    void rejectsHistoricalTablesAndCompanyOperatingStatisticsAsGuidance() {
        var summary = policy.summarize(
                "Outlook June 30, 2026 December 31, 2025 (Unaudited) ASSETS Current assets: "
                        + "Cash and cash equivalents $17,214 $17,203. "
                        + "Monthly Residential Revenue per Residential Customer $117.52 $119.70. "
                        + "Projected revenues from tenant contracts associated with active licenses $866. "
                        + "Free Cash Flow During the second quarter totaled $3.9 billion. "
                        + "Discretionary capital expenditures $15 $17 $20 $18."
        );

        assertNull(summary.revenue());
        assertNull(summary.freeCashFlow());
        assertNull(summary.capex());
    }

    @Test
    void appliesTableUnitsAndAbsoluteMonetaryTolerance() {
        var revenue = policy.summarize(
                "Business outlook: Q3 FY2026 (In millions, except per share amounts) "
                        + "Total revenue is expected to be $8,950 +/- $500."
        );
        assertDecimal("8450000000", revenue.revenue().value().min());
        assertDecimal("9450000000", revenue.revenue().value().max());

        var capex = policy.summarize(
                "Outlook for Capital Expenditures: ($ in millions, totals may not add due to rounding) "
                        + "Full Year 2026 capital expenditures are expected to be $1,050 to $1,080."
        );
        assertDecimal("1050000000", capex.capex().value().min());
        assertDecimal("1080000000", capex.capex().value().max());
    }

    @Test
    void parsesSpelledOutPercentGuidance() {
        var single = policy.summarize(
                "We are raising our full-year revenue growth guidance to 10 percent."
        );
        assertDecimal("10", single.revenue().value().min());
        assertDecimal("10", single.revenue().value().max());

        var range = policy.summarize(
                "The company estimates full-year net sales growth to be 5.5 to 6.5 percent."
        );
        assertDecimal("5.5", range.revenue().value().min());
        assertDecimal("6.5", range.revenue().value().max());
    }

    @Test
    void dropsUnstructuredMentionsAndKnownNonGuidanceRevenueContexts() {
        var summary = policy.summarize(
                "We do not plan to request a ruling from the Internal Revenue Service. "
                        + "Adjustments to revenue are required in subsequent periods to reflect changes in estimates. "
                        + "We seek to increase site rental revenues by adding more tenants. "
                        + "Leases are expected to generate annual rental revenue of approximately $69 million. "
                        + "The appendix contains a summary of capital expenditures for 2025 and 2026."
        );

        assertNull(summary.revenue());
        assertNull(summary.capex());
    }

    @Test
    void rejectsAnnualRentalRevenueAndAdjacentNonGaapPerShareAmounts() {
        var rentalRevenue = policy.summarize(
                "The project is expected to generate annual rental revenue of approximately $68 million. "
                        + "The signed leases are expected to commence next year, with expected annual rental "
                        + "revenue of approximately $69 million."
        );
        var adjacentPerShareAmount = policy.summarize(
                "The company expects full-year revenue growth alongside $0.87 non-GAAP earnings per share."
        );

        assertNull(rentalRevenue.revenue());
        assertNull(adjacentPerShareAmount.revenue());
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
