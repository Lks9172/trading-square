package io.macrosquare.company.application.port.in;

public interface EvaluateCompanySubmissionsParityUseCase {

    CompanySubmissionsParityReport evaluate(String ticker);
}
