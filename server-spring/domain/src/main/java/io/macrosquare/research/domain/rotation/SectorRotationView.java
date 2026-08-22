package io.macrosquare.research.domain.rotation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SectorRotationView(
        SectorRotationRegime regime,
        int confidence,
        Map<SectorRotationRegime, Integer> regimeScores,
        String summary,
        List<String> favoredNext,
        List<String> fadingNext,
        List<SectorRotationOutlookBucket> currentLeaders,
        List<SectorRotationOutlookBucket> nextCandidates,
        List<SectorRotationOutlookBucket> secondaryCandidates,
        List<SectorRotationOutlookBucket> fadingCandidates,
        List<SectorRotationItem> sectors
) {
    public SectorRotationView {
        regimeScores = Collections.unmodifiableMap(new LinkedHashMap<>(regimeScores));
        favoredNext = List.copyOf(favoredNext);
        fadingNext = List.copyOf(fadingNext);
        currentLeaders = List.copyOf(currentLeaders);
        nextCandidates = List.copyOf(nextCandidates);
        secondaryCandidates = List.copyOf(secondaryCandidates);
        fadingCandidates = List.copyOf(fadingCandidates);
        sectors = List.copyOf(sectors);
    }
}
