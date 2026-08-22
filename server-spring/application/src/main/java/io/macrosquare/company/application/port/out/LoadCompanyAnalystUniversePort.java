package io.macrosquare.company.application.port.out;

import java.util.List;

/** Supplies the current research-company universe without leaking catalog models. */
@FunctionalInterface
public interface LoadCompanyAnalystUniversePort {

    List<String> loadTickers();
}
