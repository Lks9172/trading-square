package io.macrosquare.disclosure.application.port.out;

import io.macrosquare.disclosure.domain.model.DartCompany;
import io.macrosquare.disclosure.domain.model.DartDisclosure;

import java.time.LocalDate;
import java.util.List;

@FunctionalInterface
public interface CollectDartDisclosuresPort {
    List<DartDisclosure> collect(DartCompany company, LocalDate from, LocalDate to, int limit);
}
