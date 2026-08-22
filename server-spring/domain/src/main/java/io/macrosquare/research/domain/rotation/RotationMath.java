package io.macrosquare.research.domain.rotation;

final class RotationMath {

    private RotationMath() {
    }

    static double positiveScore(Double value, double goodMin, double goodMax) {
        if (value == null || Double.isNaN(value)) return 50;
        if (value <= goodMin) return 0;
        if (value >= goodMax) return 100;
        return ((value - goodMin) / (goodMax - goodMin)) * 100;
    }

    static double negativeScore(Double value, double badMin, double badMax) {
        if (value == null || Double.isNaN(value)) return 50;
        if (value <= badMin) return 100;
        if (value >= badMax) return 0;
        return 100 - (((value - badMin) / (badMax - badMin)) * 100);
    }

    static double valueOr(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    static int rounded(double value) {
        return (int) Math.round(value);
    }

    static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }
}
