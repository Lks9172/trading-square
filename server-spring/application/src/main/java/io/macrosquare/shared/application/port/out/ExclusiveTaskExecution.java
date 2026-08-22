package io.macrosquare.shared.application.port.out;

import java.util.Objects;

/**
 * Executes a side-effecting scheduled task only when this process owns the
 * task's cluster-wide execution slot.
 */
@FunctionalInterface
public interface ExclusiveTaskExecution {

    /**
     * @return {@code true} when the task ran, {@code false} when another
     * instance already owned the same slot
     */
    boolean execute(String taskName, Runnable task);

    static ExclusiveTaskExecution local() {
        return (taskName, task) -> {
            if (taskName == null || taskName.isBlank()) {
                throw new IllegalArgumentException("taskName must not be blank");
            }
            Objects.requireNonNull(task, "task").run();
            return true;
        };
    }
}
