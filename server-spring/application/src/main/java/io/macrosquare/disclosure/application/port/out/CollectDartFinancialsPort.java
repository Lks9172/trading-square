package io.macrosquare.disclosure.application.port.out;

import io.macrosquare.disclosure.domain.model.DartCompany;
import io.macrosquare.disclosure.domain.model.DartFinancialMetric;

import java.util.List;

@FunctionalInterface
public interface CollectDartFinancialsPort {
    List<DartFinancialMetric> collect(DartCompany company, int businessYear, String reportCode);
}
