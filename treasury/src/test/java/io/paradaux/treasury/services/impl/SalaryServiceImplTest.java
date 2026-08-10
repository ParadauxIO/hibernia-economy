package io.paradaux.treasury.services.impl;

import io.paradaux.treasury.model.config.SalaryConfiguration;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.model.salary.SalaryPayment;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.EconomyNotifier;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.utils.Idempotency;
import io.paradaux.treasury.utils.TreasuryConstants;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalaryServiceImplTest {

    @Mock AccountService accountService;
    @Mock LedgerService ledgerService;
    @Mock Server server;
    @Mock LuckPerms luckPerms;
    @Mock UserManager userManager;
    @Mock EconomyNotifier notifier;

    private static final Map<String, BigDecimal> AMOUNTS = Map.of(
            "senator", new BigDecimal("65.0"),
            "president", new BigDecimal("75.0"),
            "owner", BigDecimal.ZERO);

    private SalaryConfiguration cfg(boolean enabled, Map<String, BigDecimal> amounts) {
        return SalaryConfiguration.forTesting(enabled, "DCGovernment", 900, amounts);
    }

    private SalaryServiceImpl service(SalaryConfiguration config, boolean withLuckPerms) {
        SalaryServiceImpl svc = new SalaryServiceImpl(config, accountService, ledgerService, server, notifier);
        if (withLuckPerms) svc.setLuckPerms(luckPerms);
        return svc;
    }

    /** Wire an online player whose LuckPerms user resolves to the given groups. */
    private Player onlinePlayer(UUID uuid, String... groups) {
        Player p = org.mockito.Mockito.mock(Player.class);
        when(p.getUniqueId()).thenReturn(uuid);
        User user = org.mockito.Mockito.mock(User.class);
        QueryOptions qo = org.mockito.Mockito.mock(QueryOptions.class);
        when(luckPerms.getUserManager()).thenReturn(userManager);
        when(userManager.getUser(uuid)).thenReturn(user);
        when(user.getQueryOptions()).thenReturn(qo);
        Set<Group> set = new LinkedHashSet<>();
        for (String g : groups) {
            Group grp = org.mockito.Mockito.mock(Group.class);
            when(grp.getName()).thenReturn(g);
            set.add(grp);
        }
        when(user.getInheritedGroups(qo)).thenReturn(set);
        return p;
    }

    private void online(Player... players) {
        doReturn(List.of(players)).when(server).getOnlinePlayers();
    }

    // ---- planPayroll ----

    @Test
    void plan_emptyWhenDisabled() {
        assertThat(service(cfg(false, AMOUNTS), true).planPayroll()).isEmpty();
        verifyNoInteractions(server);
    }

    @Test
    void plan_emptyWhenNoAmounts() {
        assertThat(service(cfg(true, Map.of()), true).planPayroll()).isEmpty();
    }

    @Test
    void plan_emptyWhenLuckPermsAbsent() {
        assertThat(service(cfg(true, AMOUNTS), false).planPayroll()).isEmpty();
    }

    @Test
    void plan_paysHighestSalaryAmongGroups() {
        UUID u = UUID.randomUUID();
        online(onlinePlayer(u, "senator", "president", "owner"));
        List<SalaryPayment> plan = service(cfg(true, AMOUNTS), true).planPayroll();
        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).player()).isEqualTo(u);
        assertThat(plan.get(0).group()).isEqualTo("president");
        assertThat(plan.get(0).amount()).isEqualByComparingTo("75.0");
    }

    @Test
    void plan_skipsPlayersWithNoSalariedGroup() {
        online(onlinePlayer(UUID.randomUUID(), "guest", "owner")); // owner=0, guest unlisted
        assertThat(service(cfg(true, AMOUNTS), true).planPayroll()).isEmpty();
    }

    @Test
    void plan_skipsAfkPlayer() {
        // A salaried player flagged AFK via the LuckPerms context is not paid.
        Player p = org.mockito.Mockito.mock(Player.class);
        online(p);
        ContextManager cm = org.mockito.Mockito.mock(ContextManager.class);
        ImmutableContextSet ctx = org.mockito.Mockito.mock(ImmutableContextSet.class);
        when(luckPerms.getContextManager()).thenReturn(cm);
        when(cm.getContext(p)).thenReturn(ctx);
        when(ctx.contains("afk", "true")).thenReturn(true); // default afk context

        assertThat(service(cfg(true, AMOUNTS), true).planPayroll()).isEmpty();
    }

    @Test
    void plan_skipsWhenLuckPermsUserUnknown() {
        UUID u = UUID.randomUUID();
        Player p = org.mockito.Mockito.mock(Player.class);
        when(p.getUniqueId()).thenReturn(u);
        when(luckPerms.getUserManager()).thenReturn(userManager);
        when(userManager.getUser(u)).thenReturn(null);
        online(p);
        assertThat(service(cfg(true, AMOUNTS), true).planPayroll()).isEmpty();
    }

    // ---- payout ----

    @Test
    void payout_zeroForEmptyPlan() {
        assertThat(service(cfg(true, AMOUNTS), true).payout(List.of())).isZero();
        verifyNoInteractions(accountService, ledgerService, notifier);
    }

    @Test
    void payout_zeroWhenGovAccountMissing() {
        when(accountService.getGovernmentAccountByName("DCGovernment")).thenReturn(null);
        int paid = service(cfg(true, AMOUNTS), true)
                .payout(List.of(new SalaryPayment(UUID.randomUUID(), "senator", new BigDecimal("65.0"))));
        assertThat(paid).isZero();
        verify(ledgerService, never()).transferChecked(any());
        verifyNoInteractions(notifier);
    }

    @Test
    void payout_transfersFromGovToEachPlayer() {
        UUID alice = UUID.randomUUID();
        Account gov = new Account();
        gov.setAccountId(7);
        when(accountService.getGovernmentAccountByName("DCGovernment")).thenReturn(gov);
        when(accountService.getOrCreatePersonalAccountId(alice)).thenReturn(42);
        when(ledgerService.transferChecked(any())).thenReturn(new LedgerService.TransferResult(1L, true));

        int paid = service(cfg(true, AMOUNTS), true)
                .payout(List.of(new SalaryPayment(alice, "senator", new BigDecimal("65.0"))));

        assertThat(paid).isEqualTo(1);
        ArgumentCaptor<TransferRequest> cap = ArgumentCaptor.forClass(TransferRequest.class);
        verify(ledgerService).transferChecked(cap.capture());
        TransferRequest req = cap.getValue();
        assertThat(req.fromAccountId()).isEqualTo(7);
        assertThat(req.toAccountId()).isEqualTo(42);
        assertThat(req.amount()).isEqualByComparingTo("65.0");
        assertThat(req.initiator()).isEqualTo(TreasuryConstants.VIRTUAL_TREASURY_INITIATOR);
        assertThat(req.pluginSystem()).isEqualTo("treasury-salary");
        assertThat(req.message()).contains("senator");

        // The recipient is notified of the salary they received.
        ArgumentCaptor<BigDecimal> amt = ArgumentCaptor.forClass(BigDecimal.class);
        verify(notifier).notifySalaryPaid(org.mockito.ArgumentMatchers.eq(alice), amt.capture());
        assertThat(amt.getValue()).isEqualByComparingTo("65.0");
    }

    @Test
    void payout_attachesDeterministicPerPeriodDedupKey() {
        // Without a dedup key, two overlapping payout runs in the same interval
        // would pay a player twice. The key is derived from the period bucket
        // (interval 900s), so a retry/overlap collapses while the next period pays.
        UUID alice = UUID.randomUUID();
        Account gov = new Account();
        gov.setAccountId(7);
        when(accountService.getGovernmentAccountByName("DCGovernment")).thenReturn(gov);
        when(accountService.getOrCreatePersonalAccountId(alice)).thenReturn(42);

        when(ledgerService.transferChecked(any())).thenReturn(new LedgerService.TransferResult(1L, true));

        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_000_000L), ZoneOffset.UTC);
        SalaryServiceImpl svc = new SalaryServiceImpl(
                cfg(true, AMOUNTS), accountService, ledgerService, server, notifier, clock);

        svc.payout(List.of(new SalaryPayment(alice, "senator", new BigDecimal("65.0"))));

        ArgumentCaptor<TransferRequest> cap = ArgumentCaptor.forClass(TransferRequest.class);
        verify(ledgerService).transferChecked(cap.capture());
        // 1_000_000 mod 900 == 100 → period start 999_900.
        byte[] expected = Idempotency.sha256("salary:999900:" + alice);
        assertThat(cap.getValue().dedupKey()).isEqualTo(expected);
    }

    @Test
    void payout_doesNotNotifyOrCountWhenTransferCollapsesOntoExistingDedupKey() {
        // ADT-8 semantics: a payment whose dedup key already exists collapses onto the
        // prior txn (no new money moved). transferChecked reports created=false, so the
        // player must NOT be re-notified and the payout must NOT be counted — otherwise a
        // retry or a manual run overlapping the scheduled task double-notifies. This is
        // race-safe: the engine reports created=false whether the collapse is detected by
        // the pre-insert check or by a concurrent-insert race, so it can't be defeated by
        // two runs both passing an earlier pre-check.
        UUID alice = UUID.randomUUID();
        Account gov = new Account();
        gov.setAccountId(7);
        when(accountService.getGovernmentAccountByName("DCGovernment")).thenReturn(gov);
        when(accountService.getOrCreatePersonalAccountId(alice)).thenReturn(42);
        // The transfer collapses onto an existing period txn → created=false.
        when(ledgerService.transferChecked(any())).thenReturn(new LedgerService.TransferResult(123L, false));

        int paid = service(cfg(true, AMOUNTS), true)
                .payout(List.of(new SalaryPayment(alice, "senator", new BigDecimal("65.0"))));

        // Corrected behaviour: transfer attempted, but no notify and not counted.
        assertThat(paid).isZero();
        verify(ledgerService).transferChecked(any());
        verify(notifier, never()).notifySalaryPaid(org.mockito.ArgumentMatchers.eq(alice), any());
    }

    @Test
    void payout_continuesWhenOneTransferFails() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Account gov = new Account();
        gov.setAccountId(7);
        when(accountService.getGovernmentAccountByName("DCGovernment")).thenReturn(gov);
        when(accountService.getOrCreatePersonalAccountId(a)).thenReturn(1);
        when(accountService.getOrCreatePersonalAccountId(b)).thenReturn(2);
        doThrow(new RuntimeException("boom"))
                .doReturn(new LedgerService.TransferResult(99L, true))
                .when(ledgerService).transferChecked(any());

        List<SalaryPayment> plan = new ArrayList<>();
        plan.add(new SalaryPayment(a, "senator", new BigDecimal("65.0")));
        plan.add(new SalaryPayment(b, "president", new BigDecimal("75.0")));

        int paid = service(cfg(true, AMOUNTS), true).payout(plan);
        assertThat(paid).isEqualTo(1); // first threw, second succeeded
        verify(ledgerService, org.mockito.Mockito.times(2)).transferChecked(any());
        // Only the player whose transfer succeeded is notified.
        verify(notifier, never()).notifySalaryPaid(org.mockito.ArgumentMatchers.eq(a), any());
        verify(notifier).notifySalaryPaid(org.mockito.ArgumentMatchers.eq(b), any());
    }
}
