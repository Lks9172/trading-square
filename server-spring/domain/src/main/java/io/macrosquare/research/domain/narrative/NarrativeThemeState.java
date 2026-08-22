package io.macrosquare.research.domain.narrative;

import java.util.List;

public record NarrativeThemeState(
        NarrativeTheme theme,
        NarrativeStage stage,
        int heatScore,
        List<String> drivers,
        List<String> risks,
        List<NarrativeProxyScore> proxyScores,
        List<NarrativeExternalSignal> externalSignals
) {
    public NarrativeThemeState {
        if (heatScore < 0 || heatScore > 100) throw new IllegalArgumentException("heatScore must be between 0 and 100");
        drivers = List.copyOf(drivers);
        risks = List.copyOf(risks);
        proxyScores = List.copyOf(proxyScores);
        externalSignals = List.copyOf(externalSignals);
    }
}
