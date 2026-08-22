package io.macrosquare.policy.application.service;

import io.macrosquare.policy.application.port.in.QueryPolicyIntelligenceUseCase;
import io.macrosquare.policy.application.port.out.PolicyAnalysisRepository;
import io.macrosquare.policy.application.port.out.PolicyCalibrationRepository;
import io.macrosquare.policy.domain.model.PolicyIntelligenceSnapshot;
import io.macrosquare.policy.domain.service.PolicyConfidenceCalibrationPolicy;
import io.macrosquare.policy.domain.service.PolicyToneAnalysisPolicy;

import java.time.Clock;
import java.util.Objects;

public final class QueryPolicyIntelligenceService implements QueryPolicyIntelligenceUseCase {

    private final PolicyAnalysisRepository repository;
    private final PolicyToneAnalysisPolicy policy;
    private final Clock clock;
    private final int documentLimit;
    private final PolicyCalibrationRepository calibrationRepository;
    private final PolicyConfidenceCalibrationPolicy calibrationPolicy;

    public QueryPolicyIntelligenceService(
            PolicyAnalysisRepository repository,
            PolicyToneAnalysisPolicy policy,
            Clock clock,
            int documentLimit
    ) {
        this(repository, null, null, policy, clock, documentLimit);
    }

    public QueryPolicyIntelligenceService(
            PolicyAnalysisRepository repository,
            PolicyCalibrationRepository calibrationRepository,
            PolicyConfidenceCalibrationPolicy calibrationPolicy,
            PolicyToneAnalysisPolicy policy,
            Clock clock,
            int documentLimit
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.calibrationRepository = calibrationRepository;
        this.calibrationPolicy = calibrationPolicy;
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        if (documentLimit < 1 || documentLimit > 30) throw new IllegalArgumentException("documentLimit must be between 1 and 30");
        this.documentLimit = documentLimit;
    }

    @Override
    public PolicyIntelligenceSnapshot query() {
        var snapshot = policy.aggregate(repository.loadLatest(documentLimit), clock.instant());
        if (calibrationRepository == null || calibrationPolicy == null) return snapshot;
        var calibration = calibrationPolicy.calibrate(
                calibrationRepository.loadChronological(240), snapshot.confidence());
        return new PolicyIntelligenceSnapshot(
                snapshot.asOf(), snapshot.tone(), snapshot.toneScore(), snapshot.confidence(),
                snapshot.documentCount(), snapshot.summary(), snapshot.documents(), calibration);
    }
}
