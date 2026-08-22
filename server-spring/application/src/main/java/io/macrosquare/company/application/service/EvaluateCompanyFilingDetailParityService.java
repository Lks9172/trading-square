package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyIdentity;
import io.macrosquare.company.application.port.in.CompanyFilingDetailParityReport;
import io.macrosquare.company.application.port.in.EvaluateCompanyFilingDetailParityUseCase;
import io.macrosquare.company.application.port.out.CompanyFilingDetailUnavailableException;
import io.macrosquare.company.application.port.out.CompanyFilingDocumentUnavailableException;
import io.macrosquare.company.application.port.out.CompanyResearchParityUnavailableException;
import io.macrosquare.company.application.port.out.CompanySubmissionsUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyFilingDetailEvidencePort;
import io.macrosquare.company.application.port.out.LoadCompanyFilingDocumentContentPort;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.application.port.out.LoadCompanySubmissionsEvidencePort;
import io.macrosquare.company.application.port.out.ResolveCompanyIdentityPort;
import io.macrosquare.company.domain.model.CompanyFilingEvidence;
import io.macrosquare.company.domain.model.CompanyGuidanceAnalysis;
import io.macrosquare.company.domain.model.CompanyGuidanceSummary;
import io.macrosquare.company.domain.model.CompanyIrMaterial;
import io.macrosquare.company.domain.model.CompanySubmissionsEvidence;
import io.macrosquare.company.domain.service.CompanyFilingClassificationPolicy;
import io.macrosquare.company.domain.service.CompanyGuidanceParsingPolicy;
import io.macrosquare.company.domain.service.CompanyIrMaterialPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only Strangler parallel run for filing text and Exhibit 99.x discovery.
 * Existing Node materials are compared, never used to construct the Spring result.
 */
public final class EvaluateCompanyFilingDetailParityService
        implements EvaluateCompanyFilingDetailParityUseCase {

    private final LoadCompanyReadPort companyReadPort;
    private final ResolveCompanyIdentityPort companyIdentityPort;
    private final LoadCompanySubmissionsEvidencePort submissionsPort;
    private final LoadCompanyFilingDetailEvidencePort filingDetailPort;
    private final LoadCompanyFilingDocumentContentPort documentContentPort;
    private final CompanyFilingClassificationPolicy filingClassificationPolicy;
    private final CompanyIrMaterialPolicy irMaterialPolicy;
    private final CompanyGuidanceParsingPolicy guidanceParsingPolicy;
    private final int primaryFilingLimit;
    private final int attachmentFilingLimit;
    private final int materialLimit;

    public EvaluateCompanyFilingDetailParityService(
            LoadCompanyReadPort companyReadPort,
            ResolveCompanyIdentityPort companyIdentityPort,
            LoadCompanySubmissionsEvidencePort submissionsPort,
            LoadCompanyFilingDetailEvidencePort filingDetailPort,
            LoadCompanyFilingDocumentContentPort documentContentPort,
            CompanyFilingClassificationPolicy filingClassificationPolicy,
            CompanyIrMaterialPolicy irMaterialPolicy,
            CompanyGuidanceParsingPolicy guidanceParsingPolicy,
            int primaryFilingLimit,
            int attachmentFilingLimit,
            int materialLimit
    ) {
        this.companyReadPort = Objects.requireNonNull(companyReadPort);
        this.companyIdentityPort = Objects.requireNonNull(companyIdentityPort);
        this.submissionsPort = Objects.requireNonNull(submissionsPort);
        this.filingDetailPort = Objects.requireNonNull(filingDetailPort);
        this.documentContentPort = Objects.requireNonNull(documentContentPort);
        this.filingClassificationPolicy = Objects.requireNonNull(filingClassificationPolicy);
        this.irMaterialPolicy = Objects.requireNonNull(irMaterialPolicy);
        this.guidanceParsingPolicy = Objects.requireNonNull(guidanceParsingPolicy);
        if (primaryFilingLimit < 1 || attachmentFilingLimit < 1 || materialLimit < 1) {
            throw new IllegalArgumentException("filing and material limits must be positive");
        }
        this.primaryFilingLimit = primaryFilingLimit;
        this.attachmentFilingLimit = attachmentFilingLimit;
        this.materialLimit = materialLimit;
    }

    @Override
    public CompanyFilingDetailParityReport evaluate(String ticker) {
        var normalizedTicker = normalizeTicker(ticker);
        var identity = companyIdentityPort.resolve(normalizedTicker);
        final var research = companyReadPort.detail(normalizedTicker);
        final CompanySubmissionsLegacyProjection legacySubmissions;
        final List<CompanyIrMaterial> legacyMaterials;
        try {
            legacySubmissions = CompanySubmissionsLegacyProjection.from(research);
            legacyMaterials = CompanyIrMaterialsLegacyProjection.from(research);
        } catch (IllegalArgumentException error) {
            throw new CompanyResearchParityUnavailableException(
                    "Legacy company research cannot be projected into filing-detail metadata",
                    error
            );
        }
        if (!identity.ticker().equals(legacySubmissions.snapshot().profile().ticker())) {
            throw new CompanyResearchParityUnavailableException(
                    "Legacy company research returned a different ticker",
                    new IllegalStateException("ticker mismatch")
            );
        }

        var direct = loadFirstAvailable(prioritizeServingCik(
                identity,
                legacySubmissions.snapshot().profile().cik()
        ));
        var primary = direct.filings().stream()
                .limit(primaryFilingLimit)
                .map(filing -> irMaterialPolicy.primaryMaterial(
                        filing,
                        filingClassificationPolicy.isEarningsRelated(filing)
                ))
                .flatMap(java.util.Optional::stream)
                .filter(irMaterialPolicy::shouldRetain)
                .toList();

        var candidates = selectAttachmentCandidates(direct.filings());
        var selectedAccessions = candidates.stream().map(CompanyFilingEvidence::accessionNumber).toList();
        var indexFailures = new ArrayList<String>();
        var indexed = new ArrayList<CompanyIrMaterial>();
        var inspectedIndexes = 0;
        for (var filing : candidates) {
            try {
                var detail = filingDetailPort.load(direct.cik(), filing.accessionNumber());
                inspectedIndexes++;
                detail.documents().stream()
                        .map(document -> irMaterialPolicy.indexedMaterial(filing, document))
                        .flatMap(java.util.Optional::stream)
                        .limit(6)
                        .forEach(indexed::add);
            } catch (CompanyFilingDetailUnavailableException error) {
                indexFailures.add(filing.accessionNumber());
            }
        }

        var combined = new LinkedHashMap<String, CompanyIrMaterial>();
        primary.forEach(material -> combined.putIfAbsent(material.identityKey(), material));
        indexed.stream()
                .filter(irMaterialPolicy::shouldRetain)
                .forEach(material -> combined.putIfAbsent(material.identityKey(), material));

        var summaryFailures = new ArrayList<String>();
        var analysisResults = combined.values().stream()
                .limit(materialLimit)
                .map(material -> analyze(material, summaryFailures))
                .toList();
        var springMaterials = analysisResults.stream().map(MaterialAnalysisResult::material).toList();

        var comparison = compareBaseline(legacyMaterials, springMaterials);
        var directAttachmentCount = (int) springMaterials.stream()
                .filter(material -> material.source() != CompanyIrMaterial.Source.PRIMARY)
                .count();
        var summarizedDirectAttachmentCount = (int) springMaterials.stream()
                .filter(material -> material.source() != CompanyIrMaterial.Source.PRIMARY)
                .filter(material -> material.summary() != null && !material.summary().isBlank())
                .count();
        var legacyKeys = legacyMaterials.stream()
                .map(CompanyIrMaterial::identityKey)
                .collect(java.util.stream.Collectors.toSet());
        var directDiscoveryImprovement = springMaterials.stream()
                .filter(material -> material.source() != CompanyIrMaterial.Source.PRIMARY)
                .anyMatch(material -> !legacyKeys.contains(material.identityKey()));
        var directCoveragePassed = indexFailures.isEmpty() && inspectedIndexes == candidates.size();
        var pdfMaterialCount = (int) springMaterials.stream()
                .filter(material -> material.contentType() == CompanyIrMaterial.ContentType.PDF)
                .count();
        var parsedPdfMaterialCount = (int) analysisResults.stream()
                .filter(MaterialAnalysisResult::contentLoaded)
                .filter(result -> result.material().contentType() == CompanyIrMaterial.ContentType.PDF)
                .count();
        var summarizedPdfMaterialCount = (int) analysisResults.stream()
                .filter(MaterialAnalysisResult::contentLoaded)
                .map(MaterialAnalysisResult::material)
                .filter(material -> material.contentType() == CompanyIrMaterial.ContentType.PDF)
                .filter(material -> material.summary() != null && !material.summary().isBlank())
                .count();
        var pdfExtractionCoveragePassed = parsedPdfMaterialCount == pdfMaterialCount;
        var guidanceEligibleMaterialCount = springMaterials.size();
        var guidanceAnalyzedMaterialCount = (int) analysisResults.stream()
                .filter(MaterialAnalysisResult::contentLoaded)
                .count();
        var guidance = analysisResults.stream()
                .filter(MaterialAnalysisResult::contentLoaded)
                .filter(result -> result.guidance() != null && result.guidance().relevant())
                .map(result -> CompanyGuidanceAnalysis.from(result.material(), result.guidance()))
                .toList();
        var structuredGuidanceMaterialCount = (int) guidance.stream()
                .filter(result -> result.summary().structuredMetricCount() > 0)
                .count();
        var structuredGuidanceMetricCount = guidance.stream()
                .mapToInt(result -> result.summary().structuredMetricCount())
                .sum();
        var guidanceExtractionCoveragePassed = guidanceAnalyzedMaterialCount == guidanceEligibleMaterialCount;

        return new CompanyFilingDetailParityReport(
                identity.ticker(),
                identity.registryCik(),
                direct.cik(),
                direct.filings().size(),
                candidates.size(),
                inspectedIndexes,
                comparison.metadataDifferences().isEmpty()
                        && directCoveragePassed
                        && pdfExtractionCoveragePassed
                        && guidanceExtractionCoveragePassed,
                comparison.metadataDifferences().isEmpty(),
                comparison.summaryDifferences().isEmpty(),
                legacyMaterials.equals(springMaterials),
                directCoveragePassed,
                directDiscoveryImprovement,
                pdfExtractionCoveragePassed,
                guidanceExtractionCoveragePassed,
                legacyMaterials.size(),
                springMaterials.size(),
                directAttachmentCount,
                summarizedDirectAttachmentCount,
                pdfMaterialCount,
                parsedPdfMaterialCount,
                summarizedPdfMaterialCount,
                guidanceEligibleMaterialCount,
                guidanceAnalyzedMaterialCount,
                guidance.size(),
                structuredGuidanceMaterialCount,
                structuredGuidanceMetricCount,
                selectedAccessions,
                indexFailures,
                summaryFailures,
                comparison.allDifferences(),
                legacyMaterials,
                springMaterials,
                guidance
        );
    }

    private MaterialAnalysisResult analyze(CompanyIrMaterial material, List<String> failures) {
        try {
            var content = documentContentPort.loadContent(material.url());
            var summarized = material.summary() == null
                    ? irMaterialPolicy.summarize(content.text()).map(material::withSummary).orElse(material)
                    : material;
            return new MaterialAnalysisResult(
                    summarized,
                    true,
                    guidanceParsingPolicy.summarize(content.text())
            );
        } catch (CompanyFilingDocumentUnavailableException error) {
            failures.add(material.url());
            return new MaterialAnalysisResult(material, false, null);
        }
    }

    private List<CompanyFilingEvidence> selectAttachmentCandidates(List<CompanyFilingEvidence> filings) {
        var ordered = new LinkedHashMap<String, CompanyFilingEvidence>();
        filings.stream()
                .filter(filingClassificationPolicy::isEarningsCandidate)
                .forEach(filing -> ordered.putIfAbsent(filing.accessionNumber(), filing));
        filings.stream()
                .filter(EvaluateCompanyFilingDetailParityService::isAttachmentBearingForm)
                .forEach(filing -> ordered.putIfAbsent(filing.accessionNumber(), filing));
        return ordered.values().stream().limit(attachmentFilingLimit).toList();
    }

    private static boolean isAttachmentBearingForm(CompanyFilingEvidence filing) {
        return "8-K".equals(filing.form()) || "6-K".equals(filing.form());
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

    private static BaselineComparison compareBaseline(
            List<CompanyIrMaterial> legacy,
            List<CompanyIrMaterial> spring
    ) {
        var springByKey = new LinkedHashMap<String, CompanyIrMaterial>();
        spring.forEach(material -> springByKey.putIfAbsent(material.identityKey(), material));
        var metadata = new ArrayList<String>();
        var summaries = new ArrayList<String>();
        for (var index = 0; index < legacy.size(); index++) {
            var expected = legacy.get(index);
            var actual = springByKey.get(expected.identityKey());
            var path = "irMaterials[" + index + "]";
            if (actual == null) {
                metadata.add(path + ".missing");
                continue;
            }
            compare(metadata, path + ".form", expected.form(), actual.form());
            compare(metadata, path + ".filingDate", expected.filingDate(), actual.filingDate());
            compare(metadata, path + ".type", expected.type(), actual.type());
            compare(metadata, path + ".source", expected.source(), actual.source());
            compare(metadata, path + ".contentType", expected.contentType(), actual.contentType());
            // A newly extracted summary is an intentional enrichment when legacy had none.
            // Existing non-null legacy summaries must still remain byte-compatible.
            if (expected.summary() != null) {
                compare(summaries, path + ".summary", expected.summary(), actual.summary());
            }
        }
        var all = new ArrayList<String>(metadata.size() + summaries.size());
        all.addAll(metadata);
        all.addAll(summaries);
        return new BaselineComparison(List.copyOf(metadata), List.copyOf(summaries), List.copyOf(all));
    }

    private static void compare(List<String> differences, String path, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) differences.add(path);
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        return ticker.trim().toUpperCase(Locale.ROOT);
    }

    private record BaselineComparison(
            List<String> metadataDifferences,
            List<String> summaryDifferences,
            List<String> allDifferences
    ) {
    }

    private record MaterialAnalysisResult(
            CompanyIrMaterial material,
            boolean contentLoaded,
            CompanyGuidanceSummary guidance
    ) {
    }
}
