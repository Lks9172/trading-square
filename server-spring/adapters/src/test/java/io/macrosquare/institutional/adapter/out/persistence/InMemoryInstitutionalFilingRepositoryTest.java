package io.macrosquare.institutional.adapter.out.persistence;

import io.macrosquare.institutional.domain.model.InstitutionalFiling;
import io.macrosquare.institutional.domain.model.InstitutionalManager;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryInstitutionalFilingRepositoryTest {

    @Test
    void reportsFreshnessOnlyWhenEveryRequestedManagerHasEvidence() {
        var repository = new InMemoryInstitutionalFilingRepository();
        var manager = new InstitutionalManager("first", "First Manager", "0000000001");
        repository.save(List.of(new InstitutionalFiling(
                manager,
                "0000000001-26-000001",
                LocalDate.parse("2026-05-15"),
                LocalDate.parse("2026-03-31"),
                "https://www.sec.gov/Archives/example.xml",
                "sec-filings/13f/example.xml",
                List.of())));

        assertTrue(repository.latestCollectedAt(List.of(manager.cik())).isPresent());
        assertTrue(repository.latestCollectedAt(List.of(manager.cik(), "0000000002")).isEmpty());
    }
}
