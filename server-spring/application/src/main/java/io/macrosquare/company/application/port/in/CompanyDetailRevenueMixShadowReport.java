package io.macrosquare.company.application.port.in;

import io.macrosquare.company.application.model.CompanyRevenueMixComposition;

import java.util.Objects;

/** Read-only branch-by-abstraction report; it never changes the public serving path. */
public record CompanyDetailRevenueMixShadowReport(
        String ticker,
        boolean contractCompatible,
        boolean servingSnapshotMatched,
        boolean shadowServeReady,
        boolean directMigrationReady,
        CompanyRevenueMixParityReport directParity,
        CompanyRevenueMixComposition composition
) {
    public CompanyDetailRevenueMixShadowReport {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        directParity = Objects.requireNonNull(directParity, "directParity");
        composition = Objects.requireNonNull(composition, "composition");
        if (!ticker.equals(directParity.ticker())) {
            throw new IllegalArgumentException("ticker must match direct parity");
        }
        if (directMigrationReady != directParity.migrationReady()) {
            throw new IllegalArgumentException("directMigrationReady must match direct parity readiness");
        }
        if (shadowServeReady && (!contractCompatible
                || !servingSnapshotMatched
                || !composition.actualUsed()
                || !directParity.directCoveragePassed()
                || !directParity.percentageValidationPassed())) {
            throw new IllegalArgumentException("shadow serving requires a compatible direct result");
        }
    }
}
