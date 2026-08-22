package io.macrosquare.notification.application.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketNotificationSnapshotTest {

    @Test
    void fingerprintIsStableAcrossMapInsertionOrderButChangesWithInvestmentState() {
        var left = snapshot(linked("cash", 20, "nasdaq", 80), "BUY");
        var right = snapshot(linked("nasdaq", 80, "cash", 20), "BUY");
        var changed = snapshot(Map.of("cash", 30, "nasdaq", 70), "HOLD");

        assertEquals(left.fingerprint(), right.fingerprint());
        assertNotEquals(left.fingerprint(), changed.fingerprint());
        assertEquals(64, left.fingerprint().length());
        assertTrue(left.fingerprint().matches("[0-9a-f]{64}"));
    }

    @Test
    void fingerprintDetectsAChangedActionConstraintWithoutExceedingPersistenceLimit() {
        var left = snapshotWithConstraint("⚠ 액션 상한: 유동성 흡수");
        var right = snapshotWithConstraint("⚠ 액션 상한: 가격 구조 훼손");

        assertNotEquals(left.fingerprint(), right.fingerprint());
        assertEquals(64, right.fingerprint().length());
    }

    private static MarketNotificationSnapshot snapshot(Map<String, Integer> allocation, String action) {
        return new MarketNotificationSnapshot(
                "2026-07-21T00:00:00Z", "RISK_ON", 72,
                List.of(new MarketNotificationSnapshot.Signal("NASDAQ", action, 4, 5, 100, "")),
                allocation, true, List.of()
        );
    }

    private static MarketNotificationSnapshot snapshotWithConstraint(String constraint) {
        return new MarketNotificationSnapshot(
                "2026-08-05T00:00:00Z", "BOND_VIGILANTE", 65,
                List.of(new MarketNotificationSnapshot.Signal("NASDAQ", "BUY", 7, 7, 100, constraint)),
                Map.of("cash", 25, "nasdaq", 14, "gold", 39), false, List.of()
        );
    }

    private static Map<String, Integer> linked(Object... values) {
        var result = new LinkedHashMap<String, Integer>();
        for (var index = 0; index < values.length; index += 2) {
            result.put((String) values[index], (Integer) values[index + 1]);
        }
        return result;
    }
}
