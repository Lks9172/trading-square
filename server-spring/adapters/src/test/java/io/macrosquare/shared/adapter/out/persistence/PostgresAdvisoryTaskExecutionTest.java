package io.macrosquare.shared.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresAdvisoryTaskExecutionTest {

    @Test
    void holdsAndReleasesTheSameSessionLockAroundTheTask() throws Exception {
        var fixture = fixture(true, true);
        var calls = new AtomicInteger();

        var executed = fixture.execution.execute("market:snapshot-refresh", calls::incrementAndGet);

        assertTrue(executed);
        assertEquals(1, calls.get());
        var acquiredKey = ArgumentCaptor.forClass(Long.class);
        var releasedKey = ArgumentCaptor.forClass(Long.class);
        verify(fixture.acquire).setLong(org.mockito.ArgumentMatchers.eq(1), acquiredKey.capture());
        verify(fixture.release).setLong(org.mockito.ArgumentMatchers.eq(1), releasedKey.capture());
        assertEquals(acquiredKey.getValue(), releasedKey.getValue());
        verify(fixture.connection).close();
    }

    @Test
    void doesNotRunOrUnlockWhenAnotherSessionOwnsTheTask() throws Exception {
        var fixture = fixture(false, true);
        var calls = new AtomicInteger();

        assertFalse(fixture.execution.execute("notification:candidate-scan", calls::incrementAndGet));

        assertEquals(0, calls.get());
        verify(fixture.connection, never()).prepareStatement("select pg_advisory_unlock(?)");
    }

    @Test
    void releasesTheLockAndPreservesTheTaskFailure() throws Exception {
        var fixture = fixture(true, true);
        var failure = new IllegalArgumentException("boom");

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> fixture.execution.execute("company:analyst-history", () -> { throw failure; }));

        assertSame(failure, thrown);
        verify(fixture.release).executeQuery();
    }

    @Test
    void failsClosedWhenPostgresCannotCoordinateTheTask() throws Exception {
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("offline"));
        var execution = new PostgresAdvisoryTaskExecution(dataSource);

        var thrown = assertThrows(IllegalStateException.class,
                () -> execution.execute("market:observation:yahoo", () -> { }));

        assertTrue(thrown.getMessage().contains("market:observation:yahoo"));
    }

    @Test
    void rejectsUnstableOrUnboundedTaskNamesBeforeOpeningAConnection() throws Exception {
        var dataSource = mock(DataSource.class);
        var execution = new PostgresAdvisoryTaskExecution(dataSource);

        assertThrows(IllegalArgumentException.class, () -> execution.execute("Market Snapshot", () -> { }));
        assertThrows(IllegalArgumentException.class, () -> execution.execute("x".repeat(129), () -> { }));
        verify(dataSource, never()).getConnection();
    }

    @Test
    void boundsDedicatedLockConnectionsWithoutDroppingQueuedWork() throws Exception {
        var dataSource = acceptingDataSource();
        var execution = new PostgresAdvisoryTaskExecution(dataSource, 1);
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);

        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            try (var workers = Executors.newFixedThreadPool(2)) {
                var first = workers.submit(() -> execution.execute("integration:first", () -> {
                    firstEntered.countDown();
                    await(releaseFirst);
                }));
                if (!firstEntered.await(3, TimeUnit.SECONDS)) {
                    first.get(1, TimeUnit.SECONDS);
                    throw new AssertionError("first task did not enter");
                }

                var second = workers.submit(() -> execution.execute(
                        "integration:second", secondEntered::countDown));
                assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS));
                verify(dataSource, times(1)).getConnection();

                releaseFirst.countDown();
                assertTrue(first.get(1, TimeUnit.SECONDS));
                assertTrue(second.get(1, TimeUnit.SECONDS));
                assertEquals(0, secondEntered.getCount());
                verify(dataSource, times(2)).getConnection();
            }
        });
    }

    @Test
    void rejectsUnsafeConnectionConcurrencyBounds() {
        var dataSource = mock(DataSource.class);

        assertThrows(IllegalArgumentException.class, () -> new PostgresAdvisoryTaskExecution(dataSource, 0));
        assertThrows(IllegalArgumentException.class, () -> new PostgresAdvisoryTaskExecution(dataSource, 33));
    }

    private static Fixture fixture(boolean acquired, boolean released) throws Exception {
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        var acquire = mock(PreparedStatement.class);
        var release = mock(PreparedStatement.class);
        var acquireResult = result(acquired);
        var releaseResult = result(released);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("select pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(connection.prepareStatement("select pg_advisory_unlock(?)")).thenReturn(release);
        when(acquire.executeQuery()).thenReturn(acquireResult);
        when(release.executeQuery()).thenReturn(releaseResult);
        return new Fixture(new PostgresAdvisoryTaskExecution(dataSource), connection, acquire, release);
    }

    private static ResultSet result(boolean value) throws Exception {
        var result = mock(ResultSet.class);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(value);
        return result;
    }

    private static DataSource acceptingDataSource() throws Exception {
        var dataSource = mock(DataSource.class);
        var first = acceptingConnection();
        var second = acceptingConnection();
        when(dataSource.getConnection()).thenReturn(first, second);
        return dataSource;
    }

    private static Connection acceptingConnection() throws Exception {
        var connection = mock(Connection.class);
        var acquire = mock(PreparedStatement.class);
        var release = mock(PreparedStatement.class);
        var acquireResult = result(true);
        var releaseResult = result(true);
        when(connection.prepareStatement("select pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(connection.prepareStatement("select pg_advisory_unlock(?)")).thenReturn(release);
        when(acquire.executeQuery()).thenReturn(acquireResult);
        when(release.executeQuery()).thenReturn(releaseResult);
        return connection;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private record Fixture(
            PostgresAdvisoryTaskExecution execution,
            Connection connection,
            PreparedStatement acquire,
            PreparedStatement release
    ) {
    }
}
