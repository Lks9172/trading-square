package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyFundamentalsFreshness;
import io.macrosquare.company.domain.model.CompanyFundamentalsSnapshot;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Prevents an already-filed newer statement from being scored with older facts. */
public final class CompanyFundamentalsFreshnessPolicy {

    private static final long PERIOD_ALIGNMENT_TOLERANCE_DAYS = 7;

    public CompanyFundamentalsFreshness evaluate(
            CompanyFundamentalsSnapshot fundamentals,
            LocalDate latestPeriodicReportDate,
            LocalDate latestPeriodicFilingDate,
            String latestPeriodicForm
    ) {
        var factsDate = parseDate(fundamentals.asOf());
        if (fundamentals.revenueTtm() == null) {
            return assessment(
                    CompanyFundamentalsFreshness.Status.INCOMPLETE,
                    factsDate, latestPeriodicReportDate, latestPeriodicFilingDate, latestPeriodicForm,
                    null,
                    "핵심 매출 TTM을 신뢰성 있게 재구성할 수 없어 기업 점수를 보류함"
            );
        }
        if (factsDate == null || latestPeriodicReportDate == null) {
            return assessment(
                    CompanyFundamentalsFreshness.Status.UNKNOWN,
                    factsDate, latestPeriodicReportDate, latestPeriodicFilingDate, latestPeriodicForm,
                    null,
                    "최신 정기보고서와 재무 기준일을 대조할 수 없어 기업 점수를 보류함"
            );
        }
        var lag = ChronoUnit.DAYS.between(factsDate, latestPeriodicReportDate);
        if (lag > PERIOD_ALIGNMENT_TOLERANCE_DAYS) {
            return assessment(
                    CompanyFundamentalsFreshness.Status.LAGGING,
                    factsDate, latestPeriodicReportDate, latestPeriodicFilingDate, latestPeriodicForm,
                    Math.toIntExact(lag),
                    "최신 " + latestPeriodicForm + " 보고기간 " + latestPeriodicReportDate
                            + "보다 재무 계산 기준일 " + factsDate + "이 뒤처져 점수를 보류함"
            );
        }
        return new CompanyFundamentalsFreshness(
                CompanyFundamentalsFreshness.Status.CURRENT,
                factsDate,
                latestPeriodicReportDate,
                latestPeriodicFilingDate,
                latestPeriodicForm,
                Math.toIntExact(Math.max(0, lag)),
                List.of()
        );
    }

    private static CompanyFundamentalsFreshness assessment(
            CompanyFundamentalsFreshness.Status status,
            LocalDate factsDate,
            LocalDate reportDate,
            LocalDate filingDate,
            String form,
            Integer lagDays,
            String warning
    ) {
        return new CompanyFundamentalsFreshness(
                status, factsDate, reportDate, filingDate, form, lagDays, List.of(warning)
        );
    }

    private static LocalDate parseDate(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
