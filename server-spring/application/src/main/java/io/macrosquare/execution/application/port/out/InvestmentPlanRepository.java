package io.macrosquare.execution.application.port.out;

import io.macrosquare.execution.domain.model.InvestmentPlan;

import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

public interface InvestmentPlanRepository {
    Optional<InvestmentPlan> load();

    InvestmentPlan save(InvestmentPlan plan);

    /**
     * Applies a side-effect-free mutation while holding the persistence boundary's
     * aggregate lock. Implementations must serialize concurrent calls across every
     * writer that shares the same backing store.
     */
    PlanMutation updateAtomically(InvestmentPlan initialPlan, UnaryOperator<InvestmentPlan> mutation);

    record PlanMutation(InvestmentPlan before, InvestmentPlan after) {
        public PlanMutation {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
        }
    }
}
