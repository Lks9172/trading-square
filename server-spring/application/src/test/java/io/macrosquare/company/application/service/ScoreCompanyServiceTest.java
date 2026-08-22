package io.macrosquare.company.application.service;

import io.macrosquare.company.application.port.in.ScoreCompanyCommand;
import io.macrosquare.company.domain.service.CompanyScoringPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoreCompanyServiceTest {

    @Test
    void mapsTheUseCaseCommandWithoutFrameworkTypes() {
        var service = new ScoreCompanyService(new CompanyScoringPolicy());
        var command = new ScoreCompanyCommand(
                "nvda",
                25.0,
                25.0,
                20.0,
                20.0,
                3.0,
                3.0,
                20.0,
                0.0,
                150.0,
                90.0,
                0.0,
                3.0
        );

        var result = service.score(command);

        assertEquals("NVDA", result.ticker().value());
        assertEquals(87, result.totalScore());
    }
}
