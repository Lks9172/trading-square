package io.macrosquare.market.adapter.out.persistence;

import io.macrosquare.market.adapter.out.json.MarketReadJsonMapper;
import io.macrosquare.market.application.model.MarketReadModels.Document;
import io.macrosquare.market.application.port.out.LoadMarketReadPort;
import io.macrosquare.market.application.port.out.MarketReadUnavailableException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Relational read model for market history and coverage routes. */
public final class JdbcMarketReadAdapter implements LoadMarketReadPort {

    private static final Map<String, Integer> RANGE_DAYS = Map.of(
            "1D", 1, "1W", 7, "1M", 31, "1Y", 366, "5Y", 1826
    );
    private static final Map<String, Integer> INTERVAL_STEPS = Map.of("1D", 1, "1W", 5, "1M", 21);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort snapshot;

    public JdbcMarketReadAdapter(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            Clock clock,
            io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort snapshot
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.snapshot = Objects.requireNonNull(snapshot);
    }

    @Override
    public Document loadLatestSnapshot() {
        return snapshot.loadCurrentOrSeed();
    }

    @Override
    public Document loadHistoryCoverage() {
        try {
            var root = objectMapper.createObjectNode();
            jdbc.query("""
                    select source, series_key, count(*) as point_count,
                           min(observed_on) as oldest, max(observed_on) as newest
                    from market.observation
                    group by source, series_key
                    order by source, series_key
                    """, row -> {
                var source = row.getString("source");
                var name = (source + "-" + row.getString("series_key")).toUpperCase(Locale.ROOT);
                var value = root.putObject(name);
                value.put("count", row.getLong("point_count"));
                value.put("oldest", row.getObject("oldest", LocalDate.class).toString());
                value.put("newest", row.getObject("newest", LocalDate.class).toString());
                value.put("guaranteedYears", "FRED".equals(source) ? 10 : 5);
            });
            return MarketReadJsonMapper.mapCoverage(root);
        } catch (RuntimeException error) {
            throw unavailable("Unable to load PostgreSQL history coverage", error);
        }
    }

    @Override
    public Document loadHistory(String source, String key) {
        try {
            var points = points(source, key, null);
            var root = objectMapper.createObjectNode();
            root.put("source", source);
            root.put("key", key);
            root.put("count", points.size());
            var array = root.putArray("points");
            points.forEach(point -> {
                var node = array.addObject();
                node.put("date", point.date().toString());
                node.put("value", point.value());
            });
            return MarketReadJsonMapper.mapHistory(root, source, key);
        } catch (MarketReadUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("Unable to load PostgreSQL market history", error);
        }
    }

    @Override
    public Document loadHistorySeries(List<String> keys, String range, String interval) {
        try {
            var days = RANGE_DAYS.get(range);
            var cutoff = days == null ? null : utcDay().minusDays(days);
            var root = objectMapper.createObjectNode();
            var keyArray = root.putArray("keys");
            keys.forEach(keyArray::add);
            root.put("range", range);
            root.put("interval", interval);
            var series = root.putObject("series");
            for (var compoundKey : keys) {
                var parts = compoundKey.split(":", 2);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) continue;
                var selected = days == null ? List.<Point>of() : downsample(points(parts[0], parts[1], cutoff), interval);
                var array = series.putArray(compoundKey);
                selected.forEach(point -> {
                    var node = array.addObject();
                    node.put("date", point.date().toString());
                    node.put("value", point.value());
                });
            }
            return MarketReadJsonMapper.mapSeries(root, keys, range, interval);
        } catch (MarketReadUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("Unable to load PostgreSQL history series", error);
        }
    }

    private List<Point> points(String source, String key, LocalDate cutoff) {
        if (source == null || !source.matches("[A-Za-z_]+") || key == null || key.isBlank()) return List.of();
        var sql = new StringBuilder("""
                select observed_on, value from market.observation
                where source = :source and series_key = :seriesKey
                """);
        var parameters = new MapSqlParameterSource()
                .addValue("source", source.toUpperCase(Locale.ROOT))
                .addValue("seriesKey", key);
        if (cutoff != null) {
            sql.append(" and observed_on >= :cutoff");
            parameters.addValue("cutoff", cutoff);
        }
        sql.append(" order by observed_on");
        return jdbc.query(sql.toString(), parameters, (row, ignored) ->
                new Point(row.getObject("observed_on", LocalDate.class), row.getDouble("value")));
    }

    private static List<Point> downsample(List<Point> points, String interval) {
        if (points.isEmpty()) return List.of();
        var step = INTERVAL_STEPS.get(interval);
        var result = new ArrayList<Point>();
        for (var index = 0; index < points.size(); index++) {
            if ((step != null && index % step == 0) || index == points.size() - 1) result.add(points.get(index));
        }
        return List.copyOf(result);
    }

    private LocalDate utcDay() {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static MarketReadUnavailableException unavailable(String message, RuntimeException cause) {
        return new MarketReadUnavailableException(message, cause);
    }

    private record Point(LocalDate date, double value) {
    }
}
