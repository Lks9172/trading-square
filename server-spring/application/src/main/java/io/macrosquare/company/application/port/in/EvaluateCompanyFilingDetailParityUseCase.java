package io.macrosquare.company.application.port.in;

@FunctionalInterface
public interface EvaluateCompanyFilingDetailParityUseCase {
    CompanyFilingDetailParityReport evaluate(String ticker);
}
