package io.macrosquare.research.adapter.in.web;

import io.macrosquare.research.application.port.in.QueryNarrativesUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/narrative")
public final class NarrativeController {

    private final QueryNarrativesUseCase queryNarratives;

    public NarrativeController(QueryNarrativesUseCase queryNarratives) {
        this.queryNarratives = Objects.requireNonNull(queryNarratives);
    }

    @GetMapping("/themes")
    public NarrativeApiResponse.Catalog listThemes() {
        return NarrativeApiResponse.Catalog.from(queryNarratives.listDefinitions());
    }

    @GetMapping("/overview")
    public NarrativeApiResponse.Overview overview() {
        return NarrativeApiResponse.Overview.from(queryNarratives.getOverview());
    }

    @GetMapping("/themes/{id}")
    public NarrativeApiResponse.Theme theme(@PathVariable String id) {
        return NarrativeApiResponse.Theme.from(queryNarratives.getTheme(id));
    }
}
