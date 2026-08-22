package io.macrosquare.research.domain.peer;

/** Deterministic SIC-to-standard-sector fallback; exact SIC remains the primary peer key. */
public final class SicSectorPolicy {

    public String classify(int sic) {
        if (sic == 6798 || between(sic, 6500, 6553)) return "real-estate";
        if (between(sic, 6000, 6799)) return "financials";
        if (between(sic, 4900, 4999)) return "utilities";
        if (sic == 1311 || sic == 1381 || sic == 1382 || sic == 1389 || sic == 2911) return "energy";
        if (between(sic, 2830, 2836) || between(sic, 3840, 3851) || between(sic, 8000, 8099)) {
            return "healthcare";
        }
        if (between(sic, 4810, 4899) || between(sic, 7800, 7841)) return "communication-services";
        if (between(sic, 3570, 3579) || between(sic, 3660, 3699) || between(sic, 7370, 7379)) {
            return "technology";
        }
        if (between(sic, 2000, 2199) || between(sic, 5400, 5499)) return "consumer-staples";
        if (between(sic, 1000, 1299) || between(sic, 1400, 1499)
                || between(sic, 2800, 2829) || between(sic, 3200, 3399)) return "materials";
        if (between(sic, 1500, 1799) || between(sic, 3400, 3569)
                || between(sic, 3700, 3799) || between(sic, 4000, 4799)) return "industrials";
        return "consumer-discretionary";
    }

    private static boolean between(int value, int minimum, int maximum) {
        return value >= minimum && value <= maximum;
    }
}
