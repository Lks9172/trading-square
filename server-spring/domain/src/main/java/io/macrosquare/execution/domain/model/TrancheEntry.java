package io.macrosquare.execution.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record TrancheEntry(
        String asset,
        int stage,
        Instant executedAt,
        Double priceAtEntry,
        String regimeAtEntry,
        Double weightPct
) {
    public static final Set<String> ALLOWED_ASSETS = Set.of(
            "NASDAQ", "KOSPI", "GOLD", "SILVER", "COPPER", "LEVERAGE", "EMERGING"
    );

    public TrancheEntry {
        asset = Objects.requireNonNull(asset, "asset").trim().toUpperCase();
        if (!ALLOWED_ASSETS.contains(asset)) {
            throw new IllegalArgumentException("invalid asset; must be one of NASDAQ,KOSPI,GOLD,SILVER,COPPER,LEVERAGE,EMERGING");
        }
        if (stage < 1 || stage > 5) throw new IllegalArgumentException("invalid stage; integer 1..5 required");
        executedAt = Objects.requireNonNull(executedAt, "executedAt");
        if (priceAtEntry != null && (!Double.isFinite(priceAtEntry) || priceAtEntry <= 0)) {
            throw new IllegalArgumentException("invalid priceAtEntry; finite positive number required");
        }
        if (weightPct != null && (!Double.isFinite(weightPct) || weightPct < 0 || weightPct > 100)) {
            throw new IllegalArgumentException("weightPct must be between 0 and 100");
        }
    }
}
