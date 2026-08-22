package io.macrosquare.technical.domain;

import java.util.Objects;

/** Daily timing evidence plus a separately aggregated weekly confirmation. */
public record MacdMultiTimeframeAnalysis(
        MacdSignalAnalysis daily,
        MacdSignalAnalysis weekly,
        boolean currentWeekProvisional
) {
    public MacdMultiTimeframeAnalysis {
        Objects.requireNonNull(daily, "daily");
        Objects.requireNonNull(weekly, "weekly");
    }
}
