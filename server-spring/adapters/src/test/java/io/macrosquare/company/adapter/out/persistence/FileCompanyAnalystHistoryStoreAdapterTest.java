package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.application.port.out.CompanyAnalystHistoryPersistenceException;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileCompanyAnalystHistoryStoreAdapterTest {

    @TempDir
    Path directory;

    @Test
    void writesANodeCompatibleEnvelopeAndReadsItBack() throws Exception {
        var adapter = adapter(directory);
        var history = List.of(
                point("2026-07-18", 1.0, 20.0),
                point("2026-07-19", null, null)
        );

        adapter.save(" nvda ", history, Instant.parse("2026-07-19T15:30:00Z"));

        var path = directory.resolve("company-analyst-history-nvda.json");
        var tree = new ObjectMapper().readTree(Files.readString(path));
        assertEquals("company-analyst-history-NVDA", tree.get("key").stringValue());
        assertEquals("2026-07-19T15:30:00Z", tree.get("updatedAt").stringValue());
        assertEquals(2, tree.get("value").size());
        assertTrue(tree.get("value").get(1).get("analystScore").isNull());
        assertEquals(history, adapter.load("NVDA").orElseThrow());
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().contains(".tmp-")));
        }
    }

    @Test
    void missingStoreHistoryIsDistinctFromAnEmptyHistoryFile() {
        var adapter = adapter(directory);
        assertTrue(adapter.load("AAPL").isEmpty());

        adapter.save("AAPL", List.of(), Instant.parse("2026-07-19T15:30:00Z"));

        assertTrue(adapter.load("AAPL").isPresent());
        assertTrue(adapter.load("AAPL").orElseThrow().isEmpty());
    }

    @Test
    void sameTickerConcurrentReplacementsNeverExposeCorruptJson() throws Exception {
        var adapter = adapter(directory);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = IntStream.range(0, 40)
                    .mapToObj(index -> (java.util.concurrent.Callable<Void>) () -> {
                        adapter.save(
                                "NVDA",
                                List.of(point("2026-07-19", 1.0, (double) index)),
                                Instant.parse("2026-07-19T15:30:00Z").plusSeconds(index)
                        );
                        adapter.load("NVDA").orElseThrow();
                        return null;
                    })
                    .toList();
            for (var future : executor.invokeAll(tasks)) future.get();
        }

        var loaded = adapter.load("NVDA").orElseThrow();
        assertEquals(1, loaded.size());
        assertTrue(loaded.getFirst().upsidePct() >= 0);
        assertTrue(loaded.getFirst().upsidePct() < 40);
    }

    @Test
    void malformedExistingStoreFileFailsClosedInsteadOfBeingTreatedAsMissing() throws Exception {
        Files.writeString(directory.resolve("company-analyst-history-nvda.json"), "{not-json");
        assertThrows(CompanyAnalystHistoryPersistenceException.class, () -> adapter(directory).load("NVDA"));
    }

    @Test
    void rejectsTickerPathTraversalAndAnUnwritableDirectoryShape() throws Exception {
        var adapter = adapter(directory);
        assertThrows(IllegalArgumentException.class, () -> adapter.save(
                "../../secret",
                List.of(),
                Instant.parse("2026-07-19T15:30:00Z")
        ));

        var notDirectory = directory.resolve("file");
        Files.writeString(notDirectory, "occupied");
        assertThrows(CompanyAnalystHistoryPersistenceException.class, () -> adapter(notDirectory).save(
                "NVDA",
                List.of(),
                Instant.parse("2026-07-19T15:30:00Z")
        ));
    }

    private static FileCompanyAnalystHistoryStoreAdapter adapter(Path directory) {
        return new FileCompanyAnalystHistoryStoreAdapter(new ObjectMapper(), directory);
    }

    private static CompanyAnalystHistoryPoint point(String date, Double score, Double upside) {
        return new CompanyAnalystHistoryPoint(LocalDate.parse(date), score, upside);
    }
}
