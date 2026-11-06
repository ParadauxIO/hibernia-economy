package io.paradaux.common.flags;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeatureFlagRuntimeTest {
    @Test
    void refreshRetainsOnlyFreshStateRecoversAndStopsAfterClose() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ScheduledExecutorService worker = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(worker).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.SECONDS));
        doReturn(connection).when(source).getConnection();
        when(connection.prepareStatement("SELECT name, enabled FROM feature_flags"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenAnswer(invocation -> {
            ResultSet rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            when(rows.getString("name")).thenReturn("business.preview");
            when(rows.getString("enabled")).thenReturn("1");
            return rows;
        });
        AtomicLong now = new AtomicLong();
        List<String> logs = new ArrayList<>();
        var runtime = new FeatureFlagRuntime(source, logs::add, worker, new FeatureFlagSnapshot(now::get));
        assertFalse(runtime.isEnabled("business.preview"));
        runtime.start();
        runtime.start();
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(worker).scheduleWithFixedDelay(callback.capture(), eq(0L), eq(5L), eq(TimeUnit.SECONDS));
        Runnable refresh = callback.getValue();
        refresh.run();
        assertTrue(runtime.isEnabled("business.preview"));
        when(source.getConnection()).thenThrow(new SQLException("offline"));
        refresh.run();
        assertTrue(runtime.isEnabled("business.preview"));
        refresh.run();
        assertEquals(1, logs.size());
        now.set(30_000_000_000L);
        assertFalse(runtime.isEnabled("business.preview"));
        doReturn(connection).when(source).getConnection();
        refresh.run();
        assertTrue(runtime.isEnabled("business.preview"));
        assertEquals(2, logs.size());
        refresh.run();
        assertEquals(2, logs.size());
        runtime.close();
        verify(worker).shutdownNow();
        assertFalse(runtime.isEnabled("business.preview"));
        runtime.start();
        verify(worker, times(1)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
        refresh.run(); // a read admitted before shutdown must not republish
        assertFalse(runtime.isEnabled("business.preview"));
    }

    @Test
    void closingBeforeStartDoesNotScheduleWork() {
        var worker = mock(ScheduledExecutorService.class);
        var runtime = new FeatureFlagRuntime(mock(DataSource.class), message -> {}, worker, new FeatureFlagSnapshot());
        runtime.close();
        runtime.start();
        verify(worker).shutdownNow();
        verifyNoMoreInteractions(worker);
    }

    @Test
    void defaultWorkerLoadsAsynchronouslyAndUsesDaemonThread() throws Exception {
        var source = mock(DataSource.class);
        var read = new CountDownLatch(1);
        var thread = new AtomicReference<Thread>();
        when(source.getConnection()).thenAnswer(invocation -> {
            thread.set(Thread.currentThread());
            read.countDown();
            throw new SQLException("offline");
        });
        try (var runtime = new FeatureFlagRuntime(source, message -> {})) {
            runtime.start();
            assertTrue(read.await(2, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), thread.get());
            assertTrue(thread.get().isDaemon());
            assertEquals("economy-feature-flags", thread.get().getName());
            assertFalse(runtime.isEnabled("business.preview"));
        }
    }
}
