package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyFilingEvidence;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyFilingClassificationPolicyTest {

    private final CompanyFilingClassificationPolicy policy = new CompanyFilingClassificationPolicy();

    @Test
    void recognizesOnlyLegacyCompatibleEarningsDescriptionsOnExactEightKForms() {
        assertTrue(policy.isEarningsRelated(filing("8-K", "Item 2.02 Results of Operations")));
        assertTrue(policy.isEarningsRelated(filing("8-K", "Quarterly Earnings Release")));
        assertFalse(policy.isEarningsRelated(filing("10-Q", "Results of Operations")));
        assertFalse(policy.isEarningsRelated(filing("8-k", "Earnings")));
    }

    @Test
    void treatsMissingOrGenericDescriptionsAsNonEarningsLikeTheServingNodePolicy() {
        assertFalse(policy.isEarningsRelated(filing("8-K", null)));
        assertFalse(policy.isEarningsRelated(filing("8-K", "8-K")));
    }

    @Test
    void usesSecItemsOnlyForImprovedDirectDiscoveryWithoutChangingLegacyClassification() {
        var filing = new CompanyFilingEvidence(
                "0000000001-26-000001",
                LocalDate.parse("2026-07-17"),
                "8-K",
                "filing.htm",
                "8-K",
                "2.02,9.01",
                "https://www.sec.gov/filing.htm"
        );

        assertFalse(policy.isEarningsRelated(filing));
        assertTrue(policy.isEarningsCandidate(filing));
        assertFalse(policy.isEarningsCandidate(new CompanyFilingEvidence(
                filing.accessionNumber(), filing.filingDate(), "10-Q", filing.primaryDocument(),
                filing.primaryDocumentDescription(), filing.items(), filing.sourceUrl()
        )));
    }

    private static CompanyFilingEvidence filing(String form, String description) {
        return new CompanyFilingEvidence(
                "0000000001-26-000001",
                LocalDate.parse("2026-07-17"),
                form,
                "filing.htm",
                description,
                "https://www.sec.gov/filing.htm"
        );
    }
}
