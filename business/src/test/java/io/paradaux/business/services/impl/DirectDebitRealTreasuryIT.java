package io.paradaux.business.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.paradaux.business.chat.FirmChatService;
import io.paradaux.business.mappers.DirectDebitMapper;
import io.paradaux.business.mappers.FirmAccountsMapper;
import io.paradaux.business.mappers.FirmMapper;
import io.paradaux.business.mappers.FirmRequestMapper;
import io.paradaux.business.mappers.FirmRoleMapper;
import io.paradaux.business.model.config.FirmConfiguration;
import io.paradaux.business.model.directdebit.CollectionStatus;
import io.paradaux.business.model.directdebit.DirectDebitCollection;
import io.paradaux.business.model.directdebit.DirectDebitKind;
import io.paradaux.business.model.directdebit.DirectDebitMandate;
import io.paradaux.business.model.directdebit.DirectDebitPeriod;
import io.paradaux.business.model.directdebit.DirectDebitStatus;
import io.paradaux.business.model.internal.Firm;
import io.paradaux.business.services.DirectDebitService;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmAreaShopService;
import io.paradaux.business.services.FirmNotificationService;
import io.paradaux.business.services.FirmPlayerService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.business.testsupport.IntegrationTestBase;
import io.paradaux.business.threading.BusinessMessages;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.ExceedsLimitException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.api.impl.TreasuryApiImpl;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.TestServicesModule;
import io.paradaux.treasury.utils.Idempotency;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.guice.MyBatisModule;

/**
 * Direct debits (PAR-557) against the real Treasury ledger and the migrated
 * schema: money moves only under an accepted mandate, the per-period cap holds
 * under concurrent charges, recurring mandates collect once per period and back
 * off then suspend when the customer can't pay, and a reservation left by a crash
 * is settled without debiting twice. Players start with 10 000.
 */
class DirectDebitRealTreasuryIT extends IntegrationTestBase {

    private static final BigDecimal START = new BigDecimal("10000.00");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-04T10:00:00Z")); // a Wednesday
    private final AtomicBoolean flagEnabled = new AtomicBoolean(true);

    private TreasuryApi treasury;
    private FirmServiceImpl firms;
    private DirectDebitMapper debitMapper;
    private DirectDebitStore store;
    private DirectDebitServiceImpl debits;

    @BeforeEach
    void wire() {
        Injector treasuryInjector = Guice.createInjector(
                new io.paradaux.treasury.guice.DatabaseModule(dataSource), new TestServicesModule());
        treasuryInjector.getInstance(LedgerService.class).bootstrapGovernmentAccounts();
        treasury = treasuryInjector.getInstance(TreasuryApiImpl.class);

        Injector business = Guice.createInjector(
                new MyBatisModule() {
                    @Override
                    protected void initialize() {
                        environmentId("direct-debit-test");
                        bindTransactionFactoryType(JdbcTransactionFactory.class);
                        addMapperClass(FirmMapper.class);
                        addMapperClass(io.paradaux.business.mappers.FirmStaffMapper.class);
                        addMapperClass(FirmAccountsMapper.class);
                        addMapperClass(FirmRoleMapper.class);
                        addMapperClass(FirmRequestMapper.class);
                        addMapperClass(DirectDebitMapper.class);
                    }
                },
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(DataSource.class).toInstance(dataSource);
                        bind(TreasuryApi.class).toInstance(treasury);
                        bind(io.paradaux.business.services.FirmService.class).to(FirmServiceImpl.class);
                        bind(FirmConfiguration.class).toInstance(mock(FirmConfiguration.class));
                        bind(FirmStaffService.class).toInstance(mock(FirmStaffService.class));
                        bind(FirmAccountService.class).to(FirmAccountServiceImpl.class);
                        bind(FirmAreaShopService.class).toInstance(mock(FirmAreaShopService.class));
                        bind(FirmChatService.class).toInstance(mock(FirmChatService.class));
                        bind(FirmPlayerService.class).toInstance(mock(FirmPlayerService.class));
                    }
                });
        firms = business.getInstance(FirmServiceImpl.class);
        debitMapper = business.getInstance(DirectDebitMapper.class);
        store = business.getInstance(DirectDebitStore.class);
        debits = new DirectDebitServiceImpl(
                debitMapper,
                store,
                business.getInstance(FirmAccountsMapper.class),
                firms,
                business.getInstance(FirmPlayerService.class),
                mock(FirmNotificationService.class),
                treasury,
                key -> DirectDebitService.FEATURE_FLAG.equals(key) && flagEnabled.get(),
                mock(BusinessMessages.class),
                clock);
    }

    @Test
    void onDemandCharges_moveMoney_andStopAtThePeriodCap() {
        Fixture f = fixture("Gym");
        DirectDebitMandate m = activeOnDemand(f, "100.00");

        debits.charge(f.firm.getFirmId(), m.getMandateId(), f.owner, new BigDecimal("60.00"), "visit 1");
        assertThatThrownBy(() ->
                        debits.charge(f.firm.getFirmId(), m.getMandateId(), f.owner, new BigDecimal("40.01"), null))
                .isInstanceOf(ExceedsLimitException.class);
        DirectDebitService.Charge second =
                debits.charge(f.firm.getFirmId(), m.getMandateId(), f.owner, new BigDecimal("40.00"), null);

        assertThat(second.remainingThisPeriod()).isEqualByComparingTo("0");
        assertThat(payerBalance(f)).isEqualByComparingTo(START.subtract(new BigDecimal("100.00")));
        assertThat(treasury.getBalanceByAccountId(f.firm.getDefaultAccountId())).isEqualByComparingTo("100.00");
        // The customer is recorded as the authorizer, tagged with the debit source.
        var txn = treasury.getTransaction(second.txnId());
        assertThat(txn.getAuthorizerUuid()).isEqualTo(f.payer);
        assertThat(txn.getPluginSystem()).isEqualTo(DirectDebitService.PLUGIN_SYSTEM);

        // A new week resets the cap.
        clock.advance(Duration.ofDays(5)); // Monday
        debits.charge(f.firm.getFirmId(), m.getMandateId(), f.owner, new BigDecimal("100.00"), null);
        assertThat(payerBalance(f)).isEqualByComparingTo(START.subtract(new BigDecimal("200.00")));
    }

    @Test
    void concurrentCharges_neverExceedTheCap() throws Exception {
        Fixture f = fixture("Arcade");
        DirectDebitMandate m = activeOnDemand(f, "100.00");

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    debits.charge(f.firm.getFirmId(), m.getMandateId(), f.owner, new BigDecimal("30.00"), null);
                    return true;
                } catch (ExceedsLimitException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> r : results) if (r.get(60, TimeUnit.SECONDS)) succeeded++;
        pool.shutdown();

        assertThat(succeeded).isEqualTo(3);
        assertThat(payerBalance(f)).isEqualByComparingTo(START.subtract(new BigDecimal("90.00")));
    }

    @Test
    void onlyThePayerAnswers_andOnlyActiveOnDemandMandatesOfTheFirmCanBeCharged() {
        Fixture f = fixture("Bakery");
        DirectDebitMandate pending = debits.propose(
                f.firm.getFirmId(),
                f.owner,
                f.payer,
                DirectDebitKind.ON_DEMAND,
                DirectDebitPeriod.WEEKLY,
                new BigDecimal("50"),
                "Bread");
        long id = pending.getMandateId();

        assertThatThrownBy(() -> debits.accept(id, f.owner)).isInstanceOf(NoPermissionException.class);
        assertThatThrownBy(() -> debits.charge(f.firm.getFirmId(), id, f.owner, BigDecimal.ONE, null))
                .isInstanceOf(ConflictException.class);
        // Only one open proposal per firm and customer.
        assertThatThrownBy(() -> debits.propose(
                        f.firm.getFirmId(),
                        f.owner,
                        f.payer,
                        DirectDebitKind.ON_DEMAND,
                        DirectDebitPeriod.WEEKLY,
                        new BigDecimal("50"),
                        "Bread"))
                .isInstanceOf(ConflictException.class);

        debits.accept(id, f.payer);
        Firm other = firms.createFirm("Other", UUID.randomUUID());
        assertThatThrownBy(() -> debits.charge(other.getFirmId(), id, f.owner, BigDecimal.ONE, null))
                .isInstanceOf(NotFoundException.class);

        debits.revoke(id, f.payer);
        assertThatThrownBy(() -> debits.charge(f.firm.getFirmId(), id, f.owner, BigDecimal.ONE, null))
                .isInstanceOf(ConflictException.class);
        assertThat(payerBalance(f)).isEqualByComparingTo(START);

        DirectDebitMandate recurring = debits.propose(
                f.firm.getFirmId(),
                f.owner,
                f.payer,
                DirectDebitKind.RECURRING,
                DirectDebitPeriod.DAILY,
                new BigDecimal("5"),
                "Daily loaf");
        debits.accept(recurring.getMandateId(), f.payer);
        assertThatThrownBy(() ->
                        debits.charge(f.firm.getFirmId(), recurring.getMandateId(), f.owner, BigDecimal.ONE, null))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void aFirmAccountThatRequiresAnAuthorizer_cannotReceiveDirectDebits() {
        Fixture f = fixture("Vault");
        treasury.updateAccount(
                treasury.getAccountById(f.firm.getDefaultAccountId()).withRequiresAuthorization(true));

        assertThatThrownBy(() -> debits.propose(
                        f.firm.getFirmId(),
                        f.owner,
                        f.payer,
                        DirectDebitKind.ON_DEMAND,
                        DirectDebitPeriod.WEEKLY,
                        new BigDecimal("50"),
                        "Storage"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("business.debit.propose.payee-requires-authorization");
    }

    @Test
    void lapsedProposals_expire_andCannotBeAccepted() {
        Fixture f = fixture("Florist");
        DirectDebitMandate m = debits.propose(
                f.firm.getFirmId(),
                f.owner,
                f.payer,
                DirectDebitKind.ON_DEMAND,
                DirectDebitPeriod.MONTHLY,
                new BigDecimal("20"),
                "Flowers");
        clock.advance(DirectDebitServiceImpl.PROPOSAL_TTL.plusMinutes(1));

        assertThatThrownBy(() -> debits.accept(m.getMandateId(), f.payer)).isInstanceOf(ConflictException.class);
        assertThat(debits.runJob().expired()).isEqualTo(1);
        assertThat(debitMapper.findMandate(m.getMandateId()).getStatus()).isEqualTo(DirectDebitStatus.EXPIRED);
        // The firm can ask again.
        debits.propose(
                f.firm.getFirmId(),
                f.owner,
                f.payer,
                DirectDebitKind.ON_DEMAND,
                DirectDebitPeriod.MONTHLY,
                new BigDecimal("20"),
                "Flowers");
    }

    @Test
    void recurring_collectsOncePerPeriod() {
        Fixture f = fixture("Paper");
        DirectDebitMandate m = activeRecurring(f, DirectDebitPeriod.DAILY, "25.00");

        assertThat(debits.runJob().collected()).isEqualTo(1);
        clock.advance(Duration.ofHours(3));
        assertThat(debits.runJob().collected()).isZero();
        assertThat(payerBalance(f)).isEqualByComparingTo(START.subtract(new BigDecimal("25.00")));
        assertThat(debitMapper.findMandate(m.getMandateId()).getNextDueAt())
                .isEqualTo(clock.utcNow().toLocalDate().plusDays(1).atStartOfDay());

        clock.advance(Duration.ofDays(1));
        assertThat(debits.runJob().collected()).isEqualTo(1);
        assertThat(payerBalance(f)).isEqualByComparingTo(START.subtract(new BigDecimal("50.00")));
    }

    @Test
    void recurring_retriesWithinThePeriod_thenSuspendsAfterRepeatedMissedPeriods() {
        Fixture f = fixture("Yacht");
        // More than the customer has: every attempt fails.
        DirectDebitMandate m = activeRecurring(f, DirectDebitPeriod.DAILY, "20000.00");
        long id = m.getMandateId();

        for (int period = 1; period <= DirectDebitStore.MAX_MISSED_PERIODS; period++) {
            for (int attempt = 1; attempt <= DirectDebitStore.MAX_ATTEMPTS_PER_PERIOD; attempt++) {
                assertThat(debits.runJob().failed())
                        .as("period %d attempt %d", period, attempt)
                        .isEqualTo(1);
                clock.advance(DirectDebitStore.RETRY_DELAY);
            }
            // Out of attempts: nothing more this period.
            assertThat(debits.runJob().failed()).isZero();
            if (period < DirectDebitStore.MAX_MISSED_PERIODS) {
                assertThat(debitMapper.findMandate(id).getMissedPeriods()).isEqualTo(period);
                clock.set(clock.utcNow().toLocalDate().plusDays(1).atTime(1, 0));
            }
        }
        assertThat(debitMapper.findMandate(id).getStatus()).isEqualTo(DirectDebitStatus.SUSPENDED);
        assertThat(payerBalance(f)).isEqualByComparingTo(START);

        debits.resume(id, f.payer);
        DirectDebitMandate resumed = debitMapper.findMandate(id);
        assertThat(resumed.getStatus()).isEqualTo(DirectDebitStatus.ACTIVE);
        assertThat(resumed.getMissedPeriods()).isZero();
    }

    @Test
    void aReservationLeftByACrash_isSettledWithoutDebitingTwice() {
        Fixture f = fixture("Crash");
        DirectDebitMandate m = activeOnDemand(f, "100.00");

        // Crash after the transfer committed but before the collection was settled.
        var afterTransfer = store.reserveOnDemand(
                f.firm.getFirmId(),
                m.getMandateId(),
                f.owner.toString(),
                new BigDecimal("30.00"),
                null,
                clock.utcNow());
        long txn = treasury.transfer(transferFor(m, afterTransfer.collection(), f.owner));
        // Crash before the transfer ran.
        var beforeTransfer = store.reserveOnDemand(
                f.firm.getFirmId(),
                m.getMandateId(),
                f.owner.toString(),
                new BigDecimal("20.00"),
                null,
                clock.utcNow());

        clock.advance(Duration.ofMinutes(10));
        assertThat(debits.runJob().recovered()).isEqualTo(2);

        DirectDebitCollection first =
                debitMapper.findCollection(afterTransfer.collection().getCollectionId());
        DirectDebitCollection second =
                debitMapper.findCollection(beforeTransfer.collection().getCollectionId());
        assertThat(first.getStatus()).isEqualTo(CollectionStatus.COLLECTED);
        assertThat(first.getTxnId()).isEqualTo(txn);
        assertThat(second.getStatus()).isEqualTo(CollectionStatus.COLLECTED);
        assertThat(payerBalance(f)).isEqualByComparingTo(START.subtract(new BigDecimal("50.00")));
    }

    @Test
    void whileTheFlagIsOff_nothingRuns() {
        Fixture f = fixture("Dormant");
        DirectDebitMandate m = activeRecurring(f, DirectDebitPeriod.DAILY, "10.00");
        flagEnabled.set(false);

        assertThat(debits.runJob().isEmpty()).isTrue();
        assertThatThrownBy(() -> debits.listForPayer(f.payer)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> debits.revoke(m.getMandateId(), f.payer)).isInstanceOf(ConflictException.class);
        assertThat(payerBalance(f)).isEqualByComparingTo(START);
    }

    // ---- Fixtures ----------------------------------------------------------------

    private record Fixture(Firm firm, UUID owner, UUID payer) {}

    private Fixture fixture(String name) {
        UUID owner = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        Firm firm = firms.createFirm(name, owner);
        treasury.resolveOrCreatePersonal(payer);
        return new Fixture(firm, owner, payer);
    }

    private DirectDebitMandate activeOnDemand(Fixture f, String cap) {
        DirectDebitMandate m = debits.propose(
                f.firm.getFirmId(),
                f.owner,
                f.payer,
                DirectDebitKind.ON_DEMAND,
                DirectDebitPeriod.WEEKLY,
                new BigDecimal(cap),
                "Membership");
        return debits.accept(m.getMandateId(), f.payer);
    }

    private DirectDebitMandate activeRecurring(Fixture f, DirectDebitPeriod period, String amount) {
        DirectDebitMandate m = debits.propose(
                f.firm.getFirmId(),
                f.owner,
                f.payer,
                DirectDebitKind.RECURRING,
                period,
                new BigDecimal(amount),
                "Subscription");
        return debits.accept(m.getMandateId(), f.payer);
    }

    /** The transfer the service would make for a collection (same dedup key). */
    private static TransferRequest transferFor(DirectDebitMandate m, DirectDebitCollection c, UUID initiator) {
        return new TransferRequest(
                m.getPayerAccountId(),
                m.getPayeeAccountId(),
                c.getAmount(),
                "Direct debit (crash simulation)",
                initiator,
                UUID.fromString(m.getPayerUuid()),
                DirectDebitService.PLUGIN_SYSTEM,
                Idempotency.sha256(DirectDebitService.PLUGIN_SYSTEM + ":" + c.getCollectionId()));
    }

    private BigDecimal payerBalance(Fixture f) {
        return treasury.getBalanceByOwnerUuid(f.payer);
    }

    /** A settable UTC clock. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        void set(java.time.LocalDateTime utc) {
            now = utc.toInstant(ZoneOffset.UTC);
        }

        java.time.LocalDateTime utcNow() {
            return java.time.LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
