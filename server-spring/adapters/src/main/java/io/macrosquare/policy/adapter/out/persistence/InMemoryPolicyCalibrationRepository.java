package io.macrosquare.policy.adapter.out.persistence;

import io.macrosquare.policy.application.port.out.PolicyCalibrationRepository;
import io.macrosquare.policy.domain.model.PolicyCalibrationObservation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryPolicyCalibrationRepository implements PolicyCalibrationRepository {

    private final Map<String, PolicyCalibrationObservation> values = new LinkedHashMap<>();

    @Override
    public synchronized int save(List<PolicyCalibrationObservation> observations) {
        observations.forEach(value -> values.put(value.documentId(), value));
        return observations.size();
    }

    @Override
    public synchronized List<PolicyCalibrationObservation> loadChronological(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit is out of range");
        return values.values().stream()
                .sorted(Comparator.comparing(PolicyCalibrationObservation::publishedAt)
                        .thenComparing(PolicyCalibrationObservation::documentId))
                .skip(Math.max(0, values.size() - limit))
                .toList();
    }
}
