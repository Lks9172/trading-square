package io.macrosquare.crypto.adapter.in.web;

import io.macrosquare.crypto.application.port.in.QueryCryptoResearchUseCase;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

@RestController
@RequestMapping("/api/research/crypto")
public final class CryptoResearchController {

    private final QueryCryptoResearchUseCase queryCryptoResearch;
    private final CryptoResearchPayloadCache payloadCache;

    public CryptoResearchController(QueryCryptoResearchUseCase queryCryptoResearch, ObjectMapper objectMapper) {
        this.queryCryptoResearch = Objects.requireNonNull(queryCryptoResearch);
        this.payloadCache = new CryptoResearchPayloadCache(objectMapper);
    }

    @GetMapping
    public ResponseEntity<byte[]> catalog() {
        return json(payloadCache.catalog(queryCryptoResearch.catalog()));
    }

    @GetMapping("/{symbol}")
    public ResponseEntity<byte[]> detail(@PathVariable String symbol) {
        return json(payloadCache.detail(queryCryptoResearch.detail(symbol)));
    }

    private static ResponseEntity<byte[]> json(byte[] payload) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);
    }
}
