package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyIdentity;
import io.macrosquare.company.application.model.CompanySubmissionsSnapshot;
import io.macrosquare.company.application.port.in.CompanySubmissionsParityReport;
import io.macrosquare.company.application.port.in.EvaluateCompanySubmissionsParityUseCase;
import io.macrosquare.company.application.port.out.CompanyResearchParityUnavailableException;
import io.macrosquare.company.application.port.out.CompanySubmissionsUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.application.port.out.LoadCompanySubmissionsEvidencePort;
import io.macrosquare.company.application.port.out.ResolveCompanyIdentityPort;
import io.macrosquare.company.domain.model.CompanySubmissionsEvidence;
import io.macrosquare.company.domain.service.CompanyFilingClassificationPolicy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Read-only parallel run for SEC company submissions and recent filing metadata.
 */
public final class EvaluateCompanySubmissionsParityService implements EvaluateCompanySubmissionsParityUseCase {

    private final LoadCompanyReadPort companyReadPort;
    private final ResolveCompanyIdentityPort companyIdentityPort;
    private final LoadCompanySubmissionsEvidencePort submissionsPort;
    private final CompanyFilingClassificationPolicy classificationPolicy;
    private final int filingLimit;

    public EvaluateCompanySubmissionsParityService(
            LoadCompanyReadPort companyReadPort,
            ResolveCompanyIdentityPort companyIdentityPort,
            LoadCompanySubmissionsEvidencePort submissionsPort,
            CompanyFilingClassificationPolicy classificationPolicy,
            int filingLimit
    ) {
        this.companyReadPort = Objects.requireNonNull(companyReadPort);
        this.companyIdentityPort = Objects.requireNonNull(companyIdentityPort);
        this.submissionsPort = Objects.requireNonNull(submissionsPort);
        this.classificationPolicy = Objects.requireNonNull(classificationPolicy);
        if (filingLimit < 1 || filingLimit > 20) {
            throw new IllegalArgumentException("filingLimit must be between 1 and 20");
        }
        this.filingLimit = filingLimit;
    }

    @Override
    public CompanySubmissionsParityReport evaluate(String ticker) {
        var normalizedTicker = normalizeTicker(ticker);
        var identity = companyIdentityPort.resolve(normalizedTicker);
        final CompanySubmissionsLegacyProjection legacy;
        try {
            legacy = CompanySubmissionsLegacyProjection.from(companyReadPort.detail(normalizedTicker));
        } catch (IllegalArgumentException error) {
            throw new CompanyResearchParityUnavailableException(
                    "Legacy company research cannot be projected into submissions metadata",
                    error
            );
        }
        if (!identity.ticker().equals(legacy.snapshot().profile().ticker())) {
            throw new CompanyResearchParityUnavailableException(
                    "Legacy company research returned a different ticker",
                    new IllegalStateException("ticker mismatch")
            );
        }

        var candidates = prioritizeServingCik(identity, legacy.snapshot().profile().cik());
        var direct = loadFirstAvailable(candidates);
        var spring = normalize(identity, direct);
        var profileDifferences = compareProfile(legacy.snapshot().profile(), spring.profile());
        var filingDifferences = compareFilings(legacy.snapshot().filings(), spring.filings());
        var differences = new ArrayList<String>();
        differences.addAll(profileDifferences);
        differences.addAll(filingDifferences);

        return new CompanySubmissionsParityReport(
                identity.ticker(),
                identity.registryCik(),
                direct.cik(),
                candidates,
                differences.isEmpty(),
                profileDifferences.isEmpty(),
                filingDifferences.isEmpty(),
                Math.min(legacy.snapshot().filings().size(), spring.filings().size()),
                direct.filings().size(),
                legacy.enrichedFilingCount(),
                differences,
                legacy.snapshot(),
                spring
        );
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
                "Unable to load SEC submissions for any continuity candidate",
                firstFailure
        );
    }

    private CompanySubmissionsSnapshot normalize(CompanyIdentity identity, CompanySubmissionsEvidence evidence) {
        var name = evidence.name().isBlank() ? identity.title() : evidence.name();
        var profile = new CompanySubmissionsSnapshot.Profile(
                identity.ticker(),
                evidence.cik(),
                name,
                evidence.exchanges().isEmpty() ? null : evidence.exchanges().getFirst(),
                evidence.sic()
        );
        var filings = evidence.filings().stream()
                .limit(filingLimit)
                .map(filing -> new CompanySubmissionsSnapshot.Filing(
                        filing.accessionNumber(),
                        filing.filingDate(),
                        filing.form(),
                        filing.primaryDocument(),
                        filing.primaryDocumentDescription(),
                        classificationPolicy.isEarningsRelated(filing),
                        filing.sourceUrl()
                ))
                .toList();
        return new CompanySubmissionsSnapshot(profile, filings);
    }

    private static List<String> compareProfile(
            CompanySubmissionsSnapshot.Profile expected,
            CompanySubmissionsSnapshot.Profile actual
    ) {
        var differences = new ArrayList<String>();
        compare(differences, "profile.ticker", expected.ticker(), actual.ticker());
        compare(differences, "profile.cik", expected.cik(), actual.cik());
        compare(differences, "profile.name", expected.name(), actual.name());
        compare(differences, "profile.exchange", expected.exchange(), actual.exchange());
        compare(differences, "profile.sic", expected.sic(), actual.sic());
        return differences;
    }

    private static List<String> compareFilings(
            List<CompanySubmissionsSnapshot.Filing> expected,
            List<CompanySubmissionsSnapshot.Filing> actual
    ) {
        var differences = new ArrayList<String>();
        compare(differences, "filings.count", expected.size(), actual.size());
        var common = Math.min(expected.size(), actual.size());
        for (var index = 0; index < common; index++) {
            var path = "filings[" + index + "]";
            var left = expected.get(index);
            var right = actual.get(index);
            compare(differences, path + ".accessionNumber", left.accessionNumber(), right.accessionNumber());
            compare(differences, path + ".filingDate", left.filingDate(), right.filingDate());
            compare(differences, path + ".form", left.form(), right.form());
            compare(differences, path + ".primaryDocument", left.primaryDocument(), right.primaryDocument());
            compare(differences, path + ".primaryDocDescription",
                    left.primaryDocumentDescription(), right.primaryDocumentDescription());
            compare(differences, path + ".isEarningsRelated", left.earningsRelated(), right.earningsRelated());
            compare(differences, path + ".filingUrl", left.filingUrl(), right.filingUrl());
        }
        return differences;
    }

    private static void compare(List<String> differences, String path, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) differences.add(path);
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        return ticker.trim().toUpperCase(Locale.ROOT);
    }
}
