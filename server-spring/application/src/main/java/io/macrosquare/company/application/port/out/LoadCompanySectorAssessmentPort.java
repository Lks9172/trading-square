package io.macrosquare.company.application.port.out;

import io.macrosquare.company.application.model.CompanySectorAssessment;

import java.util.Optional;

public interface LoadCompanySectorAssessmentPort {
    Optional<CompanySectorAssessment> load(String ticker);
}
