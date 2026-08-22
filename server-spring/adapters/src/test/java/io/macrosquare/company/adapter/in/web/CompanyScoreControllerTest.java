package io.macrosquare.company.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import io.macrosquare.company.application.service.ScoreCompanyService;
import io.macrosquare.company.domain.service.CompanyScoringPolicy;
import io.macrosquare.system.adapter.in.web.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanyScoreControllerTest {

    private MockMvc mvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        var useCase = new ScoreCompanyService(new CompanyScoringPolicy());
        mvc = MockMvcBuilders
                .standaloneSetup(new CompanyScoreController(useCase))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void exposesTransportDtosWithoutLeakingThemIntoTheDomain() throws Exception {
        var request = new CompanyScoreRequest(
                "test",
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

        mvc.perform(post("/internal/v1/company-score/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("TEST"))
                .andExpect(jsonPath("$.totalScore").value(87))
                .andExpect(jsonPath("$.growth.value").value(90))
                .andExpect(jsonPath("$.balanceSheet.value").value(84));
    }

}
