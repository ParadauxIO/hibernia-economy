package io.paradaux.treasury.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.paradaux.common.ProgressiveTaxBrackets;
import io.paradaux.common.TaxPeriod;
import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.mappers.IncomeTaxMapper;
import io.paradaux.treasury.model.config.ProgressiveIncomeTaxConfiguration;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.EconomyNotifier;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.TaxCycleRegistry;
import io.paradaux.treasury.utils.Idempotency;
import io.paradaux.treasury.utils.TreasuryConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IncomeTaxServiceImplTest {

    private static final Instant ANCHOR = Instant.parse("2026-01-01T00:00:00Z");
    /** 10 days into the first 30-day period. */
    private static final Instant NOW = ANCHOR.plus(Duration.ofDays(10));

    private static final UUID EARNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PAYER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final int GOV_ID = 99;

    @Mock
    IncomeTaxMapper incomeTaxMapper;

    @Mock
    AccountMapper accountMapper;

    @Mock
    LedgerService ledgerService;

    @Mock
    TaxCycleRegistry cycleRegistry;

    @Mock
    EconomyNotifier notifier;

    private Account earner;
    private Account payer;

    @BeforeEach
    void accounts() {
        earner = account(1, AccountType.PERSONAL, EARNER, null);
        payer = account(2, AccountType.PERSONAL, PAYER, null);
    }

    private static Account account(int id, AccountType type, UUID owner, String name) {
        Account a = new Account();
        a.setAccountId(id);
        a.setAccountType(type);
        a.setOwnerUuid(owner);
        a.setDisplayName(name);
        return a;
    }

    private static ProgressiveIncomeTaxConfiguration.Snapshot config(
            boolean enabled, Set<String> exemptAccounts, Set<String> exemptPlugins) {
        Map<String, String> brackets = new LinkedHashMap<>();
        brackets.put("0", "0");
        brackets.put("5000", "0.03");
        return new ProgressiveIncomeTaxConfiguration.Snapshot(
                enabled,
                "IncomeTax",
                TaxPeriod.of(Duration.ofDays(30), ANCHOR),
                ProgressiveTaxBrackets.fromRawEntries(brackets, w -> {}),
                exemptAccounts,
                exemptPlugins);
    }

    private IncomeTaxServiceImpl service(ProgressiveIncomeTaxConfiguration.Snapshot snapshot, Instant now) {
        return new IncomeTaxServiceImpl(
                ProgressiveIncomeTaxConfiguration.forTesting(snapshot),
                incomeTaxMapper,
                accountMapper,
                () -> ledgerService,
                cycleRegistry,
                notifier,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private IncomeTaxServiceImpl enabledService() {
        return service(config(true, Set.of("starting-balances"), Set.of("Treasury-Pay")), NOW);
    }

    private void governmentAccountExists() {
        when(accountMapper.findGovernmentAccountByName("IncomeTax"))
                .thenReturn(
                        account(GOV_ID, AccountType.GOVERNMENT, TreasuryConstants.VIRTUAL_TREASURY_OWNER, "IncomeTax"));
    }

    @Test
    void collectsTheMarginalTaxOnAPaymentThatCrossesAFloor() {
        governmentAccountExists();
        when(incomeTaxMapper.readIncome(EARNER, ANCHOR.toEpochMilli())).thenReturn(new BigDecimal("500.00"));
        when(ledgerService.adminTransfer(any())).thenReturn(77L);

        OptionalLong txn = enabledService().onIncome(payer, earner, new BigDecimal("5000"), "Test", 42L);

        assertThat(txn).hasValue(77L);
        verify(incomeTaxMapper).lockPeriod(EARNER, ANCHOR.toEpochMilli());
        verify(incomeTaxMapper)
                .addIncome(EARNER, ANCHOR.toEpochMilli(), new BigDecimal("5000.00"), new BigDecimal("15.00"));
        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(ledgerService).adminTransfer(req.capture());
        assertThat(req.getValue().fromAccountId()).isEqualTo(1);
        assertThat(req.getValue().toAccountId()).isEqualTo(GOV_ID);
        assertThat(req.getValue().amount()).isEqualByComparingTo("15.00");
        assertThat(req.getValue().initiator()).isEqualTo(TreasuryConstants.VIRTUAL_TREASURY_INITIATOR);
        assertThat(req.getValue().pluginSystem()).isEqualTo(TreasuryConstants.TREASURY_PLUGIN_NAME);
        assertThat(req.getValue().message()).contains("5000.00").contains("5500.00");
        assertThat(req.getValue().dedupKey()).isEqualTo(Idempotency.sha256("progressive-income-tax:42"));
        verify(cycleRegistry).recordCollected(GOV_ID, IncomeTaxServiceImpl.TAX_TYPE, new BigDecimal("15.00"));
        verify(notifier).notifyTaxCollected(1, IncomeTaxServiceImpl.TAX_TYPE, new BigDecimal("15.00"));
    }

    @Test
    void recordsUntaxedIncomeWithoutCollecting() {
        when(incomeTaxMapper.readIncome(EARNER, ANCHOR.toEpochMilli())).thenReturn(BigDecimal.ZERO);

        OptionalLong txn = enabledService().onIncome(payer, earner, new BigDecimal("500"), "Test", 1L);

        assertThat(txn).isEmpty();
        verify(incomeTaxMapper)
                .addIncome(EARNER, ANCHOR.toEpochMilli(), new BigDecimal("500.00"), new BigDecimal("0.00"));
        verifyNoInteractions(ledgerService, cycleRegistry, notifier);
    }

    @Test
    void treatsAMissingRowAsNoIncomeYet() {
        when(incomeTaxMapper.readIncome(EARNER, ANCHOR.toEpochMilli())).thenReturn(null);

        enabledService().onIncome(payer, earner, new BigDecimal("100"), "Test", 1L);

        verify(incomeTaxMapper)
                .addIncome(EARNER, ANCHOR.toEpochMilli(), new BigDecimal("100.00"), new BigDecimal("0.00"));
    }

    @Test
    void countsIncomeInThePeriodContainingNow() {
        Instant secondPeriod = ANCHOR.plus(Duration.ofDays(45));
        IncomeTaxServiceImpl svc = service(config(true, Set.of(), Set.of()), secondPeriod);
        long expectedStart = ANCHOR.plus(Duration.ofDays(30)).toEpochMilli();
        when(incomeTaxMapper.readIncome(EARNER, expectedStart)).thenReturn(BigDecimal.ZERO);

        svc.onIncome(payer, earner, new BigDecimal("10"), "Test", 1L);

        verify(incomeTaxMapper).lockPeriod(EARNER, expectedStart);
    }

    @Test
    void doesNothingWhenDisabled() {
        service(config(false, Set.of(), Set.of()), NOW).onIncome(payer, earner, new BigDecimal("9000"), "Test", 1L);
        verifyNoInteractions(incomeTaxMapper, ledgerService);
    }

    @Test
    void doesNothingWithoutAPositiveBracket() {
        var empty = new ProgressiveIncomeTaxConfiguration.Snapshot(
                true,
                "IncomeTax",
                TaxPeriod.of(Duration.ofDays(30), ANCHOR),
                ProgressiveTaxBrackets.fromRawEntries(Map.of("0", "0"), w -> {}),
                Set.of(),
                Set.of());
        service(empty, NOW).onIncome(payer, earner, new BigDecimal("9000"), "Test", 1L);
        verifyNoInteractions(incomeTaxMapper, ledgerService);
    }

    @Test
    void ignoresPaymentsToNonPersonalAccounts() {
        Account firm = account(5, AccountType.BUSINESS, EARNER, "Firm");
        enabledService().onIncome(payer, firm, new BigDecimal("9000"), "Test", 1L);
        verifyNoInteractions(incomeTaxMapper, ledgerService);
    }

    @Test
    void ignoresPersonalAccountsWithoutAnOwner() {
        earner.setOwnerUuid(null);
        enabledService().onIncome(payer, earner, new BigDecimal("9000"), "Test", 1L);
        verifyNoInteractions(incomeTaxMapper, ledgerService);
    }

    @Test
    void movesBetweenAPlayersOwnAccountsAreNotIncome() {
        Account ownFirm = account(5, AccountType.BUSINESS, EARNER, "MyFirm");
        enabledService().onIncome(ownFirm, earner, new BigDecimal("9000"), "Test", 1L);
        verifyNoInteractions(incomeTaxMapper, ledgerService);
    }

    @Test
    void exemptSourceAccountsAreMatchedByNameIgnoringCase() {
        Account grant =
                account(3, AccountType.GOVERNMENT, TreasuryConstants.VIRTUAL_TREASURY_OWNER, "Starting-Balances");
        enabledService().onIncome(grant, earner, new BigDecimal("9000"), "Treasury-Core", 1L);
        verifyNoInteractions(incomeTaxMapper, ledgerService);
    }

    @Test
    void exemptNamesDoNotApplyToPlayers() {
        payer.setDisplayName("starting-balances");
        when(incomeTaxMapper.readIncome(any(), anyLong())).thenReturn(BigDecimal.ZERO);

        enabledService().onIncome(payer, earner, new BigDecimal("10"), "Test", 1L);

        verify(incomeTaxMapper).addIncome(any(), anyLong(), eq(new BigDecimal("10.00")), any());
    }

    @Test
    void exemptPluginsAreMatchedIgnoringCase() {
        enabledService().onIncome(payer, earner, new BigDecimal("9000"), "treasury-pay", 1L);
        verifyNoInteractions(incomeTaxMapper, ledgerService);
    }

    @Test
    void chestShopSalesAreNotIncomeByDefault() {
        enabledService().onIncome(payer, earner, new BigDecimal("9000"), "ChestShop", 1L);
        verifyNoInteractions(incomeTaxMapper, ledgerService);
    }

    @Test
    void playerPaymentsCanBeSwitchedOff() {
        var base = config(true, Set.of(), Set.of());
        var noPay = new ProgressiveIncomeTaxConfiguration.Snapshot(
                base.enabled(),
                base.governmentAccount(),
                base.period(),
                base.brackets(),
                Set.of(),
                Set.of(),
                false,
                false);
        service(noPay, NOW).onIncome(payer, earner, new BigDecimal("9000"), TreasuryConstants.PAY_PLUGIN_SYSTEM, 1L);
        verifyNoInteractions(incomeTaxMapper, ledgerService);
    }

    @Test
    void chestShopSalesCanBeSwitchedOn() {
        var base = config(true, Set.of(), Set.of());
        var withShops = new ProgressiveIncomeTaxConfiguration.Snapshot(
                base.enabled(),
                base.governmentAccount(),
                base.period(),
                base.brackets(),
                Set.of(),
                Set.of(),
                true,
                true);
        when(incomeTaxMapper.readIncome(any(), anyLong())).thenReturn(BigDecimal.ZERO);

        service(withShops, NOW).onIncome(payer, earner, new BigDecimal("10"), "ChestShop", 1L);

        verify(incomeTaxMapper).lockPeriod(EARNER, ANCHOR.toEpochMilli());
    }

    @Test
    void paymentsWithNoPluginAreIncome() {
        when(incomeTaxMapper.readIncome(any(), anyLong())).thenReturn(BigDecimal.ZERO);
        enabledService().onIncome(payer, earner, new BigDecimal("10"), null, 1L);
        verify(incomeTaxMapper).lockPeriod(EARNER, ANCHOR.toEpochMilli());
    }

    @Test
    void cachesAnExistingDestinationAccount() {
        governmentAccountExists();
        when(incomeTaxMapper.readIncome(any(), anyLong())).thenReturn(new BigDecimal("6000"));
        IncomeTaxServiceImpl svc = enabledService();

        svc.onIncome(payer, earner, new BigDecimal("100"), "Test", 1L);
        svc.onIncome(payer, earner, new BigDecimal("100"), "Test", 2L);

        verify(accountMapper, times(1)).findGovernmentAccountByName("IncomeTax");
        verify(ledgerService, times(2)).adminTransfer(any());
    }

    @Test
    void createsAMissingDestinationWithoutCachingIt() {
        when(incomeTaxMapper.readIncome(any(), anyLong())).thenReturn(new BigDecimal("6000"));
        doAnswer(inv -> {
                    ((Account) inv.getArgument(0)).setAccountId(123);
                    return 1;
                })
                .when(accountMapper)
                .insertAccount(any());
        IncomeTaxServiceImpl svc = enabledService();

        svc.onIncome(payer, earner, new BigDecimal("100"), "Test", 1L);
        svc.onIncome(payer, earner, new BigDecimal("100"), "Test", 2L);

        verify(accountMapper, times(2)).findGovernmentAccountByName("IncomeTax");
        verify(accountMapper, times(2)).seedBalance(123);
        verify(cycleRegistry, times(2)).recordCollected(eq(123), eq(IncomeTaxServiceImpl.TAX_TYPE), any());
    }

    @Test
    void reResolvesTheDestinationWhenItsNameChanges() {
        governmentAccountExists();
        when(accountMapper.findGovernmentAccountByName("Other"))
                .thenReturn(account(50, AccountType.GOVERNMENT, TreasuryConstants.VIRTUAL_TREASURY_OWNER, "Other"));
        when(incomeTaxMapper.readIncome(any(), anyLong())).thenReturn(new BigDecimal("6000"));
        var first = config(true, Set.of(), Set.of());
        var cfg = ProgressiveIncomeTaxConfiguration.forTesting(first);
        IncomeTaxServiceImpl svc = new IncomeTaxServiceImpl(
                cfg,
                incomeTaxMapper,
                accountMapper,
                () -> ledgerService,
                cycleRegistry,
                notifier,
                Clock.fixed(NOW, ZoneOffset.UTC));

        svc.onIncome(payer, earner, new BigDecimal("100"), "Test", 1L);
        cfg.publish(new ProgressiveIncomeTaxConfiguration.Snapshot(
                true, "Other", first.period(), first.brackets(), Set.of(), Set.of()));
        svc.onIncome(payer, earner, new BigDecimal("100"), "Test", 2L);

        verify(cycleRegistry).recordCollected(eq(GOV_ID), any(), any());
        verify(cycleRegistry).recordCollected(eq(50), any(), any());
    }

    @Test
    void neverCollectsWhenNothingIsOwed() {
        when(incomeTaxMapper.readIncome(any(), anyLong())).thenReturn(new BigDecimal("4999.99"));
        // 0.01 straddles the floor by nothing: 4999.99 -> 5000.00 is still all 0%.
        enabledService().onIncome(payer, earner, new BigDecimal("0.01"), "Test", 1L);
        verify(ledgerService, never()).adminTransfer(any());
    }
}
