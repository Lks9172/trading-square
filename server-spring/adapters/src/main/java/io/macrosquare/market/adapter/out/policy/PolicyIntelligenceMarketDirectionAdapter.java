package io.macrosquare.market.adapter.out.policy;

import io.macrosquare.market.application.model.AutomaticPolicyDirection;
import io.macrosquare.market.application.port.out.ResolveAutomaticPolicyDirectionPort;
import io.macrosquare.policy.application.port.in.QueryPolicyIntelligenceUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Anti-corruption adapter: policy-context tone becomes the market-context -2..+2 convention. */
public final class PolicyIntelligenceMarketDirectionAdapter implements ResolveAutomaticPolicyDirectionPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyIntelligenceMarketDirectionAdapter.class);
    private static final int MINIMUM_CONFIDENCE = 35;
    private static final Duration MAXIMUM_AGE = Duration.ofDays(180);
    private final QueryPolicyIntelligenceUseCase query;
    private final Clock clock;

    public PolicyIntelligenceMarketDirectionAdapter(QueryPolicyIntelligenceUseCase query, Clock clock) {
        this.query = Objects.requireNonNull(query);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<AutomaticPolicyDirection> resolve() {
        try {
            var value = query.query();
            var age = Duration.between(value.asOf(), clock.instant());
            var confidence = value.calibration().enoughSamples()
                    ? Math.min(value.confidence(), value.calibration().calibratedConfidence())
                    : value.confidence();
            if (value.documentCount() == 0 || confidence < MINIMUM_CONFIDENCE
                    || age.isNegative() || age.compareTo(MAXIMUM_AGE) > 0) {
                return Optional.empty();
            }
            var direction = value.toneScore() >= 50 ? 2
                    : value.toneScore() >= 15 ? 1
                    : value.toneScore() <= -50 ? -2
                    : value.toneScore() <= -15 ? -1
                    : 0;
            return Optional.of(new AutomaticPolicyDirection(
                    direction, confidence, "Official Fed/Treasury/USTR policy NLP", value.asOf()));
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "Automatic policy direction unavailable; retaining the last valid market input (errorType={})",
                    error.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
