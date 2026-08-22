package io.macrosquare.research.adapter.in.web;

import io.macrosquare.research.application.port.in.EvaluateResearchParityUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/internal/v1/migration")
public final class ResearchParityController {

    private final EvaluateResearchParityUseCase parityUseCase;

    public ResearchParityController(EvaluateResearchParityUseCase parityUseCase) {
        this.parityUseCase = Objects.requireNonNull(parityUseCase);
    }

    @GetMapping("/research-parity")
    public ResearchParityResponse evaluate() {
        return ResearchParityResponse.from(parityUseCase.evaluate());
    }
}
