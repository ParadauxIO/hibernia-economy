package io.paradaux.business.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.paradaux.business.Business;
import io.paradaux.business.chat.FirmChatService;
import io.paradaux.business.mappers.FirmAccountsMapper;
import io.paradaux.business.mappers.FirmMapper;
import io.paradaux.business.mappers.FirmRequestMapper;
import io.paradaux.business.mappers.FirmRoleMapper;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.config.BalanceTaxConfiguration;
import io.paradaux.business.model.config.FirmConfiguration;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmAreaShopService;
import io.paradaux.business.services.FirmBalanceTaxService.BalanceTaxCycleResult;
import io.paradaux.business.services.FirmPlayerService;
import io.paradaux.business.services.FirmPropertyService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.business.testsupport.IntegrationTestBase;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ExceedsLimitException;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.api.impl.TreasuryApiImpl;
import io.paradaux.treasury.event.TaxCycleEvent;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountMember;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.tax.TaxCycleType;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.TestServicesModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.guice.MyBatisModule;

/**
 * Business money flows run against the REAL Treasury — {@link TreasuryApiImpl} with
 * its production services, mappers and balance triggers, wired exactly as Treasury's
 * own ITs wire it — on the shared test database, instead of the Mockito
 * {@code TreasuryApi} the unit tests use. Business and Treasury get separate Guice
 * injectors and MyBatis environments over one DataSource, as the two plugins do on a
 * server. The harness's post-test ledger invariant check covers every test here.
 *
 * <p>What a stub can't show and these do: the account firm creation opens really is a
 * BUSINESS account the proprietor can access and authorise; deposits/withdrawals
 * move real balances and an overdraw is refused with nothing moved; firm-to-firm
 * payments and the weekly corporate balance tax settle to the cent and the tax is
 * idempotent per period through Treasury's dedup keys.
 */
class RealTreasuryFirmFlowsIT extends IntegrationTestBase {

    private TreasuryApi treasury;
    private LedgerService ledger;
    private FirmServiceImpl firms;
    private FirmTransactionServiceImpl transactions;
    private final FirmConfiguration firmConfig = mock(FirmConfiguration.class);

    @BeforeEach
    void wire() {
        Injector treasuryInjector = Guice.createInjector(
                new io.paradaux.treasury.guice.DatabaseModule(dataSource), new TestServicesModule());
        ledger = treasuryInjector.getInstance(LedgerService.class);
        ledger.bootstrapGovernmentAccounts();
        treasury = treasuryInjector.getInstance(TreasuryApiImpl.class);

        Injector business = Guice.createInjector(
                new MyBatisModule() {
                    @Override
                    protected void initialize() {
                        environmentId("real-treasury-test");
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
                        bind(io.paradaux.business.services.FirmService.class).to(FirmServiceImpl.class);
                        bind(FirmConfiguration.class).toInstance(firmConfig);
                        bind(FirmStaffService.class).toInstance(mock(FirmStaffService.class));
                        bind(FirmAccountService.class).toInstance(mock(FirmAccountService.class));
                        bind(FirmAreaShopService.class).toInstance(mock(FirmAreaShopService.class));
                        bind(FirmChatService.class).toInstance(mock(FirmChatService.class));
                        bind(FirmPlayerService.class).toInstance(mock(FirmPlayerService.class));
                    }
                });
        firms = business.getInstance(FirmServiceImpl.class);
        transactions = business.getInstance(FirmTransactionServiceImpl.class);
    }

    @Test
    void createFirm_opensARealBusinessAccount_theProprietorCanAccessAndAuthorise() {
        UUID owner = UUID.randomUUID();

        Firm firm = firms.createFirm("Acme", owner);

        Account account = treasury.getAccountById(firm.getDefaultAccountId());
        assertThat(account).isNotNull();
        assertThat(account.getAccountType()).isEqualTo(AccountType.BUSINESS);
        assertThat(account.getOwnerUuid()).isEqualTo(owner);
        assertThat(treasury.getBalanceByAccountId(account.getAccountId())).isEqualByComparingTo("0");
        assertThat(treasury.canAccessAccount(owner, account.getAccountId())).isTrue();
        assertThat(treasury.getAuthorizers(account.getAccountId()))
                .extracting(AccountMember::getMemberUuid)
                .contains(owner);
        assertThat(mapper(FirmAccountsMapper.class).isFirmAccount(firm.getFirmId(), account.getAccountId()))
                .isTrue();
    }

    @Test
    void depositAndWithdraw_moveRealMoney_andAnOverdrawMovesNothing() {
        UUID owner = UUID.randomUUID();
        Firm firm = firms.createFirm("Acme", owner);
        int firmAccount = firm.getDefaultAccountId();
        int personal = treasury.resolveOrCreatePersonal(owner).getAccountId(); // 10 000 starting balance

        transactions.deposit(firm.getFirmId(), owner, new BigDecimal("2500.00"));
        transactions.withdraw(firm.getFirmId(), owner, new BigDecimal("1000.25"));

        assertThat(treasury.getBalanceByAccountId(firmAccount)).isEqualByComparingTo("1499.75");
        assertThat(treasury.getBalanceByAccountId(personal)).isEqualByComparingTo("8500.25");

        assertThatThrownBy(() -> transactions.withdraw(firm.getFirmId(), owner, new BigDecimal("1499.76")))
                .isInstanceOf(ExceedsLimitException.class);
        assertThatThrownBy(() -> transactions.deposit(firm.getFirmId(), owner, new BigDecimal("8500.26")))
                .isInstanceOf(ExceedsLimitException.class);
        assertThatThrownBy(() -> transactions.deposit(firm.getFirmId(), owner, BigDecimal.ZERO))
                .isInstanceOf(BadCommandException.class);
        assertThat(treasury.getBalanceByAccountId(firmAccount)).isEqualByComparingTo("1499.75");
        assertThat(treasury.getBalanceByAccountId(personal)).isEqualByComparingTo("8500.25");
    }

    @Test
    void payFirm_movesMoneyBetweenFirms_andRefusesAnOverdraw() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Firm payer = firms.createFirm("Payer", alice);
        Firm payee = firms.createFirm("Payee", bob);
        transactions.deposit(payer.getFirmId(), alice, new BigDecimal("300.00"));

        transactions.payFirm(payer.getFirmId(), payee.getFirmId(), alice, new BigDecimal("120.50"), "invoice 7");

        assertThat(treasury.getBalanceByAccountId(payer.getDefaultAccountId())).isEqualByComparingTo("179.50");
        assertThat(treasury.getBalanceByAccountId(payee.getDefaultAccountId())).isEqualByComparingTo("120.50");
        assertThatThrownBy(() ->
                        transactions.payFirm(payer.getFirmId(), payee.getFirmId(), alice, new BigDecimal("179.51")))
                .isInstanceOf(ExceedsLimitException.class);
        assertThat(treasury.getBalanceByAccountId(payer.getDefaultAccountId())).isEqualByComparingTo("179.50");
    }

    @Test
    void weeklyBalanceTax_collectsExactlyTheFirmTotal_andIsIdempotentPerPeriod() {
        UUID owner = UUID.randomUUID();
        Firm firm = firms.createFirm("Taxed", owner);
        int main = firm.getDefaultAccountId();
        int second = treasury.createAccount(AccountType.BUSINESS, owner, "Taxed Reserve")
                .getAccountId();
        mapper(FirmAccountsMapper.class).insertFirmAccount(firm.getFirmId(), second);
        transactions.deposit(firm.getFirmId(), owner, new BigDecimal("50.00"));
        int personal = treasury.resolveOrCreatePersonal(owner).getAccountId();
        treasury.transfer(new io.paradaux.treasury.model.economy.TransferRequest(
                personal, second, new BigDecimal("101.00"), "fund reserve", owner, null, "test", null));
        int government = treasury.getGovernmentAccountByName("DCGovernment").getAccountId();
        BigDecimal govBefore = treasury.getBalanceByAccountId(government);

        // 151.00 total at 3.33% → 5.0283 → 5.03, split 50/101 by largest remainder:
        // quotas 166.56c / 336.44c → floors 166 + 336 = 502, the spare cent goes to .56.
        FirmBalanceTaxServiceImpl tax = balanceTax(firm, List.of(main, second), new BigDecimal("0.0333"));
        TaxCycleEvent cycle =
                new TaxCycleEvent(TaxCycleType.WEEKLY, Instant.parse("2026-10-12T00:00:00Z"), treasury.getTaxApi());

        BalanceTaxCycleResult first = tax.runWeeklyCycle(cycle);

        assertThat(first).isEqualTo(new BalanceTaxCycleResult(2, 0, 0));
        assertThat(treasury.getBalanceByAccountId(main)).isEqualByComparingTo("48.33");
        assertThat(treasury.getBalanceByAccountId(second)).isEqualByComparingTo("97.64");
        assertThat(treasury.getBalanceByAccountId(government).subtract(govBefore))
                .isEqualByComparingTo("5.03");

        // Same period again (a retried/duplicated cycle): Treasury's dedup keys make it a
        // no-op — reported as collected, but no money moves a second time.
        tax.runWeeklyCycle(cycle);
        assertThat(treasury.getBalanceByAccountId(main)).isEqualByComparingTo("48.33");
        assertThat(treasury.getBalanceByAccountId(second)).isEqualByComparingTo("97.64");
        assertThat(treasury.getBalanceByAccountId(government).subtract(govBefore))
                .isEqualByComparingTo("5.03");
    }

    private FirmBalanceTaxServiceImpl balanceTax(Firm firm, List<Integer> accountIds, BigDecimal rate) {
        BalanceTaxConfiguration config = mock(BalanceTaxConfiguration.class);
        when(config.getWeeklyRate(any())).thenReturn(rate);
        when(config.getGovernmentAccount()).thenReturn("DCGovernment");
        io.paradaux.business.services.FirmService firmService = mock(io.paradaux.business.services.FirmService.class);
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm));
        FirmPropertyService properties = mock(FirmPropertyService.class);
        when(properties.getBoolean(anyInt(), anyString())).thenReturn(Optional.empty());
        FirmAccountService accounts = mock(FirmAccountService.class);
        when(accounts.listAccountIds(firm.getFirmId())).thenReturn(accountIds);
        Business plugin = mock(Business.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("real-treasury-it"));
        return new FirmBalanceTaxServiceImpl(config, firmService, properties, accounts, transactions, treasury, plugin);
    }
}
