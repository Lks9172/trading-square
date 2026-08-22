package io.macrosquare.company.adapter.out.sec;

import io.macrosquare.company.domain.model.CompanyRevenueMixDimension;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecInlineXbrlRevenueMixParserTest {

    @Test
    void mapsInlineContextsAndScaledRevenueFactsToSemanticEvidence() {
        var evidence = SecInlineXbrlRevenueMixParser.parse(document().getBytes(StandardCharsets.UTF_8), "sec:test");

        assertEquals(4, evidence.facts().size());
        assertEquals(1, evidence.consolidatedRevenue().size());
        assertEquals("United States", evidence.facts().get(2).label());
        assertEquals(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, evidence.facts().getFirst().dimension());
        assertEquals(CompanyRevenueMixDimension.GEOGRAPHY, evidence.facts().get(2).dimension());
        assertEquals("60000000", evidence.facts().getFirst().value().toPlainString());
        assertEquals("100000000", evidence.consolidatedRevenue().getFirst().value().toPlainString());
    }

    @Test
    void acceptsTheOperatingSegmentsConsolidationQualifierButRejectsCrossTabFacts() {
        var evidence = SecInlineXbrlRevenueMixParser.parse(document().getBytes(StandardCharsets.UTF_8), "sec:test");

        assertEquals(2, evidence.facts().stream()
                .filter(fact -> fact.dimension() == CompanyRevenueMixDimension.REPORTABLE_SEGMENT)
                .count());
        assertEquals(0, evidence.facts().stream().filter(fact -> fact.label().equals("Ignored Cross Tab")).count());
    }

    @Test
    void ignoresDeferredRevenueAndNegativeOrNilObservations() {
        var xml = document().replace(
                "</body>",
                """
                <ix:nonFraction name="us-gaap:ContractWithCustomerLiabilityRevenueRecognized"
                    contextRef="segA" unitRef="USD" scale="6">999</ix:nonFraction>
                <ix:nonFraction name="us-gaap:Revenues" contextRef="segA" unitRef="USD"
                    scale="6" sign="-">20</ix:nonFraction>
                <ix:nonFraction name="us-gaap:Revenues" contextRef="segA" unitRef="USD"
                    xsi:nil="true">0</ix:nonFraction>
                </body>
                """
        );

        var evidence = SecInlineXbrlRevenueMixParser.parse(xml.getBytes(StandardCharsets.UTF_8), "sec:test");

        assertEquals(4, evidence.facts().size());
    }

    @Test
    void rejectsMalformedInlineXbrl() {
        assertThrows(IllegalArgumentException.class, () -> SecInlineXbrlRevenueMixParser.parse(
                "<html><broken></html>".getBytes(StandardCharsets.UTF_8), "sec:test"
        ));
    }

    @Test
    void projectsOnlyKnownAggregateRevenueQualifiersFromTwoAxisCrossTabs() {
        var xml = document().replace(
                "</body>",
                """
                <xbrli:context id="qualifiedGeo">
                  <xbrli:entity><xbrli:identifier scheme="test">1</xbrli:identifier><xbrli:segment>
                    <xbrldi:explicitMember dimension="srt:StatementGeographicalAxis">country:SG</xbrldi:explicitMember>
                    <xbrldi:explicitMember dimension="srt:ProductOrServiceAxis">test:SalesAndOtherOperatingRevenueMember</xbrldi:explicitMember>
                  </xbrli:segment></xbrli:entity>
                  <xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period>
                </xbrli:context>
                <xbrli:context id="qualifiedSegment">
                  <xbrli:entity><xbrli:identifier scheme="test">1</xbrli:identifier><xbrli:segment>
                    <xbrldi:explicitMember dimension="us-gaap:StatementBusinessSegmentsAxis">test:EnergyProductsMember</xbrldi:explicitMember>
                    <xbrldi:explicitMember dimension="srt:ProductOrServiceAxis">test:SalesAndOtherOperatingRevenueMember</xbrldi:explicitMember>
                  </xbrli:segment></xbrli:entity>
                  <xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period>
                </xbrli:context>
                <ix:nonFraction name="us-gaap:Revenues" contextRef="qualifiedGeo" unitRef="USD" scale="6">25</ix:nonFraction>
                <ix:nonFraction name="us-gaap:Revenues" contextRef="qualifiedSegment" unitRef="USD" scale="6">35</ix:nonFraction>
                </body>
                """
        );

        var evidence = SecInlineXbrlRevenueMixParser.parse(
                xml.getBytes(StandardCharsets.UTF_8), "sec:test"
        );

        assertTrue(evidence.facts().stream().anyMatch(fact ->
                fact.dimension() == CompanyRevenueMixDimension.GEOGRAPHY
                        && fact.label().equals("Singapore")
                        && fact.value().toPlainString().equals("25000000")
        ));
        assertTrue(evidence.facts().stream().anyMatch(fact ->
                fact.dimension() == CompanyRevenueMixDimension.REPORTABLE_SEGMENT
                        && fact.label().equals("Energy Products")
                        && fact.value().toPlainString().equals("35000000")
        ));
    }

    @Test
    void recognizesIfrsCustomerRevenueAndEndMarketAxes() {
        var xml = document()
                .replace(
                        "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"",
                        "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                                + "xmlns:ifrs-full=\"https://xbrl.ifrs.org/taxonomy/2025-03-27/ifrs-full\""
                )
                .replace(
                        "</body>",
                        """
                        <xbrli:context id="ifrsMarket">
                          <xbrli:entity><xbrli:identifier scheme="test">1</xbrli:identifier><xbrli:segment>
                            <xbrldi:explicitMember dimension="ifrs-full:MarketsOfCustomersAxis">test:HighPerformanceComputingMember</xbrldi:explicitMember>
                          </xbrli:segment></xbrli:entity>
                          <xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period>
                        </xbrli:context>
                        <ix:nonFraction name="ifrs-full:RevenueFromContractsWithCustomers"
                            contextRef="ifrsMarket" unitRef="USD" scale="6">45</ix:nonFraction>
                        </body>
                        """
                );

        var evidence = SecInlineXbrlRevenueMixParser.parse(
                xml.getBytes(StandardCharsets.UTF_8), "sec:test"
        );

        assertTrue(evidence.facts().stream().anyMatch(fact ->
                fact.dimension() == CompanyRevenueMixDimension.PRODUCT_OR_SERVICE
                        && fact.label().equals("High Performance Computing")
                        && fact.value().toPlainString().equals("45000000")
        ));
    }

    private static String document() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ix="http://www.xbrl.org/2013/inlineXBRL"
                      xmlns:xbrli="http://www.xbrl.org/2003/instance"
                      xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                      xmlns:us-gaap="http://fasb.org/us-gaap/2025"
                      xmlns:srt="http://fasb.org/srt/2025"
                      xmlns:country="http://xbrl.sec.gov/country/2025"
                      xmlns:test="https://example.test/xbrl"
                      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                <body>
                  <xbrli:context id="total">
                    <xbrli:entity><xbrli:identifier scheme="test">1</xbrli:identifier></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period>
                  </xbrli:context>
                  <xbrli:context id="segA">
                    <xbrli:entity><xbrli:identifier scheme="test">1</xbrli:identifier><xbrli:segment>
                      <xbrldi:explicitMember dimension="us-gaap:StatementBusinessSegmentsAxis">test:CloudSegmentMember</xbrldi:explicitMember>
                      <xbrldi:explicitMember dimension="srt:ConsolidationItemsAxis">us-gaap:OperatingSegmentsMember</xbrldi:explicitMember>
                    </xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period>
                  </xbrli:context>
                  <xbrli:context id="segB">
                    <xbrli:entity><xbrli:identifier scheme="test">1</xbrli:identifier><xbrli:segment>
                      <xbrldi:explicitMember dimension="us-gaap:StatementBusinessSegmentsAxis">test:ConsumerSegmentMember</xbrldi:explicitMember>
                    </xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period>
                  </xbrli:context>
                  <xbrli:context id="geoUS">
                    <xbrli:entity><xbrli:identifier scheme="test">1</xbrli:identifier><xbrli:segment>
                      <xbrldi:explicitMember dimension="srt:StatementGeographicalAxis">country:US</xbrldi:explicitMember>
                    </xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period>
                  </xbrli:context>
                  <xbrli:context id="geoOther">
                    <xbrli:entity><xbrli:identifier scheme="test">1</xbrli:identifier><xbrli:segment>
                      <xbrldi:explicitMember dimension="srt:StatementGeographicalAxis">test:OtherCountriesMember</xbrldi:explicitMember>
                    </xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period>
                  </xbrli:context>
                  <xbrli:context id="crossTab">
                    <xbrli:entity><xbrli:identifier scheme="test">1</xbrli:identifier><xbrli:segment>
                      <xbrldi:explicitMember dimension="us-gaap:StatementBusinessSegmentsAxis">test:IgnoredCrossTabMember</xbrldi:explicitMember>
                      <xbrldi:explicitMember dimension="test:CustomerTypeAxis">test:EnterpriseMember</xbrldi:explicitMember>
                    </xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period>
                  </xbrli:context>
                  <ix:nonFraction name="us-gaap:Revenues" contextRef="total" unitRef="USD" scale="6">100</ix:nonFraction>
                  <ix:nonFraction name="us-gaap:Revenues" contextRef="segA" unitRef="USD" scale="6">60</ix:nonFraction>
                  <ix:nonFraction name="us-gaap:Revenues" contextRef="segB" unitRef="USD" scale="6">40</ix:nonFraction>
                  <ix:nonFraction name="us-gaap:Revenues" contextRef="geoUS" unitRef="USD" scale="6">70</ix:nonFraction>
                  <ix:nonFraction name="us-gaap:Revenues" contextRef="geoOther" unitRef="USD" scale="6">30</ix:nonFraction>
                  <ix:nonFraction name="us-gaap:Revenues" contextRef="crossTab" unitRef="USD" scale="6">99</ix:nonFraction>
                </body>
                </html>
                """;
    }
}
