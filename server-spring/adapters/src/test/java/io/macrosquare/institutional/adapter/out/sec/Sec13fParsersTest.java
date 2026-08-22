package io.macrosquare.institutional.adapter.out.sec;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Sec13fParsersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesLatestDistinct13fReportPeriodsAndSkipsUnrelatedForms() throws Exception {
        var json = """
                {
                  "cik": "0000000001",
                  "filings": {"recent": {
                    "form": ["13F-HR/A", "13F-HR", "10-K", "13F-HR"],
                    "accessionNumber": ["0000000001-26-000003", "0000000001-26-000002", "x", "0000000001-25-000001"],
                    "filingDate": ["2026-05-16", "2026-05-15", "2026-04-01", "2026-02-14"],
                    "reportDate": ["2026-03-31", "2026-03-31", "2025-12-31", "2025-12-31"]
                  }}
                }
                """;

        final java.util.List<Sec13fSubmissionsParser.FilingReference> result;
        try (var parser = objectMapper.createParser(json)) {
            result = Sec13fSubmissionsParser.parse(parser, "0000000001", 2);
        }

        assertEquals(2, result.size());
        assertEquals("0000000001-26-000003", result.getFirst().accessionNumber());
        assertEquals(LocalDate.parse("2025-12-31"), result.getLast().reportPeriod());
    }

    @Test
    void rejectsSubmissionsFromAnotherManagerCik() throws Exception {
        var json = """
                {"cik":"0000000002","filings":{"recent":{
                  "form":[],"accessionNumber":[],"filingDate":[],"reportDate":[]
                }}}
                """;

        try (var parser = objectMapper.createParser(json)) {
            assertThrows(IllegalArgumentException.class,
                    () -> Sec13fSubmissionsParser.parse(parser, "0000000001", 2));
        }
    }

    @Test
    void selectsInformationTableXmlBeforePrimarySubmissionDocuments() throws Exception {
        var json = """
                {"directory":{"item":[
                  {"name":"primary_doc.xml","size":900000},
                  {"name":"infotable.xml","size":120000},
                  {"name":"tiny.xml","size":100}
                ]}}
                """;

        final java.util.List<String> result;
        try (var parser = objectMapper.createParser(json)) {
            result = Sec13fIndexParser.xmlCandidates(parser);
        }

        assertEquals("infotable.xml", result.getFirst());
        assertFalse(result.contains("primary_doc.xml"));
    }

    @Test
    void preservesCurrentSecXmlDollarValuesAtTheAdapterBoundary() {
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <informationTable xmlns="http://www.sec.gov/edgar/document/thirteenf/informationtable">
                  <infoTable>
                    <nameOfIssuer>APPLE INC</nameOfIssuer>
                    <titleOfClass>COM</titleOfClass>
                    <cusip>037833100</cusip>
                    <value>123456</value>
                    <shrsOrPrnAmt><sshPrnamt>789000</sshPrnamt></shrsOrPrnAmt>
                    <putCall>CALL</putCall>
                  </infoTable>
                </informationTable>
                """;

        var result = Sec13fInformationTableParser.parse(xml.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, result.size());
        assertEquals(123_456, result.getFirst().valueUsd());
        assertEquals(789_000, result.getFirst().shares());
        assertEquals("CALL", result.getFirst().putCall());
    }

    @Test
    void normalizesAWholeLegacyThousandsFilingToDollars() {
        var rows = new StringBuilder("<informationTable>");
        for (var index = 1; index <= 5; index++) {
            rows.append("""
                    <infoTable>
                      <nameOfIssuer>ISSUER %d</nameOfIssuer><titleOfClass>COM</titleOfClass>
                      <cusip>00000000%d</cusip><value>%d</value>
                      <shrsOrPrnAmt><sshPrnamt>1000000</sshPrnamt></shrsOrPrnAmt>
                    </infoTable>
                    """.formatted(index, index, index * 100_000));
        }
        rows.append("</informationTable>");

        var result = Sec13fInformationTableParser.parse(
                rows.toString().getBytes(StandardCharsets.UTF_8));

        assertEquals(5, result.size());
        assertEquals(100_000_000d, result.getFirst().valueUsd());
        assertEquals(500_000_000d, result.getLast().valueUsd());
    }

    @Test
    void skipsMalformedOrZeroValueRowsInsteadOfManufacturingAZeroPosition() {
        var xml = """
                <informationTable>
                  <infoTable><nameOfIssuer>BAD VALUE</nameOfIssuer><cusip>000000001</cusip>
                    <value>not-a-number</value><shrsOrPrnAmt><sshPrnamt>100</sshPrnamt></shrsOrPrnAmt>
                  </infoTable>
                  <infoTable><nameOfIssuer>BAD SHARES</nameOfIssuer><cusip>000000002</cusip>
                    <value>100</value><shrsOrPrnAmt><sshPrnamt>0</sshPrnamt></shrsOrPrnAmt>
                  </infoTable>
                </informationTable>
                """;

        var result = Sec13fInformationTableParser.parse(xml.getBytes(StandardCharsets.UTF_8));

        assertTrue(result.isEmpty());
    }
}
