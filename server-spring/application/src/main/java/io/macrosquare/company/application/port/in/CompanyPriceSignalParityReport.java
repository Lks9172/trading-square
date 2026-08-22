package io.macrosquare.company.application.port.in;

import io.macrosquare.company.application.model.CompanyPriceSignalSnapshot;
import io.macrosquare.company.domain.bottom.BottomPriceContext;

import java.util.List;
import java.util.Objects;

public record CompanyPriceSignalParityReport(
        String ticker,
        int lookbackDays,
        boolean allMatched,
        boolean priceHistoryMatched,
        boolean markersMatched,
        boolean priceSignalMatched,
        boolean confirmedBottomMatched,
        boolean reversalConfirmationMatched,
        List<String> differences,
        CompanyPriceSignalSnapshot legacy,
        CompanyPriceSignalSnapshot spring,
        BottomPriceContext springContext,
        boolean legacyAvailable
) {
    public CompanyPriceSignalParityReport {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        if (lookbackDays < 120) throw new IllegalArgumentException("lookbackDays must be at least 120");
        differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
        legacy = Objects.requireNonNull(legacy, "legacy");
        spring = Objects.requireNonNull(spring, "spring");
        springContext = Objects.requireNonNull(springContext, "springContext");
    }

    public CompanyPriceSignalParityReport(
            String ticker,
            int lookbackDays,
            boolean allMatched,
            boolean priceHistoryMatched,
            boolean markersMatched,
            boolean priceSignalMatched,
            boolean confirmedBottomMatched,
            boolean reversalConfirmationMatched,
            List<String> differences,
            CompanyPriceSignalSnapshot legacy,
            CompanyPriceSignalSnapshot spring,
            BottomPriceContext springContext
    ) {
        this(
                ticker, lookbackDays, allMatched, priceHistoryMatched, markersMatched,
                priceSignalMatched, confirmedBottomMatched, reversalConfirmationMatched,
                differences, legacy, spring, springContext, true
        );
    }
}
