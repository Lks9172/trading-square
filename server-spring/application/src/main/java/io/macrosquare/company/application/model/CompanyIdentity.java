package io.macrosquare.company.application.model;

import java.util.Objects;
import java.util.List;

/**
 * Transport-neutral company identity resolved from the SEC ticker directory.
 */
public record CompanyIdentity(
        String ticker,
        String registryCik,
        String title,
        List<String> fundamentalsCiks,
        List<String> submissionCiks
) {

    public CompanyIdentity(String ticker, String registryCik, String title) {
        this(ticker, registryCik, title, List.of(registryCik), List.of(registryCik));
    }

    public CompanyIdentity(String ticker, String registryCik, String title, List<String> continuityCiks) {
        this(ticker, registryCik, title, continuityCiks, continuityCiks);
    }

    public CompanyIdentity {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        if (registryCik == null || !registryCik.matches("\\d{10}")) {
            throw new IllegalArgumentException("registryCik must contain exactly ten digits");
        }
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        fundamentalsCiks = List.copyOf(Objects.requireNonNull(fundamentalsCiks, "fundamentalsCiks"));
        if (fundamentalsCiks.isEmpty() || !fundamentalsCiks.getFirst().equals(registryCik)) {
            throw new IllegalArgumentException("fundamentalsCiks must start with registryCik");
        }
        if (fundamentalsCiks.stream().anyMatch(cik -> cik == null || !cik.matches("\\d{10}"))) {
            throw new IllegalArgumentException("fundamentalsCiks must contain ten-digit CIKs only");
        }
        submissionCiks = List.copyOf(Objects.requireNonNull(submissionCiks, "submissionCiks"));
        if (submissionCiks.isEmpty() || !submissionCiks.getFirst().equals(registryCik)) {
            throw new IllegalArgumentException("submissionCiks must start with registryCik");
        }
        if (submissionCiks.stream().anyMatch(cik -> cik == null || !cik.matches("\\d{10}"))) {
            throw new IllegalArgumentException("submissionCiks must contain ten-digit CIKs only");
        }
    }
}
