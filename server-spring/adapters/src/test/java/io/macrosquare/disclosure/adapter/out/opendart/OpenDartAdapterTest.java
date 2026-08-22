package io.macrosquare.disclosure.adapter.out.opendart;

import io.macrosquare.disclosure.domain.service.DartEventClassificationPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenDartAdapterTest {

    @Test
    void parsesBoundedOfficialDirectoryDisclosuresAndFinancialFacts() throws Exception {
        var builder = RestClient.builder().baseUrl("https://opendart.fss.or.kr");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://opendart.fss.or.kr/api/corpCode.xml?crtfc_key=test-key"))
                .andRespond(withSuccess(directoryZip(), MediaType.APPLICATION_OCTET_STREAM));
        server.expect(once(), requestTo("https://opendart.fss.or.kr/api/list.json?crtfc_key=test-key&corp_code=00126380&bgn_de=20260701&end_de=20260721&page_no=1&page_count=10"))
                .andRespond(withSuccess("""
                        {"status":"000","message":"정상","list":[{
                          "corp_code":"00126380","corp_name":"삼성전자","report_nm":"유상증자 결정",
                          "rcept_no":"20260721000001","flr_nm":"삼성전자","rcept_dt":"20260721","rm":""
                        }]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json?crtfc_key=test-key&corp_code=00126380&bsns_year=2025&reprt_code=11011&fs_div=CFS"))
                .andRespond(withSuccess("""
                        {"status":"000","message":"정상","list":[{
                          "sj_div":"IS","sj_nm":"손익계산서","account_id":"ifrs-full_Revenue",
                          "account_nm":"매출액","thstrm_amount":"300,000","frmtrm_amount":"250,000","currency":"KRW"
                        }]}
                        """, MediaType.APPLICATION_JSON));
        var adapter = new OpenDartAdapter(
                builder.build(), new ObjectMapper(), new DartEventClassificationPolicy(), null, "test-key",
                Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC),
                Duration.ZERO, 1_000_000, 2_000_000);

        var companies = adapter.collect();
        var filings = adapter.collect(
                companies.getFirst(), LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-21"), 10);
        var metrics = adapter.collect(companies.getFirst(), 2025, "11011");

        assertEquals("005930", companies.getFirst().stockCode());
        assertEquals("CAPITAL_ACTION", filings.getFirst().eventType().name());
        assertEquals("300000", metrics.getFirst().currentAmount().toPlainString());
        assertTrue(metrics.getFirst().accountName().contains("매출"));
        server.verify();
    }

    private static byte[] directoryZip() throws Exception {
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <result><list><corp_code>00126380</corp_code><corp_name>삼성전자</corp_name>
                <corp_eng_name>Samsung Electronics</corp_eng_name><stock_code>005930</stock_code>
                <modify_date>20260701</modify_date></list></result>
                """;
        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("CORPCODE.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return output.toByteArray();
        }
    }
}
