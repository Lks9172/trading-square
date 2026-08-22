package io.macrosquare.execution.adapter.in.web;

import io.macrosquare.execution.application.model.WeeklyReviewReport;
import io.macrosquare.execution.application.port.in.QueryWeeklyReviewUseCase;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@RestController
public final class WeeklyReviewController {

    private static final MediaType UTF8_TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private final QueryWeeklyReviewUseCase weeklyReview;

    public WeeklyReviewController(QueryWeeklyReviewUseCase weeklyReview) {
        this.weeklyReview = Objects.requireNonNull(weeklyReview);
    }

    @GetMapping("/api/weekly-report")
    public ResponseEntity<?> report(@RequestParam(name = "format", required = false) String format) {
        var report = weeklyReview.review();
        if ("text".equals(format)) {
            return ResponseEntity.ok().contentType(UTF8_TEXT).body(report.text());
        }
        var response = new LinkedHashMap<String, Object>();
        response.put("report", reportResponse(report));
        response.put("text", report.text());
        return ResponseEntity.ok(response);
    }

    private static Map<String, Object> reportResponse(WeeklyReviewReport report) {
        var value = new LinkedHashMap<String, Object>();
        value.put("generatedAt", report.generatedAt().toString());
        value.put("period", Map.of("from", report.periodFrom().toString(), "to", report.periodTo().toString()));
        value.put("regime", Map.of("current", report.regime(), "score", report.regimeScore()));
        value.put("keySignals", report.keySignals().stream().map(signal -> Map.of(
                "asset", signal.asset(), "signal", signal.signal(), "met", signal.met(),
                "dataCoveragePct", signal.dataCoveragePct())).toList());
        value.put("topReasons", report.topReasons());
        value.put("warnings", report.warnings());
        value.put("nextEvents", report.nextEvents().stream().map(event -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("event", event.event());
            item.put("date", event.date().toString());
            item.put("dday", event.dday());
            item.put("importance", event.importance());
            return item;
        }).toList());
        value.put("ruleViolations", report.ruleViolations());
        value.put("portfolio", portfolioResponse(report));
        return value;
    }

    private static Map<String, Object> portfolioResponse(WeeklyReviewReport report) {
        var value = new LinkedHashMap<String, Object>();
        value.put("sourceUnit", report.holdings().sourceUnit().name());
        value.put("normalized", report.holdings().normalized());
        value.put("percentages", report.holdings().percentages());
        value.put("sourceValues", report.holdings().sourceValues());
        value.put("denominator", report.holdings().denominator());
        value.put("allocatedPct", report.holdings().allocatedPct());
        value.put("unallocatedPct", report.holdings().unallocatedPct());
        value.put("overAllocatedPct", report.holdings().overAllocatedPct());
        value.put("totalDriftPct", report.drift().totalDriftPct());
        return value;
    }
}
