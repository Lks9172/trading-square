package io.macrosquare.execution.adapter.in.web;

import io.macrosquare.execution.application.port.in.ManageInvestmentExecutionUseCase;
import io.macrosquare.execution.domain.service.PortfolioAllocationPolicy;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@RestController
public final class InvestmentExecutionController {

    private final ManageInvestmentExecutionUseCase execution;
    private final PortfolioAllocationPolicy allocationPolicy;

    public InvestmentExecutionController(
            ManageInvestmentExecutionUseCase execution,
            PortfolioAllocationPolicy allocationPolicy
    ) {
        this.execution = Objects.requireNonNull(execution);
        this.allocationPolicy = Objects.requireNonNull(allocationPolicy);
    }

    @GetMapping("/api/plan")
    public Map<String, Object> plan() {
        return Map.of("plan", InvestmentExecutionJsonMapper.planResponse(execution.investmentPlan(), allocationPolicy));
    }

    @PostMapping("/api/plan")
    public Map<String, Object> updatePlan(@RequestBody JsonNode request) {
        var plan = execution.updateInvestmentPlan(InvestmentExecutionJsonMapper.planPatch(request));
        return Map.of("plan", InvestmentExecutionJsonMapper.planResponse(plan, allocationPolicy));
    }

    @PostMapping("/api/execution-plan/tranche")
    public ResponseEntity<Map<String, Object>> recordTranche(@Valid @RequestBody RecordTrancheRequest request) {
        var result = execution.recordTranche(request.asset(), request.stage(), request.priceAtEntry());
        var response = new LinkedHashMap<String, Object>();
        response.put("entry", InvestmentExecutionJsonMapper.trancheResponse(result.entry()));
        response.put("total", result.total());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/execution-plan/tranche")
    public Map<String, Object> tranches() {
        var book = execution.trancheBook();
        var response = new LinkedHashMap<String, Object>();
        response.put("entries", book.entries().stream().map(InvestmentExecutionJsonMapper::trancheResponse).toList());
        response.put("summary", book.summary().stream().map(InvestmentExecutionJsonMapper::trancheSummaryResponse).toList());
        return response;
    }

    @DeleteMapping("/api/execution-plan/tranche/{asset}")
    public Map<String, Object> clearTranches(@PathVariable String asset) {
        var response = new LinkedHashMap<String, Object>();
        response.put("asset", asset);
        response.put("remainingTotal", execution.clearTranches(asset));
        return response;
    }

    @GetMapping("/api/trade-log")
    public Map<String, Object> tradeLog(@RequestParam(name = "limit", defaultValue = "200") String rawLimit) {
        int limit;
        try {
            limit = Integer.parseInt(rawLimit);
        } catch (NumberFormatException ignored) {
            limit = 200;
        }
        return Map.of("entries", execution.recentTradeLog(limit).stream()
                .map(InvestmentExecutionJsonMapper::tradeLogResponse)
                .toList());
    }

    @PostMapping("/api/trade-log")
    public Map<String, Object> appendTradeLog(@RequestBody JsonNode request) {
        var result = execution.appendTradeLog(InvestmentExecutionJsonMapper.tradeLogCommand(request));
        var response = new LinkedHashMap<String, Object>();
        response.put("ok", true);
        if (result.againstSystemRecommendation() != null) {
            response.put("againstSystemRecommendation", result.againstSystemRecommendation());
        }
        return response;
    }
}
