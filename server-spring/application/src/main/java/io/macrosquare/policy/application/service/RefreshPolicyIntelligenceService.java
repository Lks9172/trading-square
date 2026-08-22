package io.macrosquare.policy.application.service;

import io.macrosquare.policy.application.model.PolicyRefreshReport;
import io.macrosquare.policy.application.port.in.RefreshPolicyIntelligenceUseCase;
import io.macrosquare.policy.application.port.out.CollectPolicyDocumentsPort;
import io.macrosquare.policy.application.port.out.PolicyAnalysisRepository;
import io.macrosquare.policy.application.port.out.PolicyCalibrationRepository;
import io.macrosquare.policy.domain.service.PolicyConfidenceCalibrationPolicy;
import io.macrosquare.policy.domain.service.PolicyToneAnalysisPolicy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Objects;

public final class RefreshPolicyIntelligenceService implements RefreshPolicyIntelligenceUseCase {

    private final CollectPolicyDocumentsPort collector;
    private final PolicyAnalysisRepository repository;
    private final PolicyToneAnalysisPolicy policy;
    private final PolicyConfidenceCalibrationPolicy calibrationPolicy;
    private final PolicyCalibrationRepository calibrationRepository;
    private final int maximumDocuments;
    private final Clock clock;

    public RefreshPolicyIntelligenceService(
            CollectPolicyDocumentsPort collector,
            PolicyAnalysisRepository repository,
            PolicyToneAnalysisPolicy policy,
            int maximumDocuments,
            Clock clock
    ) {
        this(collector, repository, null, null, policy, maximumDocuments, clock);
    }

    public RefreshPolicyIntelligenceService(
            CollectPolicyDocumentsPort collector,
            PolicyAnalysisRepository repository,
            PolicyCalibrationRepository calibrationRepository,
            PolicyConfidenceCalibrationPolicy calibrationPolicy,
            PolicyToneAnalysisPolicy policy,
            int maximumDocuments,
            Clock clock
    ) {
        this.collector = Objects.requireNonNull(collector);
        this.repository = Objects.requireNonNull(repository);
        this.calibrationRepository = calibrationRepository;
        this.calibrationPolicy = calibrationPolicy;
        this.policy = Objects.requireNonNull(policy);
        if (maximumDocuments < 1 || maximumDocuments > 120) {
            throw new IllegalArgumentException("maximumDocuments must be between 1 and 120");
        }
        this.maximumDocuments = maximumDocuments;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public PolicyRefreshReport refresh() {
        var started = clock.instant();
        var failures = new ArrayList<String>();
        var documents = collector.collect(maximumDocuments);
        var analyses = new ArrayList<io.macrosquare.policy.domain.model.PolicyDocumentAnalysis>();
        for (var document : documents) {
            try {
                analyses.add(policy.analyze(document));
            } catch (RuntimeException error) {
                failures.add(document.id() + ":" + error.getClass().getSimpleName());
            }
        }
        var persisted = analyses.isEmpty() ? 0 : repository.save(analyses);
        if (calibrationRepository != null && calibrationPolicy != null) {
            var observations = analyses.stream().map(calibrationPolicy::observe)
                    .filter(Objects::nonNull).toList();
            if (!observations.isEmpty()) calibrationRepository.save(observations);
        }
        return new PolicyRefreshReport(
                started, clock.instant(), documents.size(), persisted, failures);
    }
}
