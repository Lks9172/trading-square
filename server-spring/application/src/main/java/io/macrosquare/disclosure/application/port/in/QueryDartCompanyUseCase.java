package io.macrosquare.disclosure.application.port.in;

import io.macrosquare.disclosure.domain.model.DartCompanySnapshot;

@FunctionalInterface
public interface QueryDartCompanyUseCase {
    DartCompanySnapshot query(String stockCode);
}
