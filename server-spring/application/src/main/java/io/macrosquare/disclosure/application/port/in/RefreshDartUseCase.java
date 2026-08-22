package io.macrosquare.disclosure.application.port.in;

import io.macrosquare.disclosure.application.model.DartRefreshReport;

@FunctionalInterface
public interface RefreshDartUseCase {
    DartRefreshReport refresh();
}
