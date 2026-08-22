package io.macrosquare.shared.adapter.in.scheduling;

/** Allows Spring/Micrometer to observe scheduled work that completed unsuccessfully. */
public final class ScheduledTaskExecutionException extends RuntimeException {

    public ScheduledTaskExecutionException(String task, Throwable cause) {
        super("Scheduled task failed: " + task, cause);
    }

    public ScheduledTaskExecutionException(String task, String detail) {
        super("Scheduled task failed: " + task + " (" + detail + ')');
    }
}
