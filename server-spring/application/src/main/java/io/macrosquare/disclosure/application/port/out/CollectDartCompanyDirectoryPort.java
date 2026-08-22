package io.macrosquare.disclosure.application.port.out;

import io.macrosquare.disclosure.domain.model.DartCompany;

import java.util.List;

@FunctionalInterface
public interface CollectDartCompanyDirectoryPort {
    List<DartCompany> collect();
}
