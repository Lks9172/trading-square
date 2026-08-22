package io.macrosquare.disclosure.domain.model;

import java.math.BigDecimal;

public record DartFinancialMetric(
        String corpCode,
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
    public DartFinancialMetric {
        if (corpCode == null || !corpCode.matches("\\d{8}")) throw new IllegalArgumentException("corpCode is invalid");
        if (businessYear < 1990 || businessYear > 2200) throw new IllegalArgumentException("businessYear is invalid");
        reportCode = required(reportCode, "reportCode");
        statementCode = required(statementCode, "statementCode");
        statementName = required(statementName, "statementName");
        accountId = required(accountId, "accountId");
        accountName = required(accountName, "accountName");
        currency = currency == null ? "" : currency.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
