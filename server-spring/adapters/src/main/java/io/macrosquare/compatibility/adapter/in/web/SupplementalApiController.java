package io.macrosquare.compatibility.adapter.in.web;

import io.macrosquare.compatibility.application.model.SupplementalApiModels.Document;
import io.macrosquare.compatibility.application.port.in.QuerySupplementalApiUseCase;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

@RestController
public final class SupplementalApiController {

    private final QuerySupplementalApiUseCase query;
    private final SupplementalApiPayloadCache payloadCache;

    public SupplementalApiController(QuerySupplementalApiUseCase query, ObjectMapper objectMapper) {
        this.query = Objects.requireNonNull(query);
        this.payloadCache = new SupplementalApiPayloadCache(objectMapper);
    }

    @GetMapping("/api/smart-money")
    public ResponseEntity<byte[]> smartMoney() {
        return json("smart-money", query.smartMoney());
    }

    @GetMapping("/api/research/sectors/backtest")
    public ResponseEntity<byte[]> sectorBacktest(@RequestParam(name = "years", required = false) String years) {
        return json("sector-backtest:" + Objects.toString(years, ""), query.sectorBacktest(years));
    }

    @GetMapping("/api/bottleneck/themes")
    public ResponseEntity<byte[]> bottleneckThemes() {
        return json("bottleneck-themes", query.bottleneckThemes());
    }

    @GetMapping("/api/bottleneck/themes/{id}")
    public ResponseEntity<byte[]> bottleneckTheme(@PathVariable String id) {
        return json("bottleneck-theme:" + id, query.bottleneckTheme(id));
    }

    @GetMapping("/api/research/companies")
    public ResponseEntity<byte[]> companies(
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(name = "themeId", required = false) String themeId,
            @RequestParam(name = "sectorId", required = false) String sectorId,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "pageSize", required = false) String pageSize
    ) {
        var key = String.join("\u0000", "companies", Objects.toString(sort, ""), Objects.toString(search, ""),
                Objects.toString(themeId, ""), Objects.toString(sectorId, ""), Objects.toString(page, ""),
                Objects.toString(pageSize, ""));
        return json(key, query.companies(sort, search, themeId, sectorId, page, pageSize));
    }

    @GetMapping("/api/research/highlights")
    public ResponseEntity<byte[]> highlights() {
        return json("highlights", query.highlights());
    }

    @GetMapping("/api/earnings")
    public ResponseEntity<byte[]> earnings() {
        return json("earnings", query.earnings());
    }

    @GetMapping("/api/domestic-reports")
    public ResponseEntity<byte[]> domesticReports() {
        return json("domestic-reports", query.domesticReports());
    }

    @GetMapping("/api/backtest/summary")
    public ResponseEntity<byte[]> backtestSummary() {
        return json("backtest-summary", query.backtestSummary());
    }

    @GetMapping("/api/backtest/portfolio")
    public ResponseEntity<byte[]> backtestPortfolio(@RequestParam(name = "years", required = false) String years) {
        return json("backtest-portfolio:" + Objects.toString(years, ""), query.backtestPortfolio(years));
    }

    @GetMapping("/api/backtest/user-plan")
    public ResponseEntity<byte[]> backtestUserPlan(@RequestParam(name = "years", required = false) String years) {
        return json("backtest-user-plan:" + Objects.toString(years, ""), query.backtestUserPlan(years));
    }

    private ResponseEntity<byte[]> json(String key, Document document) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(payloadCache.payload(key, document));
    }
}
