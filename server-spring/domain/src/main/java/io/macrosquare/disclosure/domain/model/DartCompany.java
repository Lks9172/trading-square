package io.macrosquare.disclosure.domain.model;

import java.time.LocalDate;

public record DartCompany(
        String corpCode,
        String stockCode,
        String corpName,
        String corpEnglishName,
        LocalDate modifiedOn
) {
    public DartCompany {
        if (corpCode == null || !corpCode.matches("\\d{8}")) throw new IllegalArgumentException("corpCode is invalid");
        stockCode = stockCode == null ? "" : stockCode.trim();
        if (!stockCode.isEmpty() && !stockCode.matches("\\d{6}")) throw new IllegalArgumentException("stockCode is invalid");
        if (corpName == null || corpName.isBlank()) throw new IllegalArgumentException("corpName is required");
        corpEnglishName = corpEnglishName == null ? "" : corpEnglishName.trim();
        if (modifiedOn == null) throw new IllegalArgumentException("modifiedOn is required");
    }
}
