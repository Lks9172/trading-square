package io.macrosquare.compatibility.application.port.out;

import java.util.Set;

/** Supplies the current listed-company universe used by the earnings calendar. */
public interface LoadEarningsUniversePort {

    Set<String> loadTickers();
}
