package io.paradaux.treasuryapi.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.util.Modules;
import io.paradaux.treasuryapi.guice.DatabaseModule;
import io.paradaux.treasuryapi.model.GroupSource;
import io.paradaux.treasuryapi.model.config.DatabaseConfiguration;
import io.paradaux.treasuryapi.testsupport.IntegrationTestBase;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Real transactions and independent injectors: a JVM monitor cannot protect two servers. */
class DerivedGroupPublicationIT extends IntegrationTestBase {
    @Test
    void staleRetargetAndCleanupCandidatesCannotChangeCurrentMembership() throws Exception {
        UUID owner = UUID.randomUUID();
        seed(owner);
        var business = mock(BusinessGroupMembers.class);
        var service = service(business);
        execute("UPDATE explorer_group_sync SET selector = '8' WHERE group_id = 1");
        service.reconcileBinding(new GroupSource(1, "business", "7"));
        service.removeUnconfigured(new GroupSource(1, "business", ""));
        verifyNoInteractions(business);
        assertThat(members(1)).containsExactly(owner);
        execute("DELETE FROM explorer_group_sync WHERE group_id = 1");
        service.reconcile();
        assertThat(members(1)).isEmpty();
    }

    @Test
    void failedRemovalRollsBackNewGrantsButAnotherBindingStillPublishes() throws Exception {
        UUID oldOwner = UUID.randomUUID(), newOwner = UUID.randomUUID();
        seed(oldOwner);
        execute("INSERT INTO explorer_group (group_id, name) VALUES (2, 'Healthy')");
        execute("INSERT INTO explorer_group_sync VALUES (2, 'business', '8')");
        var business = mock(BusinessGroupMembers.class);
        when(business.resolve(anyString())).thenReturn(Set.of(newOwner));
        var service = service(business);
        execute(
                "CREATE TRIGGER reject_group_removal BEFORE DELETE ON explorer_group_member FOR EACH ROW "
                        + "BEGIN IF OLD.group_id = 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected removal failure'; END IF; END");
        try {
            service.reconcile();
            assertThat(members(1)).containsExactly(oldOwner);
            assertThat(members(2)).containsExactly(newOwner);
        } finally {
            execute("DROP TRIGGER reject_group_removal");
        }
        service.reconcile();
        assertThat(members(1)).containsExactly(newOwner);
    }

    @Test
    void concurrentSameSelectorPublishersCannotRestoreAnOlderSnapshot() throws Exception {
        UUID oldOwner = UUID.randomUUID(), newOwner = UUID.randomUUID();
        seed(oldOwner);
        var firstResolved = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var oldProvider = mock(BusinessGroupMembers.class);
        when(oldProvider.resolve("7")).thenAnswer(invocation -> {
            firstResolved.countDown();
            assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
            return Set.of(oldOwner);
        });
        var newProvider = mock(BusinessGroupMembers.class);
        when(newProvider.resolve("7")).thenReturn(Set.of(newOwner));
        var first = service(oldProvider);
        var second = service(newProvider);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var oldRun = workers.submit(first::reconcile);
            try {
                assertThat(firstResolved.await(10, TimeUnit.SECONDS)).isTrue();
                var newRun = workers.submit(second::reconcile);
                // Positive barrier: the second publisher either finishes (the old bug)
                // or MariaDB confirms it reached the locking read held by the first publisher.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!newRun.isDone() && !publicationWaiting() && System.nanoTime() < deadline) {
                    TimeUnit.MILLISECONDS.sleep(20);
                }
                assertThat(newRun.isDone() || publicationWaiting()).isTrue();
                releaseFirst.countDown();
                oldRun.get(10, TimeUnit.SECONDS);
                newRun.get(10, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
            }
        }
        assertThat(members(1)).containsExactly(newOwner);
    }

    private DerivedGroupReconciliation service(BusinessGroupMembers business) {
        return Guice.createInjector(Modules.override(new DatabaseModule(mock(DatabaseConfiguration.class)))
                        .with(new AbstractModule() {
                            @Override
                            protected void configure() {
                                bind(DataSource.class).toInstance(dataSource);
                                bind(BusinessGroupMembers.class).toInstance(business);
                                bind(RealtyGroupMembers.class).toInstance(mock(RealtyGroupMembers.class));
                            }
                        }))
                .getInstance(DerivedGroupReconciliation.class);
    }

    private void seed(UUID owner) throws Exception {
        execute("INSERT INTO explorer_group (group_id, name) VALUES (1, 'Contended')");
        execute("INSERT INTO explorer_group_sync VALUES (1, 'business', '7')");
        execute("INSERT INTO explorer_group_member (group_id, player_uuid_bin, source, synced_at, sync_selector) "
                + "VALUES (1, uuid_to_bin('" + owner + "'), 'business', NOW(), '7')");
    }

    private Set<UUID> members(int group) throws Exception {
        Set<UUID> result = new java.util.HashSet<>();
        try (var c = dataSource.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT bin_to_uuid(player_uuid_bin) FROM explorer_group_member WHERE group_id = " + group)) {
            while (r.next()) result.add(UUID.fromString(r.getString(1)));
        }
        return result;
    }

    private boolean publicationWaiting() throws Exception {
        try (var c = dataSource.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                        + "WHERE INFO LIKE 'SELECT group_id, source, selector FROM explorer_group_sync%FOR UPDATE'")) {
            r.next();
            return r.getInt(1) > 0;
        }
    }

    private void execute(String sql) throws Exception {
        try (var c = dataSource.getConnection();
                var s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }
}
