package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.application.port.out.CompanyAnalystEvidenceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacySourceCacheCompanyAnalystConsensusAdapterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-19T14:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void mapsTheFreshGlobalConsensusWithoutWriting() throws IOException {
        writeConsensus("2026-07-19T12:48:36.106Z");
        var path = directory.resolve(consensusFile());
        var before = Files.getLastModifiedTime(path);

        var consensus = adapter().load(" nvda ");

        assertEquals(1.098, consensus.analystScore());
        assertEquals(49.06, consensus.upsidePct());
        assertEquals(before, Files.getLastModifiedTime(path));
    }

    @Test
    void treatsConsensusOlderThanTheLegacySevenDayFallbackAsUnavailable() throws IOException {
        writeConsensus("2026-07-10T12:00:00Z");

        var consensus = adapter().load("NVDA");

        assertNull(consensus.analystScore());
        assertNull(consensus.upsidePct());
    }

    @Test
    void treatsFutureDatedConsensusAsUnavailable() throws IOException {
        writeConsensus("2099-01-01T00:00:00Z");

        var consensus = adapter().load("NVDA");

        assertNull(consensus.analystScore());
        assertNull(consensus.upsidePct());
    }

    @Test
    void failsClosedForMissingOrMalformedConsensus() throws IOException {
        assertThrows(CompanyAnalystEvidenceUnavailableException.class, () -> adapter().load("NVDA"));
        Files.writeString(directory.resolve(consensusFile()), "{not-json");
        assertThrows(CompanyAnalystEvidenceUnavailableException.class, () -> adapter().load("NVDA"));
    }

    @Test
    void preventsTickerBasedPathTraversal() throws IOException {
        writeConsensus("2026-07-19T12:48:36.106Z");
        assertThrows(IllegalArgumentException.class, () -> adapter().load("../../secret"));
    }

    private LegacySourceCacheCompanyAnalystConsensusAdapter adapter() {
        return new LegacySourceCacheCompanyAnalystConsensusAdapter(
                new ObjectMapper(), directory, CLOCK, Duration.ofDays(7)
        );
    }

    private void writeConsensus(String updatedAt) throws IOException {
        Files.writeString(directory.resolve(consensusFile()), """
                {
                  "key":"analyst-consensus-nasdaq-megacap",
                  "updatedAt":"%s",
                  "value":{
                    "perTicker":{"AAPL":0.66,"NVDA":1.098},
                    "perTickerUpsidePct":{"AAPL":-4.64,"NVDA":49.06}
                  }
                }
                """.formatted(updatedAt));
    }

    private static String consensusFile() {
        return "analyst-consensus-nasdaq-megacap.json";
    }
}
