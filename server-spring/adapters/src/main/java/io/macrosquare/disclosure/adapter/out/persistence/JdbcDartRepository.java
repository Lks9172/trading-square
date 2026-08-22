package io.macrosquare.disclosure.adapter.out.persistence;

import io.macrosquare.disclosure.application.port.out.DartRepository;
import io.macrosquare.disclosure.domain.model.DartCompany;
import io.macrosquare.disclosure.domain.model.DartCompanySnapshot;
import io.macrosquare.disclosure.domain.model.DartDisclosure;
import io.macrosquare.disclosure.domain.model.DartEventType;
import io.macrosquare.disclosure.domain.model.DartFinancialMetric;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public final class JdbcDartRepository implements DartRepository {

    private static final String UPSERT_COMPANY = """
            insert into disclosure.dart_company (
                corp_code, stock_code, corp_name, corp_english_name, modified_on, collected_at
            ) values (:corpCode, nullif(:stockCode, ''), :corpName, :corpEnglishName, :modifiedOn, :collectedAt)
            on conflict (corp_code) do update set
                stock_code = excluded.stock_code, corp_name = excluded.corp_name,
                corp_english_name = excluded.corp_english_name, modified_on = excluded.modified_on,
                collected_at = excluded.collected_at
            """;
    private static final String UPSERT_DISCLOSURE = """
            insert into disclosure.dart_filing (
                receipt_number, corp_code, corp_name, report_name, filer_name, received_on,
                remark, event_type, source_url, collected_at
            ) values (
                :receiptNumber, :corpCode, :corpName, :reportName, :filerName, :receivedOn,
                :remark, :eventType, :sourceUrl, :collectedAt
            )
            on conflict (receipt_number) do update set
                corp_name = excluded.corp_name, report_name = excluded.report_name,
                filer_name = excluded.filer_name, received_on = excluded.received_on,
                remark = excluded.remark, event_type = excluded.event_type,
                source_url = excluded.source_url, collected_at = excluded.collected_at
            """;
    private static final String UPSERT_FINANCIAL = """
            insert into disclosure.dart_financial_metric (
                corp_code, business_year, report_code, statement_code, statement_name,
                account_id, account_name, current_amount, previous_amount, currency, collected_at
            ) values (
                :corpCode, :businessYear, :reportCode, :statementCode, :statementName,
                :accountId, :accountName, :currentAmount, :previousAmount, :currency, :collectedAt
            )
            on conflict (corp_code, business_year, report_code, statement_code, account_id) do update set
                statement_name = excluded.statement_name, account_name = excluded.account_name,
                current_amount = excluded.current_amount, previous_amount = excluded.previous_amount,
                currency = excluded.currency, collected_at = excluded.collected_at
            """;
    private static final String COMPANY = """
            select corp_code, stock_code, corp_name, corp_english_name, modified_on
            from disclosure.dart_company where stock_code = :stockCode
            """;
    private static final String COMPANY_DIRECTORY_UPDATED = """
            select max(collected_at) from disclosure.dart_company
            """;
    private static final String DISCLOSURES = """
            select receipt_number, corp_code, corp_name, report_name, filer_name,
                   received_on, remark, event_type, source_url
            from disclosure.dart_filing where corp_code = :corpCode
            order by received_on desc, receipt_number desc limit :limit
            """;
    private static final String FINANCIALS = """
            with latest_period as (
                select business_year, report_code
                from disclosure.dart_financial_metric where corp_code = :corpCode
                group by business_year, report_code
                order by business_year desc,
                         case report_code when '11011' then 4 when '11014' then 3
                              when '11012' then 2 when '11013' then 1 else 0 end desc
                limit 1
            )
            select metric.corp_code, metric.business_year, metric.report_code,
                   metric.statement_code, metric.statement_name, metric.account_id,
                   metric.account_name, metric.current_amount, metric.previous_amount, metric.currency
            from disclosure.dart_financial_metric metric
            join latest_period period using (business_year, report_code)
            where metric.corp_code = :corpCode
            order by metric.statement_code, metric.account_id limit :limit
            """;
    private static final String UPDATED = """
            select max(updated_at) as updated_at
            from (
                select max(collected_at) as updated_at from disclosure.dart_company
                union all
                select max(collected_at) as updated_at from disclosure.dart_filing
                union all
                select max(collected_at) as updated_at from disclosure.dart_financial_metric
            ) timestamps
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public JdbcDartRepository(NamedParameterJdbcTemplate jdbc, TransactionOperations transactions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public int saveCompanies(List<DartCompany> companies, Instant collectedAt) {
        if (companies.isEmpty()) return 0;
        return transactions.execute(status -> {
            jdbc.batchUpdate(UPSERT_COMPANY, companies.stream().map(value -> new MapSqlParameterSource()
                    .addValue("corpCode", value.corpCode()).addValue("stockCode", value.stockCode())
                    .addValue("corpName", value.corpName()).addValue("corpEnglishName", value.corpEnglishName())
                    .addValue("modifiedOn", Date.valueOf(value.modifiedOn()))
                    .addValue("collectedAt", Timestamp.from(collectedAt))).toArray(MapSqlParameterSource[]::new));
            return companies.size();
        });
    }

    @Override
    public int saveDisclosures(List<DartDisclosure> values, Instant collectedAt) {
        if (values.isEmpty()) return 0;
        return transactions.execute(status -> {
            jdbc.batchUpdate(UPSERT_DISCLOSURE, values.stream().map(value -> new MapSqlParameterSource()
                    .addValue("receiptNumber", value.receiptNumber()).addValue("corpCode", value.corpCode())
                    .addValue("corpName", value.corpName()).addValue("reportName", value.reportName())
                    .addValue("filerName", value.filerName()).addValue("receivedOn", Date.valueOf(value.receivedOn()))
                    .addValue("remark", value.remark()).addValue("eventType", value.eventType().name())
                    .addValue("sourceUrl", value.sourceUrl()).addValue("collectedAt", Timestamp.from(collectedAt)))
                    .toArray(MapSqlParameterSource[]::new));
            return values.size();
        });
    }

    @Override
    public int saveFinancials(List<DartFinancialMetric> values, Instant collectedAt) {
        if (values.isEmpty()) return 0;
        return transactions.execute(status -> {
            jdbc.batchUpdate(UPSERT_FINANCIAL, values.stream().map(value -> new MapSqlParameterSource()
                    .addValue("corpCode", value.corpCode()).addValue("businessYear", value.businessYear())
                    .addValue("reportCode", value.reportCode()).addValue("statementCode", value.statementCode())
                    .addValue("statementName", value.statementName()).addValue("accountId", value.accountId())
                    .addValue("accountName", value.accountName()).addValue("currentAmount", value.currentAmount())
                    .addValue("previousAmount", value.previousAmount()).addValue("currency", value.currency())
                    .addValue("collectedAt", Timestamp.from(collectedAt))).toArray(MapSqlParameterSource[]::new));
            return values.size();
        });
    }

    @Override
    public DartCompany findByStockCode(String stockCode) {
        var values = jdbc.query(COMPANY, new MapSqlParameterSource("stockCode", stockCode),
                (row, number) -> company(row));
        return values.isEmpty() ? null : values.getFirst();
    }

    @Override
    public Instant companyDirectoryUpdatedAt() {
        var value = jdbc.getJdbcTemplate().queryForObject(COMPANY_DIRECTORY_UPDATED, Timestamp.class);
        return value == null ? null : value.toInstant();
    }

    @Override
    public DartCompanySnapshot loadSnapshot(String stockCode, int disclosureLimit, int financialLimit) {
        var company = findByStockCode(stockCode);
        if (company == null) return new DartCompanySnapshot(
                "collecting", updatedAt(), null, List.of(), List.of(),
                "OpenDART API 키 활성화 후 기업코드와 공시를 수집합니다.");
        var parameters = new MapSqlParameterSource("corpCode", company.corpCode());
        var filings = jdbc.query(DISCLOSURES, parameters.addValue("limit", disclosureLimit), (row, number) ->
                new DartDisclosure(
                        row.getString("receipt_number"), row.getString("corp_code"), row.getString("corp_name"),
                        row.getString("report_name"), row.getString("filer_name"),
                        row.getObject("received_on", LocalDate.class), row.getString("remark"),
                        DartEventType.valueOf(row.getString("event_type")), row.getString("source_url")));
        var metrics = jdbc.query(FINANCIALS, new MapSqlParameterSource("corpCode", company.corpCode())
                .addValue("limit", financialLimit), (row, number) -> new DartFinancialMetric(
                row.getString("corp_code"), row.getInt("business_year"), row.getString("report_code"),
                row.getString("statement_code"), row.getString("statement_name"), row.getString("account_id"),
                row.getString("account_name"), row.getBigDecimal("current_amount"),
                row.getBigDecimal("previous_amount"), row.getString("currency")));
        return new DartCompanySnapshot(
                "ready", updatedAt(), company, filings, metrics,
                "OpenDART 공식 기업코드·공시목록·연결재무 전계정 API를 사용합니다.");
    }

    private Instant updatedAt() {
        try {
            var value = jdbc.getJdbcTemplate().queryForObject(UPDATED, Timestamp.class);
            return value == null ? null : value.toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static DartCompany company(java.sql.ResultSet row) throws java.sql.SQLException {
        return new DartCompany(
                row.getString("corp_code"), row.getString("stock_code"), row.getString("corp_name"),
                row.getString("corp_english_name"), row.getObject("modified_on", LocalDate.class));
    }
}
