package io.macrosquare.research.domain.peer;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public final class PeerDiscoveryPolicy {

    public PeerDiscoveryResult discover(
            PeerTaxonomy target,
            List<PeerTaxonomy> universe,
            LocalDate asOf,
            int limit
    ) {
        if (target == null) return new PeerDiscoveryResult(
                asOf, null, List.of(), 0, "대상 종목의 SEC SIC taxonomy를 수집 중입니다.");
        if (limit < 1 || limit > 40) throw new IllegalArgumentException("limit must be between 1 and 40");
        var candidates = universe.stream()
                .filter(value -> !value.ticker().equals(target.ticker()))
                .filter(value -> value.activeOn(asOf))
                .map(value -> match(target, value))
                .filter(value -> value.similarityScore() >= 45)
                .sorted(Comparator.comparingInt(PeerMatch::similarityScore).reversed()
                        .thenComparing(PeerMatch::ticker))
                .toList();
        return new PeerDiscoveryResult(
                asOf, target, candidates.stream().limit(limit).toList(), candidates.size(),
                "SEC SIC exact(100)→industry group(85)→major group(70)→표준 섹터(45) 순입니다. as-of 유효기간으로 당시 생존 종목만 비교합니다.");
    }

    private static PeerMatch match(PeerTaxonomy target, PeerTaxonomy candidate) {
        final int score;
        final String level;
        if (target.sic() == candidate.sic()) {
            score = 100;
            level = "EXACT_SIC";
        } else if (target.sic() / 10 == candidate.sic() / 10) {
            score = 85;
            level = "SIC_INDUSTRY_GROUP";
        } else if (target.sic() / 100 == candidate.sic() / 100) {
            score = 70;
            level = "SIC_MAJOR_GROUP";
        } else if (target.sectorKey().equals(candidate.sectorKey())) {
            score = 45;
            level = "STANDARD_SECTOR";
        } else {
            score = 0;
            level = "UNRELATED";
        }
        return new PeerMatch(
                candidate.ticker(), candidate.companyName(), candidate.sic(), candidate.sicDescription(),
                candidate.sectorKey(), score, level);
    }
}
