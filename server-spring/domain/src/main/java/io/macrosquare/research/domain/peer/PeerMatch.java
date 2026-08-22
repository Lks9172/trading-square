package io.macrosquare.research.domain.peer;

public record PeerMatch(
        String ticker,
        String companyName,
        int sic,
        String sicDescription,
        String sectorKey,
        int similarityScore,
        String matchLevel
) {
}
