package io.macrosquare.research.application.port.out;

import io.macrosquare.research.application.model.CurrentCompanyMetric;

import java.util.Map;

@FunctionalInterface
public interface LoadCurrentCompanyMetricsPort {

    Map<String, CurrentCompanyMetric> loadAll();
}
