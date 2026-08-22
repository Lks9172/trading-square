package io.macrosquare.company.application.port.in;

public interface EvaluateCompanyResearchParityUseCase {
    CompanyResearchParityReport evaluate(String ticker);
}
