package io.macrosquare.policy.application.port.out;

import io.macrosquare.policy.domain.model.PolicyCalibrationObservation;

import java.util.List;

public interface PolicyCalibrationRepository {
    int save(List<PolicyCalibrationObservation> observations);

    List<PolicyCalibrationObservation> loadChronological(int limit);
}
