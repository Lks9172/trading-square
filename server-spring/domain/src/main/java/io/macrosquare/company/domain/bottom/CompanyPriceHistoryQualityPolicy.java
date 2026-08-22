package io.macrosquare.company.domain.bottom;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.time.temporal.ChronoUnit;

/**
 * Rejects price series whose unit basis is unsafe for bottom/reversal scoring.
 *
 * <p>An unadjusted split looks exactly like a catastrophic price collapse and
 * can manufacture a perfect bottom signal. The domain therefore treats a
 * canonical split-like adjacent discontinuity as unavailable evidence. It is
 * safer to temporarily withhold a signal than to publish a false entry alert.</p>
 */
public final class CompanyPriceHistoryQualityPolicy {

    private static final double[] CORPORATE_ACTION_FACTORS = {2, 3, 4, 5, 10, 20};
    private static final double FACTOR_TOLERANCE = 0.04;

    public Assessment evaluate(List<BottomPatternPoint> history) {
        Objects.requireNonNull(history, "history");
        var warnings = new ArrayList<String>();
        BottomPatternPoint previous = null;
        for (var current : history) {
            if (current == null) {
                warnings.add("price history contains a null observation");
                continue;
            }
            if (!Double.isFinite(current.close()) || current.close() <= 0) {
                warnings.add("price history contains a non-positive close near " + current.date());
            }
            if (current.volume() == null || !Double.isFinite(current.volume()) || current.volume() <= 0) {
                warnings.add("price history contains missing or non-positive volume near " + current.date());
            }
            if (current.high() != null && current.close() > current.high() * 1.000001) {
                warnings.add("price-history close exceeds the daily high near " + current.date());
            }
            if (current.low() != null && current.close() < current.low() * 0.999999) {
                warnings.add("price-history close is below the daily low near " + current.date());
            }
            if (previous != null) {
                if (!current.date().isAfter(previous.date())) {
                    warnings.add("price-history dates are duplicated or out of order near " + current.date());
                }
                var calendarGap = ChronoUnit.DAYS.between(previous.date(), current.date());
                var factor = calendarGap <= 14
                        ? splitLikeFactor(previous.close(), current.close())
                        : null;
                if (factor != null) {
                    warnings.add("unadjusted corporate-action-like price discontinuity near "
                            + current.date() + " (approximately "
                            + String.format(Locale.ROOT, "%.0f", factor) + "x)");
                }
            }
            previous = current;
        }
        return new Assessment(warnings.isEmpty(), warnings);
    }

    private static Double splitLikeFactor(double previous, double current) {
        if (!Double.isFinite(previous) || !Double.isFinite(current) || previous <= 0 || current <= 0) return null;
        var observed = Math.max(previous / current, current / previous);
        for (var factor : CORPORATE_ACTION_FACTORS) {
            if (Math.abs(observed / factor - 1) <= FACTOR_TOLERANCE) return factor;
        }
        return null;
    }

    public record Assessment(boolean eligible, List<String> warnings) {
        public Assessment {
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
            if (eligible && !warnings.isEmpty()) {
                throw new IllegalArgumentException("eligible history cannot contain quality warnings");
            }
        }
    }
}
