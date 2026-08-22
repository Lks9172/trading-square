package io.macrosquare.notification.application.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public record MarketNotificationSnapshot(
        String timestamp,
        String regime,
        int regimeScore,
        List<Signal> signals,
        Map<String, Integer> allocations,
        boolean leverageAllowed,
        List<String> breadthLines
) {
    public MarketNotificationSnapshot {
        timestamp = timestamp == null ? "" : timestamp;
        regime = regime == null ? "UNKNOWN" : regime;
        if (regimeScore < 0 || regimeScore > 100) regimeScore = 0;
        signals = List.copyOf(signals == null ? List.of() : signals);
        allocations = Map.copyOf(allocations == null ? Map.of() : allocations);
        breadthLines = List.copyOf(breadthLines == null ? List.of() : breadthLines);
    }

    public String fingerprint() {
        var stableAllocations = allocations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + '=' + entry.getValue())
                .toList();
        var canonical = regime + ':' + regimeScore + ':' + signals.stream()
                .map(value -> value.asset() + '=' + value.action() + '@' + value.dataCoveragePct()
                        + ':' + value.constraint()).sorted().toList() + ':' + stableAllocations;
        return sha256(canonical);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    public record Signal(
            String asset,
            String action,
            int conditionsMet,
            int conditionsTotal,
            int dataCoveragePct,
            String constraint
    ) {
        public Signal {
            if (asset == null) asset = "";
            if (action == null) action = "";
            if (dataCoveragePct < 0 || dataCoveragePct > 100) dataCoveragePct = 0;
            constraint = constraint == null ? "" : constraint;
        }
    }
}
