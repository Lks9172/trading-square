package io.macrosquare.disclosure.adapter.in.web;

import io.macrosquare.disclosure.application.port.in.QueryDartCompanyUseCase;
import io.macrosquare.disclosure.domain.model.DartCompanySnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@RestController
public final class DartDisclosureController {

    private final QueryDartCompanyUseCase query;

    public DartDisclosureController(QueryDartCompanyUseCase query) {
        this.query = Objects.requireNonNull(query);
    }

    @GetMapping("/api/dart/disclosures/{stockCode}")
    public Response disclosures(@PathVariable String stockCode) {
        return Response.from(query.query(stockCode));
    }

    public record Response(
            String status,
            Instant asOf,
            Company company,
            List<Disclosure> disclosures,
            List<Financial> financials,
            String methodology
    ) {
        static Response from(DartCompanySnapshot value) {
            return new Response(
                    value.status(), value.asOf(), value.company() == null ? null : new Company(
                            value.company().corpCode(), value.company().stockCode(), value.company().corpName(),
                            value.company().corpEnglishName(), value.company().modifiedOn()),
                    value.disclosures().stream().map(item -> new Disclosure(
                            item.receiptNumber(), item.reportName(), item.filerName(), item.receivedOn(),
                            item.remark(), item.eventType().name(), item.sourceUrl())).toList(),
                    value.financials().stream().map(item -> new Financial(
                            item.businessYear(), item.reportCode(), item.statementCode(), item.statementName(),
                            item.accountId(), item.accountName(), item.currentAmount(), item.previousAmount(),
                            item.currency())).toList(), value.methodology());
        }
    }

    public record Company(
            String corpCode,
            String stockCode,
            String corpName,
            String corpEnglishName,
            LocalDate modifiedOn
    ) {
    }

    public record Disclosure(
            String receiptNumber,
            String reportName,
            String filerName,
            LocalDate receivedOn,
            String remark,
            String eventType,
            String sourceUrl
    ) {
    }

    public record Financial(
            int businessYear,
            String reportCode,
            String statementCode,
            String statementName,
            String accountId,
            String accountName,
            BigDecimal currentAmount,
            BigDecimal previousAmount,
            String currency
    ) {
    }
}
