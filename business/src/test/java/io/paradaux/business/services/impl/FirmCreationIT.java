package io.paradaux.business.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import io.paradaux.business.chat.FirmChatService;
import io.paradaux.business.mappers.FirmAccountsMapper;
import io.paradaux.business.mappers.FirmMapper;
import io.paradaux.business.mappers.FirmRequestMapper;
import io.paradaux.business.mappers.FirmRoleMapper;
import io.paradaux.business.model.config.FirmConfiguration;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmAreaShopService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.business.testsupport.IntegrationTestBase;
import io.paradaux.hibernia.framework.exceptions.ExceedsLimitException;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.Account;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.guice.MyBatisModule;

/** Real Guice transaction interception and independent MariaDB connections. */
class FirmCreationIT extends IntegrationTestBase {
    private final TreasuryApi treasury = mock(TreasuryApi.class);
    private final FirmConfiguration config = mock(FirmConfiguration.class);
    private FirmServiceImpl service;

    @BeforeEach
    void wireService() {
        service = Guice.createInjector(
                        new MyBatisModule() {
                            @Override
                            protected void initialize() {
                                environmentId("firm-creation-test");
                                bindTransactionFactoryType(JdbcTransactionFactory.class);
                                addMapperClass(FirmMapper.class);
                                addMapperClass(FirmAccountsMapper.class);
                                addMapperClass(FirmRoleMapper.class);
                                addMapperClass(FirmRequestMapper.class);
                            }
                        },
                        new AbstractModule() {
                            @Override
                            protected void configure() {
                                bind(DataSource.class).toInstance(dataSource);
                                bind(TreasuryApi.class).toInstance(treasury);
                                bind(FirmConfiguration.class).toInstance(config);
                                bind(FirmStaffService.class).toInstance(mock(FirmStaffService.class));
                                bind(FirmAccountService.class).toInstance(mock(FirmAccountService.class));
                                bind(FirmAreaShopService.class).toInstance(mock(FirmAreaShopService.class));
                                bind(FirmChatService.class).toInstance(mock(FirmChatService.class));
                            }
                        })
                .getInstance(FirmServiceImpl.class);
        when(treasury.createAccount(any(), any(), any())).thenAnswer(inv -> {
            Account account = new Account();
            account.setAccountId(insertStubAccount(inv.getArgument(2)));
            return account;
        });
    }

    @Test
    void concurrentCreates_cannotExceedOwnershipLimit() throws Exception {
        when(config.hasOwnedFirmLimit()).thenReturn(true);
        when(config.getOwnedFirmLimit()).thenReturn(2);
        UUID owner = UUID.randomUUID();
        service.createFirm("Existing", owner); // Also exercises an existing lock row.
        race(owner, owner, "ownership limit", false);
        assertThat(mapper(FirmMapper.class).getFirmsOwnedByCount(owner.toString()))
                .isEqualTo(2);
    }

    @Test
    void concurrentFirstCreates_cannotBypassCooldown() throws Exception {
        when(config.hasCreateCooldown()).thenReturn(true);
        when(config.getCreateCooldownSeconds()).thenReturn(300);
        UUID owner = UUID.randomUUID();
        race(owner, owner, "before creating another firm", false);
        assertThat(mapper(FirmMapper.class).getFirmsOwnedByCount(owner.toString()))
                .isEqualTo(1);
    }

    @Test
    void disabledLimits_allowBothCreates() throws Exception {
        UUID owner = UUID.randomUUID();
        race(owner, owner, null, false);
        assertThat(mapper(FirmMapper.class).getFirmsOwnedByCount(owner.toString()))
                .isEqualTo(2);
    }

    @Test
    void differentOwners_doNotBlockEachOther() throws Exception {
        race(UUID.randomUUID(), UUID.randomUUID(), null, false);
    }

    @Test
    void rollback_releasesLockAndDoesNotConsumeLimitOrCooldown() throws Exception {
        when(config.hasOwnedFirmLimit()).thenReturn(true);
        when(config.getOwnedFirmLimit()).thenReturn(1);
        when(config.hasCreateCooldown()).thenReturn(true);
        when(config.getCreateCooldownSeconds()).thenReturn(300);
        UUID owner = UUID.randomUUID();
        race(owner, owner, null, true);
        assertThat(mapper(FirmMapper.class).getFirmsOwnedByCount(owner.toString()))
                .isEqualTo(1);
        verify(treasury).archiveAccount(anyInt());
    }

    private void race(UUID firstOwner, UUID secondOwner, String rejection, boolean rollback) throws Exception {
        CountDownLatch firstInsideTransaction = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        // Pause after all reads and the insert, while the transaction still owns the lock.
        doAnswer(inv -> {
                    if (firstCall.compareAndSet(true, false)) {
                        firstInsideTransaction.countDown();
                        if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new AssertionError("Release timed out");
                        if (rollback) throw new IllegalStateException("Injected failure");
                    }
                    return null;
                })
                .when(treasury)
                .addMember(anyInt(), any(), any());

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.createFirm("First", firstOwner));
            assertThat(firstInsideTransaction.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return service.createFirm("Second", secondOwner);
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            if (firstOwner.equals(secondOwner)) {
                // Prove the second create is parked on the first one's row lock, rather than
                // inferring it from "not finished within 300ms" — which also passes when the
                // second thread simply hasn't reached the lock yet, i.e. when there is no lock.
                awaitSecondCreateBlockedOnLock(secondOwner);
                assertThat(second.isDone())
                        .as("second create must still be blocked")
                        .isFalse();
            } else {
                assertThat(second.get(10, TimeUnit.SECONDS).getFirmId()).isPositive();
            }
            releaseFirst.countDown();
            if (rollback) {
                assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(IllegalStateException.class);
            } else {
                assertThat(first.get(10, TimeUnit.SECONDS).getFirmId()).isPositive();
            }
            if (rejection != null) {
                assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(ExceedsLimitException.class)
                        .hasStackTraceContaining(rejection);
                verify(treasury, never()).createAccount(any(), any(), eq("Second Corporate Account"));
            } else {
                assertThat(second.get(10, TimeUnit.SECONDS).getFirmId()).isPositive();
            }
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * Waits until the server shows the second create's {@code firm_creation_lock} upsert
     * for {@code owner} still executing after 200ms — the server's own evidence that it
     * is parked behind the first transaction's row lock (a single-row upsert otherwise
     * finishes in well under a millisecond). Uses {@code information_schema.PROCESSLIST}
     * ({@code TIME_MS} is MariaDB-specific, present on 10.11 and 11.x); InnoDB's
     * {@code INNODB_TRX} was tried first but doesn't list a not-yet-started implicit
     * (autocommit=0) transaction that is blocked on its very first statement.
     */
    private void awaitSecondCreateBlockedOnLock(UUID owner) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String sql = "SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                + "WHERE COMMAND = 'Query' AND INFO LIKE ? AND INFO LIKE ? AND TIME_MS >= 200";
        while (System.nanoTime() < deadline) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, "%INSERT INTO firm_creation_lock%");
                ps.setString(2, "%" + owner + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("second createFirm never blocked on the firm_creation_lock row lock");
    }
}
