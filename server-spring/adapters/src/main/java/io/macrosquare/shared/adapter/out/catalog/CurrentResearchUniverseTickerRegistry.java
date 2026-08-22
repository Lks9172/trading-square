package io.macrosquare.shared.adapter.out.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Adapter-boundary registry for symbol lifecycle changes in immutable cutover
 * catalog artifacts.
 *
 * <p>A replacement is a new company identity, not a ticker alias. Callers may
 * copy classification metadata, but must never copy the retired company's
 * financial metrics or scores.</p>
 */
public final class CurrentResearchUniverseTickerRegistry {

    private static final Map<String, String> ALIASES = Map.of("MMC", "MRSH");
    private static final Set<String> RETIRED = Set.of("CTRA", "EA");
    private static final Map<String, SectorReplacement> REPLACEMENTS = Map.of(
            "EA", new SectorReplacement(
                    "EA", "communication-services", "커뮤니케이션 서비스",
                    "RBLX", "Roblox Corporation", "NYSE", "7372", "SECTOR_XLC"),
            "CTRA", new SectorReplacement(
                    "CTRA", "energy", "에너지",
                    "EPD", "Enterprise Products Partners L.P.", "NYSE", "4922", "SECTOR_XLE")
    );

    private CurrentResearchUniverseTickerRegistry() {
    }

    public static String canonicalTicker(String value) {
        var normalized = value.trim().toUpperCase(Locale.ROOT).replace('.', '-');
        return ALIASES.getOrDefault(normalized, normalized);
    }

    public static boolean retired(String ticker) {
        return RETIRED.contains(canonicalTicker(ticker));
    }

    public static Optional<SectorReplacement> replacementForRetired(String ticker) {
        return Optional.ofNullable(REPLACEMENTS.get(canonicalTicker(ticker)));
    }

    public static boolean isReplacementTicker(String ticker) {
        var canonical = canonicalTicker(ticker);
        return REPLACEMENTS.values().stream().anyMatch(value -> value.ticker().equals(canonical));
    }

    public static Optional<SectorReplacement> replacementByTicker(String ticker) {
        var canonical = canonicalTicker(ticker);
        return REPLACEMENTS.values().stream().filter(value -> value.ticker().equals(canonical)).findFirst();
    }

    /** Returns the immutable cutover artifact symbol for a current identity. */
    public static String legacyStorageTicker(String ticker) {
        var canonical = canonicalTicker(ticker);
        return ALIASES.entrySet().stream()
                .filter(entry -> entry.getValue().equals(canonical))
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst()
                .orElse(canonical);
    }

    public static List<SectorReplacement> replacements() {
        return REPLACEMENTS.values().stream()
                .sorted(java.util.Comparator.comparing(SectorReplacement::retiredTicker))
                .toList();
    }

    public record SectorReplacement(
            String retiredTicker,
            String sectorId,
            String sectorLabel,
            String ticker,
            String name,
            String exchange,
            String sic,
            String sectorKey
    ) {
    }
}
