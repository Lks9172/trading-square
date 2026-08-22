package io.macrosquare.company.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/** Guidance result tied to the exact SEC-backed investor material that produced it. */
public record CompanyGuidanceAnalysis(
        String title,
        String form,
        LocalDate filingDate,
        String url,
        CompanyIrMaterial.ContentType contentType,
        CompanyGuidanceSummary summary
) {
    public CompanyGuidanceAnalysis {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        if (form == null || form.isBlank()) throw new IllegalArgumentException("form is required");
        filingDate = Objects.requireNonNull(filingDate, "filingDate");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
        contentType = Objects.requireNonNull(contentType, "contentType");
        summary = Objects.requireNonNull(summary, "summary");
    }

    public static CompanyGuidanceAnalysis from(CompanyIrMaterial material, CompanyGuidanceSummary summary) {
        return new CompanyGuidanceAnalysis(
                material.title(),
                material.form(),
                material.filingDate(),
                material.url(),
                material.contentType(),
                summary
        );
    }
}
