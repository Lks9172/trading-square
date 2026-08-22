package io.macrosquare.market.adapter.in.web;

import io.macrosquare.market.application.port.in.QueryMarketReadUseCase;
import io.macrosquare.market.application.port.in.PersonalizeMarketSnapshotUseCase;
import io.macrosquare.market.application.port.in.RefreshMarketSnapshotUseCase;
import io.macrosquare.market.application.port.in.QueryMarketCorrelationUseCase;
import io.macrosquare.market.adapter.out.json.MarketReadJsonMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

@RestController
public final class MarketReadController {

    private final QueryMarketReadUseCase queryMarketRead;
    private final PersonalizeMarketSnapshotUseCase personalizeMarketSnapshot;
    private final RefreshMarketSnapshotUseCase refreshMarketSnapshot;
    private final QueryMarketCorrelationUseCase queryMarketCorrelation;
    private final MarketReadPayloadCache payloadCache;

    public MarketReadController(
            QueryMarketReadUseCase queryMarketRead,
            PersonalizeMarketSnapshotUseCase personalizeMarketSnapshot,
            RefreshMarketSnapshotUseCase refreshMarketSnapshot,
            QueryMarketCorrelationUseCase queryMarketCorrelation,
            ObjectMapper objectMapper
    ) {
        this.queryMarketRead = Objects.requireNonNull(queryMarketRead);
        this.personalizeMarketSnapshot = Objects.requireNonNull(personalizeMarketSnapshot);
        this.refreshMarketSnapshot = Objects.requireNonNull(refreshMarketSnapshot);
        this.queryMarketCorrelation = Objects.requireNonNull(queryMarketCorrelation);
        this.payloadCache = new MarketReadPayloadCache(objectMapper);
    }

    @GetMapping("/api/snapshot")
    public ResponseEntity<byte[]> latestSnapshot() {
        return json(payloadCache.payload("snapshot", queryMarketRead.latestSnapshot()));
    }

    @PostMapping("/api/snapshot")
    public ResponseEntity<byte[]> personalizedSnapshot(@RequestBody JsonNode profileOverrides) {
        var request = MarketReadJsonMapper.mapObject(profileOverrides);
        var result = personalizeMarketSnapshot.personalize(request);
        return json(payloadCache.payload("personalized\u0000" + request.hashCode(), result));
    }

    @PostMapping("/api/refresh")
    public ResponseEntity<byte[]> refreshSnapshot() {
        var result = refreshMarketSnapshot.refresh().snapshot();
        return json(payloadCache.payload("refresh", result));
    }

    @GetMapping("/api/correlation")
    public MarketCorrelationResponse correlation(
            @RequestParam(name = "lookback", required = false) String lookback,
            @RequestParam(name = "keys", required = false) List<String> keys
    ) {
        var requested = new java.util.ArrayList<String>();
        if (keys != null) keys.forEach(parameter -> {
            if (parameter != null) java.util.Arrays.stream(parameter.split(",", -1))
                    .map(String::trim).filter(value -> !value.isEmpty()).forEach(requested::add);
        });
        return MarketCorrelationResponse.from(queryMarketCorrelation.query(parseLookback(lookback), requested));
    }

    @GetMapping("/api/history/coverage")
    public ResponseEntity<byte[]> historyCoverage() {
        return json(payloadCache.payload("history-coverage", queryMarketRead.historyCoverage()));
    }

    @GetMapping("/api/history/{source}/{key}")
    public ResponseEntity<byte[]> history(@PathVariable String source, @PathVariable String key) {
        return json(payloadCache.payload(
                "history\u0000" + source + "\u0000" + key,
                queryMarketRead.history(source, key)
        ));
    }

    @GetMapping("/api/history-series")
    public ResponseEntity<byte[]> historySeries(
            @RequestParam(name = "keys", required = false) List<String> keys,
            @RequestParam(name = "range", required = false) String range,
            @RequestParam(name = "interval", required = false) String interval
    ) {
        var keyParameters = keys == null ? List.<String>of() : List.copyOf(keys);
        return json(payloadCache.payload(
                "history-series\u0000" + String.join("\u0001", keyParameters)
                        + "\u0000" + Objects.toString(range, "")
                        + "\u0000" + Objects.toString(interval, ""),
                queryMarketRead.historySeries(keyParameters, range, interval)
        ));
    }

    private static ResponseEntity<byte[]> json(byte[] payload) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);
    }

    private static int parseLookback(String value) {
        try {
            return value == null || value.isBlank() ? 60 : Math.max(10, Math.min(500, Integer.parseInt(value.trim())));
        } catch (NumberFormatException ignored) {
            return 60;
        }
    }
}
