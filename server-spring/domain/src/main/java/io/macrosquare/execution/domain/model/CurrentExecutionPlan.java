package io.macrosquare.execution.domain.model;

import java.util.List;

public record CurrentExecutionPlan(
        String asset,
        Action action,
        String actionLabel,
        Double currentPrice,
        int targetAllocationPct,
        List<Stage> stages,
        ExitRule stopLoss,
        ExitRule takeProfit,
        int validityDays,
        String primaryReason,
        Timing timing
) {
    public CurrentExecutionPlan {
        if (asset == null || asset.isBlank() || action == null || actionLabel == null || actionLabel.isBlank()) {
            throw new IllegalArgumentException("execution plan identity is required");
        }
        if (targetAllocationPct < 0 || targetAllocationPct > 100 || validityDays < 1) {
            throw new IllegalArgumentException("execution plan range is invalid");
        }
        stages = List.copyOf(stages == null ? List.of() : stages);
        if (stopLoss == null || takeProfit == null || timing == null) {
            throw new IllegalArgumentException("execution safeguards are required");
        }
    }

    public enum Action {
        BUY_NOW, SCALE_IN, HOLD, TAKE_PROFIT, EXIT, AVOID
    }

    public enum StageStatus {
        PENDING, READY
    }

    public record Stage(
            int stage,
            int weightPct,
            String triggerCondition,
            Double triggerPrice,
            StageStatus status
    ) {
        public Stage {
            if (stage < 1 || stage > 3 || weightPct < 0 || weightPct > 100
                    || triggerCondition == null || triggerCondition.isBlank() || status == null) {
                throw new IllegalArgumentException("execution stage is invalid");
            }
        }
    }

    public record ExitRule(Double price, String condition) {
        public ExitRule {
            if (condition == null || condition.isBlank()) throw new IllegalArgumentException("condition is required");
        }
    }

    public record Timing(
            boolean macroAligned,
            boolean sectorAligned,
            boolean flowConfirmed,
            boolean chartConfirmed,
            boolean overheatingRisk,
            List<String> notes
    ) {
        public Timing {
            notes = List.copyOf(notes == null ? List.of() : notes);
        }
    }
}
