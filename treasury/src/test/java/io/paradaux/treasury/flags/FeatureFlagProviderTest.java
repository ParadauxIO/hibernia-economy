package io.paradaux.treasury.flags;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.paradaux.common.flags.FeatureFlagRuntime;
import io.paradaux.treasury.api.FeatureFlagApi;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class FeatureFlagProviderTest {
    @Test
    void registeredProviderNeverBlocksTheServerThreadOnAStalledDatabase() throws Exception {
        var server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin();
        DataSource dataSource = mock(DataSource.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var readerThread = new AtomicReference<Thread>();
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            readerThread.set(Thread.currentThread());
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            throw new SQLException("test database unavailable");
        });
        try (var runtime = new FeatureFlagRuntime(dataSource, message -> {})) {
            server.getServicesManager()
                    .register(FeatureFlagApi.class, runtime::isEnabled, plugin, ServicePriority.Highest);
            runtime.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), readerThread.get());
            FeatureFlagApi api = server.getServicesManager().load(FeatureFlagApi.class);
            assertNotNull(api);
            assertTimeout(java.time.Duration.ofMillis(100), () -> {
                for (int i = 0; i < 1000; i++) assertFalse(api.isEnabled("business.preview"));
            });
            verify(dataSource, times(1)).getConnection();
        } finally {
            release.countDown();
            MockBukkit.unmock();
        }
        assertFalse(FeatureFlagApi.DISABLED.isEnabled("business.preview"));
    }
}
