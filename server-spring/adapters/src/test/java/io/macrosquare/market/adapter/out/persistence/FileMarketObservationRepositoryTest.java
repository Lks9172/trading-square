package io.macrosquare.market.adapter.out.persistence;

import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FileMarketObservationRepositoryTest {

    @TempDir
    Path directory;

    @Test
    void atomicallyPersistsLatestAndReplacesSameDayHistoryWithinRetention() throws Exception {
        var repository = new FileMarketObservationRepository(
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC),
                directory.toAbsolutePath(),
                2,
                1024 * 1024
        );
        repository.save(List.of(observation("2026-07-18", 4.0)));
        repository.save(List.of(observation("2026-07-19", 4.1)));
        repository.save(List.of(observation("2026-07-20", 4.2)));
        repository.save(List.of(observation("2026-07-20", 4.3)));

        var latest = repository.loadLatest(MarketDataSource.FRED);
        var history = new ObjectMapper().readTree(
                Files.readString(directory.resolve("history/fred-dgs10.json")));

        assertEquals(1, latest.size());
        assertEquals(4.3, latest.getFirst().value());
        assertEquals(2, history.size());
        assertEquals("2026-07-19", history.get(0).get("observationDate").stringValue());
        assertEquals(4.3, history.get(1).get("value").asDouble());
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
    }

    private static MarketObservation observation(String date, double value) {
        return new MarketObservation(
                "DGS10", "DGS10", value, LocalDate.parse(date), MarketDataSource.FRED);
    }
}
