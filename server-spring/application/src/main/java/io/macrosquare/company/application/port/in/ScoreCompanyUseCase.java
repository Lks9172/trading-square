package io.macrosquare.company.application.port.in;

import io.macrosquare.company.domain.model.CompanyScore;

public interface ScoreCompanyUseCase {
    CompanyScore score(ScoreCompanyCommand command);
}
