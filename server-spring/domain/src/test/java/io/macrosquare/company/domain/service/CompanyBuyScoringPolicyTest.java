package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyBuyLabel;
import io.macrosquare.company.domain.model.CompanyFinancials;
import io.macrosquare.company.domain.model.CompanyMarketExpectations;
import io.macrosquare.company.domain.model.Ticker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanyBuyScoringPolicyTest {

    private final CompanyScoringPolicy companyScoringPolicy = new CompanyScoringPolicy();
    private final CompanyBuyScoringPolicy buyScoringPolicy = new CompanyBuyScoringPolicy();

    @Test
    void doesNotMistakeTargetUpsideMovementForEpsRevisionOrCrowding() {
        var financials = new CompanyFinancials(
                new Ticker("TEST"),
                25.0,
                25.0,
                20.0,
                20.0,
                3.0,
                3.0,
                20.0,
                0.0,
                150.0,
                90.0,
                0.0,
                3.0
        );
        var companyScore = companyScoringPolicy.evaluate(financials);
        var expectations = new CompanyMarketExpectations(10.0, 5.0, 0.5);

        var result = buyScoringPolicy.evaluate(financials, companyScore, expectations);

        assertEquals(78, result.appealScore());
        assertEquals(27, result.crowdingScore());
        assertEquals(77, result.buyScore());
        assertEquals(CompanyBuyLabel.FAVORABLE, result.label());
        assertEquals(4, result.reasons().size());
    }

    @Test
    void usesARealEpsEstimateRevisionAsAppealEvidenceWithoutCallingItCrowding() {
        var financials = new CompanyFinancials(
                new Ticker("TEST"),
                25.0, 25.0, 20.0, 20.0, 3.0, 3.0, 20.0, 0.0,
                150.0, 90.0, 0.0, 3.0
        );
        var companyScore = companyScoringPolicy.evaluate(financials);
        var expectations = new CompanyMarketExpectations(
                10.0, null, 5.0, null, null, 0.5
        );

        var result = buyScoringPolicy.evaluate(financials, companyScore, expectations);

        assertEquals(82, result.appealScore());
        assertEquals(27, result.crowdingScore());
        assertEquals(79, result.buyScore());
    }
}
