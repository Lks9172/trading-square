package io.macrosquare.shared.adapter.out.persistence;

import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * PostgreSQL session advisory lock used to prevent duplicate scheduled side
 * effects when two application instances overlap during rollout or failover.
 * The lock connection remains open for exactly the duration of the task. The
 * supplied data source should therefore be dedicated to coordination rather
 * than the transactional application pool. A local fair semaphore bounds the
 * number of physical lock sessions while preserving eventual execution.
 */
public final class PostgresAdvisoryTaskExecution implements ExclusiveTaskExecution {

    private static final String ACQUIRE_SQL = "select pg_try_advisory_lock(?)";
    private static final String RELEASE_SQL = "select pg_advisory_unlock(?)";
    private static final Pattern TASK_NAME = Pattern.compile("[a-z0-9][a-z0-9:._-]{0,127}");
    private static final byte[] NAMESPACE = "macrosquare:scheduled-task:".getBytes(StandardCharsets.UTF_8);

    private final DataSource dataSource;
    private final Semaphore connectionPermits;

    public PostgresAdvisoryTaskExecution(DataSource dataSource) {
        this(dataSource, 4);
    }

    public PostgresAdvisoryTaskExecution(DataSource dataSource, int maximumConcurrentLocks) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (maximumConcurrentLocks < 1 || maximumConcurrentLocks > 32) {
            throw new IllegalArgumentException("maximumConcurrentLocks must be between 1 and 32");
        }
        this.connectionPermits = new Semaphore(maximumConcurrentLocks, true);
    }

    @Override
    public boolean execute(String taskName, Runnable task) {
        var normalized = normalize(taskName);
        var action = Objects.requireNonNull(task, "task");
        var lockKey = lockKey(normalized);
        acquireConnectionPermit(normalized);
        try {
            try (var connection = dataSource.getConnection()) {
                if (!queryBoolean(connection, ACQUIRE_SQL, lockKey)) return false;
                Throwable taskFailure = null;
                try {
                    action.run();
                    return true;
                } catch (RuntimeException | Error error) {
                    taskFailure = error;
                    throw error;
                } finally {
                    release(connection, lockKey, normalized, taskFailure);
                }
            } catch (SQLException error) {
                throw new IllegalStateException("Unable to coordinate scheduled task " + normalized, error);
            }
        } finally {
            connectionPermits.release();
        }
    }

    private void acquireConnectionPermit(String taskName) {
        try {
            connectionPermits.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to coordinate scheduled task " + taskName,
                    interrupted);
        }
    }

    private static void release(Connection connection, long lockKey, String taskName, Throwable taskFailure) {
        try {
            if (!queryBoolean(connection, RELEASE_SQL, lockKey)) {
                throw new SQLException("PostgreSQL advisory lock was not owned by this session");
            }
        } catch (SQLException releaseFailure) {
            var wrapped = new IllegalStateException("Unable to release scheduled task lock " + taskName,
                    releaseFailure);
            if (taskFailure != null) {
                taskFailure.addSuppressed(wrapped);
                return;
            }
            throw wrapped;
        }
    }

    private static boolean queryBoolean(Connection connection, String sql, long lockKey) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lockKey);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("PostgreSQL advisory lock query returned no row");
                return result.getBoolean(1);
            }
        }
    }

    private static String normalize(String taskName) {
        if (taskName == null || !TASK_NAME.matcher(taskName).matches()) {
            throw new IllegalArgumentException("invalid scheduled task name");
        }
        return taskName;
    }

    private static long lockKey(String taskName) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(NAMESPACE);
            digest.update(taskName.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
