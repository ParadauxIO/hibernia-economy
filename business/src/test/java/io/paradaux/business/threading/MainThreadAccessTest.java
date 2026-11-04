package io.paradaux.business.threading;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.paradaux.business.Business;
import io.paradaux.business.mappers.FirmPlayerMapper;
import io.paradaux.business.services.impl.FirmPlayerServiceImpl;
import java.util.UUID;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

class MainThreadAccessTest {
    @Test
    void workerLookupRunsOnServerThreadAndReturnsOnlineState() throws Exception {
        Business plugin = mock(Business.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        var queue = new ArrayBlockingQueue<FutureTask<?>>(1);
        when(scheduler.callSyncMethod(eq(plugin), any())).thenAnswer(call -> {
            FutureTask<Object> task = new FutureTask<>(call.getArgument(1));
            queue.add(task);
            return task;
        });
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        var service = new FirmPlayerServiceImpl(mock(FirmPlayerMapper.class), new MainThreadAccess(plugin));
        try (var bukkit = mockStatic(Bukkit.class);
                var worker = Executors.newSingleThreadExecutor()) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            Future<Boolean> result = worker.submit(() -> {
                try (var offThread = mockStatic(Bukkit.class)) {
                    offThread.when(Bukkit::getScheduler).thenReturn(scheduler);
                    offThread.when(() -> Bukkit.getPlayer(uuid)).thenThrow(new AssertionError("Off-thread lookup"));
                    return service.isOnline(uuid);
                }
            });
            var scheduled = queue.poll(2, TimeUnit.SECONDS);
            assertThat(scheduled).isNotNull();
            assertThat(result.isDone()).isFalse();
            scheduled.run();
            assertThat(result.get(2, TimeUnit.SECONDS)).isTrue();
            bukkit.verify(() -> Bukkit.getPlayer(uuid));
        }
    }

    @Test
    void rosterQueriesStayOnWorkerWhileOnlineFilteringIncludesProprietorOnServer() throws Exception {
        Business plugin = mock(Business.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        var queue = new ArrayBlockingQueue<FutureTask<?>>(1);
        when(scheduler.callSyncMethod(eq(plugin), any())).thenAnswer(call -> {
            FutureTask<Object> task = new FutureTask<>(call.getArgument(1));
            queue.add(task);
            return task;
        });
        var firms = mock(io.paradaux.business.services.FirmService.class);
        var roles = mock(io.paradaux.business.mappers.FirmRoleMapper.class);
        var staff = mock(io.paradaux.business.mappers.FirmStaffMapper.class);
        UUID owner = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        var firm = new io.paradaux.business.model.Firm();
        firm.setFirmId(7);
        firm.setProprietorUuid(owner.toString());
        when(firms.getAnyFirmById(7)).thenReturn(firm);
        Thread serverThread = Thread.currentThread();
        when(staff.listCurrentEmployeesByFirm(7)).thenAnswer(call -> {
            assertThat(Thread.currentThread()).isNotSameAs(serverThread);
            var employee = new io.paradaux.business.model.FirmEmployee();
            employee.setPlayerUuid(offline.toString());
            return new java.util.ArrayList<>(java.util.List.of(employee));
        });
        var service = new io.paradaux.business.services.impl.FirmStaffServiceImpl(
                firms, roles, staff, null, () -> null, new MainThreadAccess(plugin));
        Player ownerPlayer = mock(Player.class);
        try (var bukkit = mockStatic(Bukkit.class);
                var worker = Executors.newSingleThreadExecutor()) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(ownerPlayer);
            var result = worker.submit(() -> {
                try (var offThread = mockStatic(Bukkit.class)) {
                    offThread.when(Bukkit::getScheduler).thenReturn(scheduler);
                    offThread
                            .when(() -> Bukkit.getPlayer(any(UUID.class)))
                            .thenThrow(new AssertionError("Off-thread roster lookup"));
                    return service.getOnlineEmployees(7);
                }
            });
            var scheduled = queue.poll(2, TimeUnit.SECONDS);
            assertThat(scheduled).isNotNull();
            scheduled.run();
            assertThat(result.get(2, TimeUnit.SECONDS)).containsExactly(ownerPlayer);
            bukkit.verify(() -> Bukkit.getPlayer(owner));
            bukkit.verify(() -> Bukkit.getPlayer(offline));
        }
    }

    @Test
    void serverThreadReadsInlineWithoutScheduling() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertThat(new MainThreadAccess(null).read(() -> "value")).isEqualTo("value");
            bukkit.verify(Bukkit::getScheduler, never());
        }
    }

    @Test
    void interruptedWaitRestoresInterruptAndCancelsRead() throws Exception {
        Business plugin = mock(Business.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Future<Object> future = mock(Future.class);
        when(scheduler.callSyncMethod(eq(plugin), any())).thenReturn(future);
        when(future.get(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            assertThatThrownBy(() -> new MainThreadAccess(plugin).read(() -> "unused"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(future).cancel(false);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void stalledServerCancelsReadInsteadOfLeavingWorkerBlocked() throws Exception {
        Business plugin = mock(Business.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Future<Object> future = mock(Future.class);
        when(scheduler.callSyncMethod(eq(plugin), any())).thenReturn(future);
        when(future.get(5, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            assertThatThrownBy(() -> new MainThreadAccess(plugin).read(() -> "unused"))
                    .hasCauseInstanceOf(TimeoutException.class);
            verify(future).cancel(false);
        }
    }
}
