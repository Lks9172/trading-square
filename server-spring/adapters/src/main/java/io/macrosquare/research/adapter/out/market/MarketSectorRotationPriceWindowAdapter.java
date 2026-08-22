package io.macrosquare.research.adapter.out.market;

import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.research.application.port.out.LoadSectorRotationPriceWindowPort;
import io.macrosquare.research.domain.rotation.SectorRotationForwardWindow;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Read-only ACL from market-owned total-return observations into Research validation windows. */
public final class MarketSectorRotationPriceWindowAdapter implements LoadSectorRotationPriceWindowPort {

    private static final Map<String, String> SERIES = Map.ofEntries(
            Map.entry("SECTOR_XLK", "XLK_TR"), Map.entry("SECTOR_XLF", "XLF_TR"),
            Map.entry("SECTOR_XLE", "XLE_TR"), Map.entry("SECTOR_XLV", "XLV_TR"),
            Map.entry("SECTOR_XLI", "XLI_TR"), Map.entry("SECTOR_XLY", "XLY_TR"),
            Map.entry("SECTOR_XLC", "XLC_TR"), Map.entry("SECTOR_XLB", "XLB_TR"),
            Map.entry("SECTOR_XLRE", "XLRE_TR"), Map.entry("SECTOR_XLU", "XLU_TR"),
            Map.entry("SECTOR_XLP", "XLP_TR"));
    private static final int SAFE_US_CLOSE_HOUR_UTC = 22;
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final MarketObservationRepository observations;
    private final Clock clock;
    private volatile CachedCommonValues cached;

    public MarketSectorRotationPriceWindowAdapter(MarketObservationRepository observations) {
        this(observations, Clock.systemUTC());
    }

    public MarketSectorRotationPriceWindowAdapter(MarketObservationRepository observations, Clock clock) {
        this.observations = Objects.requireNonNull(observations);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<LocalDate> latestCompletedCommonDate(Instant calculatedAt) {
        Objects.requireNonNull(calculatedAt);
        var dates = commonValues().dates();
        var utc = calculatedAt.atZone(ZoneOffset.UTC);
        var inclusive = utc.getHour() >= SAFE_US_CLOSE_HOUR_UTC;
        return dates.stream().filter(date -> inclusive ? !date.isAfter(utc.toLocalDate())
                        : date.isBefore(utc.toLocalDate()))
                .max(LocalDate::compareTo);
    }

    @Override
    public Optional<SectorRotationForwardWindow> loadForwardWindow(LocalDate startOn, int tradingSessions) {
        Objects.requireNonNull(startOn);
        if (tradingSessions != 21 && tradingSessions != 63 && tradingSessions != 126) {
            throw new IllegalArgumentException("unsupported outcome horizon");
        }
        var common = commonValues();
        var startIndex = common.dates().indexOf(startOn);
        if (startIndex < 0 || startIndex + tradingSessions >= common.dates().size()) return Optional.empty();
        var endOn = common.dates().get(startIndex + tradingSessions);
        var benchmark = returnPct(common.values().get("SPY_TR").get(startOn),
                common.values().get("SPY_TR").get(endOn));
        var returns = new LinkedHashMap<String, Double>();
        SERIES.forEach((sectorKey, seriesKey) -> returns.put(sectorKey,
                returnPct(common.values().get(seriesKey).get(startOn),
                        common.values().get(seriesKey).get(endOn))));
        var equalWeight = returns.values().stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return Optional.of(new SectorRotationForwardWindow(
                startOn, endOn, tradingSessions, benchmark, equalWeight, returns));
    }

    private CommonValues commonValues() {
        var current = cached;
        var now = clock.instant();
        if (fresh(current, now)) {
            return current.values();
        }
        synchronized (this) {
            current = cached;
            now = clock.instant();
            if (fresh(current, now)) {
                return current.values();
            }
            var loaded = loadCommonValues();
            cached = new CachedCommonValues(now, loaded);
            return loaded;
        }
    }

    private CommonValues loadCommonValues() {
        var values = new LinkedHashMap<String, Map<LocalDate, Double>>();
        var keys = new ArrayList<>(SERIES.values());
        keys.add("SPY_TR");
        Set<LocalDate> common = null;
        for (var key : keys) {
            var byDate = new LinkedHashMap<LocalDate, Double>();
            observations.loadHistory(MarketDataSource.YAHOO, key).forEach(point -> {
                if (Double.isFinite(point.value()) && point.value() > 0) {
                    byDate.put(point.observationDate(), point.value());
                }
            });
            values.put(key, Map.copyOf(byDate));
            common = common == null ? new java.util.HashSet<>(byDate.keySet()) : intersect(common, byDate.keySet());
        }
        var dates = common == null ? List.<LocalDate>of() : common.stream().sorted().toList();
        return new CommonValues(dates, Map.copyOf(values));
    }

    private static boolean fresh(CachedCommonValues value, Instant now) {
        return value != null && !now.isBefore(value.loadedAt()) && now.isBefore(value.loadedAt().plus(CACHE_TTL));
    }

    private static Set<LocalDate> intersect(Set<LocalDate> current, Set<LocalDate> next) {
        var result = new java.util.HashSet<>(current);
        result.retainAll(next);
        return result;
    }

    private static double returnPct(double start, double end) {
        return (end / start - 1d) * 100d;
    }

    private record CommonValues(List<LocalDate> dates, Map<String, Map<LocalDate, Double>> values) {}
    private record CachedCommonValues(Instant loadedAt, CommonValues values) {}
}
