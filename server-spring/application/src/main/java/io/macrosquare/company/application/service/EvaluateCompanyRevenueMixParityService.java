package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyIdentity;
import io.macrosquare.company.application.port.in.CompanyRevenueMixParityReport;
import io.macrosquare.company.application.port.in.EvaluateCompanyRevenueMixParityUseCase;
import io.macrosquare.company.application.port.out.CompanyResearchParityUnavailableException;
import io.macrosquare.company.application.port.out.CompanyRevenueMixUnavailableException;
import io.macrosquare.company.application.port.out.CompanySubmissionsUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.application.port.out.LoadCompanyRevenueMixEvidencePort;
import io.macrosquare.company.application.port.out.LoadCompanySubmissionsEvidencePort;
import io.macrosquare.company.application.port.out.ResolveCompanyIdentityPort;
import io.macrosquare.company.domain.model.CompanyFilingEvidence;
import io.macrosquare.company.domain.model.CompanyRevenueMixBreakdown;
import io.macrosquare.company.domain.model.CompanyRevenueMixEvidence;
import io.macrosquare.company.domain.model.CompanySubmissionsEvidence;
import io.macrosquare.company.domain.service.CompanyRevenueMixPolicy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Direct SEC Inline XBRL parallel run. Legacy values are comparison evidence
 * only and never become inputs to the Spring revenue-mix result.
 */
public final class EvaluateCompanyRevenueMixParityService
        implements EvaluateCompanyRevenueMixParityUseCase {

    private static final Set<String> ANNUAL_FORMS = Set.of("10-K", "20-F", "40-F");
    private static final Set<String> INTERIM_FORMS = Set.of("10-Q");

    private final LoadCompanyReadPort companyReadPort;
    private final ResolveCompanyIdentityPort companyIdentityPort;
    private final LoadCompanySubmissionsEvidencePort submissionsPort;
    private final LoadCompanyRevenueMixEvidencePort revenueMixPort;
    private final CompanyRevenueMixPolicy policy;

    public EvaluateCompanyRevenueMixParityService(
            LoadCompanyReadPort companyReadPort,
            ResolveCompanyIdentityPort companyIdentityPort,
            LoadCompanySubmissionsEvidencePort submissionsPort,
            LoadCompanyRevenueMixEvidencePort revenueMixPort,
            CompanyRevenueMixPolicy policy
    ) {
        this.companyReadPort = Objects.requireNonNull(companyReadPort);
        this.companyIdentityPort = Objects.requireNonNull(companyIdentityPort);
        this.submissionsPort = Objects.requireNonNull(submissionsPort);
        this.revenueMixPort = Objects.requireNonNull(revenueMixPort);
        this.policy = Objects.requireNonNull(policy);
    }

    @Override
    public CompanyRevenueMixParityReport evaluate(String ticker) {
        var normalizedTicker = normalizeTicker(ticker);
        var identity = companyIdentityPort.resolve(normalizedTicker);
        final var research = companyReadPort.detail(normalizedTicker);
        final CompanySubmissionsLegacyProjection legacySubmissions;
        final var legacyMix = projectLegacy(research);
        try {
            legacySubmissions = CompanySubmissionsLegacyProjection.from(research);
        } catch (IllegalArgumentException error) {
            throw new CompanyResearchParityUnavailableException(
                    "Legacy company research cannot be projected into filing metadata", error
            );
        }
        if (!identity.ticker().equals(legacySubmissions.snapshot().profile().ticker())) {
            throw new CompanyResearchParityUnavailableException(
                    "Legacy company research returned a different ticker",
                    new IllegalStateException("ticker mismatch")
            );
        }

        var direct = loadFirstAvailable(prioritizeServingCik(
                identity, legacySubmissions.snapshot().profile().cik()
        ));
        var candidates = selectCandidates(direct.filings());
        var failures = new ArrayList<String>();
        var evidence = new ArrayList<CompanyRevenueMixEvidence>();
        for (var filing : candidates) {
            try {
                evidence.add(revenueMixPort.loadRevenueMix(filing.sourceUrl()));
            } catch (CompanyRevenueMixUnavailableException error) {
                failures.add(filing.accessionNumber());
            }
        }

        var spring = policy.evaluate(evidence);
        var percentageValidationPassed = validBreakdown(spring.segment())
                && validBreakdown(spring.geography());
        var directCoveragePassed = !candidates.isEmpty()
                && failures.isEmpty()
                && evidence.size() == candidates.size();
        var legacyCoveragePreserved = (legacyMix.segment().isEmpty() || spring.hasSegment())
                && (legacyMix.geography().isEmpty() || spring.hasGeography());
        var differences = differences(legacyMix, spring, failures);
        var migrationReady = directCoveragePassed
                && percentageValidationPassed
                && legacyCoveragePreserved
                && (spring.hasSegment() || spring.hasGeography());

        return new CompanyRevenueMixParityReport(
                identity.ticker(),
                identity.registryCik(),
                direct.cik(),
                direct.filings().size(),
                candidates.size(),
                evidence.size(),
                spring.dimensionalFactCount(),
                migrationReady,
                directCoveragePassed,
                percentageValidationPassed,
                legacyCoveragePreserved,
                spring.hasSegment(),
                spring.hasGeography(),
                candidates.stream().map(CompanyFilingEvidence::accessionNumber).toList(),
                failures,
                differences,
                legacyMix,
                spring
        );
    }

    private static io.macrosquare.company.application.model.CompanyRevenueMixLegacyRead projectLegacy(
            io.macrosquare.company.application.model.CompanyReadModels.Research research
    ) {
        try {
            return CompanyRevenueMixLegacyProjection.from(research);
        } catch (IllegalArgumentException error) {
            throw new CompanyResearchParityUnavailableException(
                    "Legacy company research cannot be projected into revenue mix", error
            );
        }
    }

    private static List<CompanyFilingEvidence> selectCandidates(List<CompanyFilingEvidence> filings) {
        var ordered = filings.stream()
                .filter(filing -> filing.sourceUrl() != null && isInlineXbrlDocument(filing.sourceUrl()))
                .sorted(Comparator.comparing(CompanyFilingEvidence::filingDate).reversed())
                .toList();
        var selected = new LinkedHashMap<String, CompanyFilingEvidence>();
        ordered.stream().filter(filing -> INTERIM_FORMS.contains(filing.form())).findFirst()
                .ifPresent(filing -> selected.put(filing.accessionNumber(), filing));
        ordered.stream().filter(filing -> ANNUAL_FORMS.contains(filing.form())).findFirst()
                .ifPresent(filing -> selected.put(filing.accessionNumber(), filing));
        return List.copyOf(selected.values());
    }

    private static boolean isInlineXbrlDocument(String source) {
        var normalized = source.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".htm") || normalized.endsWith(".html") || normalized.endsWith(".xml");
    }

    private List<String> prioritizeServingCik(CompanyIdentity identity, String legacyCik) {
        var ordered = new LinkedHashSet<String>();
        if (identity.submissionCiks().contains(legacyCik)) ordered.add(legacyCik);
        ordered.addAll(identity.submissionCiks());
        return List.copyOf(ordered);
    }

    private CompanySubmissionsEvidence loadFirstAvailable(List<String> candidates) {
        CompanySubmissionsUnavailableException firstFailure = null;
        for (var cik : candidates) {
            try {
                return submissionsPort.load(cik);
            } catch (CompanySubmissionsUnavailableException error) {
                if (firstFailure == null) firstFailure = error;
            }
        }
        throw new CompanySubmissionsUnavailableException(
                "Unable to load SEC submissions for any continuity candidate", firstFailure
        );
    }

    private static boolean validBreakdown(CompanyRevenueMixBreakdown breakdown) {
        if (breakdown == null) return true;
        var sum = breakdown.entries().stream()
                .map(entry -> entry.percentOfTotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.compareTo(new BigDecimal("100.0")) == 0
                && breakdown.entries().size() >= 2
                && breakdown.coveragePercent().compareTo(new BigDecimal("80.0")) >= 0
                && breakdown.coveragePercent().compareTo(new BigDecimal("120.0")) <= 0;
    }

    private static List<String> differences(
            io.macrosquare.company.application.model.CompanyRevenueMixLegacyRead legacy,
            io.macrosquare.company.domain.model.CompanyRevenueMixAnalysis spring,
            List<String> failures
    ) {
        var result = new ArrayList<String>();
        if (!legacy.segment().isEmpty() && !spring.hasSegment()) result.add("segment.coverage");
        if (!legacy.geography().isEmpty() && !spring.hasGeography()) result.add("geography.coverage");
        failures.forEach(accession -> result.add("filing[" + accession + "].extraction"));
        return List.copyOf(result);
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        return ticker.trim().toUpperCase(Locale.ROOT);
    }
}
