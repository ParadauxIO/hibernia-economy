package io.paradaux.business.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.paradaux.business.commands.resolvers.FirmName;
import io.paradaux.business.internal.model.Firm;
import io.paradaux.business.internal.model.FirmPlayer;
import io.paradaux.business.internal.model.directdebit.DirectDebitKind;
import io.paradaux.business.internal.model.directdebit.DirectDebitMandate;
import io.paradaux.business.internal.model.directdebit.DirectDebitPeriod;
import io.paradaux.business.internal.model.directdebit.DirectDebitStatus;
import io.paradaux.business.services.DirectDebitService;
import io.paradaux.business.services.FirmPlayerService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.business.threading.BusinessMessages;
import io.paradaux.treasury.api.TreasuryApi;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DirectDebitCommandsTest {

    private final DirectDebitService debits = mock(DirectDebitService.class);
    private final FirmService firms = mock(FirmService.class);
    private final FirmStaffService staff = mock(FirmStaffService.class);
    private final FirmPlayerService players = mock(FirmPlayerService.class);
    private final TreasuryApi treasury = mock(TreasuryApi.class);
    private final BusinessMessages message = mock(BusinessMessages.class);
    private final Player sender = mock(Player.class);
    private final UUID senderId = UUID.randomUUID();
    private final UUID payerId = UUID.randomUUID();
    private final DirectDebitCommands commands =
            new DirectDebitCommands(debits, firms, players, new FirmGate(firms, staff, message), treasury, message);

    private final Firm firm = new Firm();

    @BeforeEach
    void setUp() {
        when(sender.getUniqueId()).thenReturn(senderId);
        firm.setFirmId(12);
        firm.setDisplayName("Globex");
        when(treasury.formatAmount(any())).thenAnswer(i -> "$" + ((BigDecimal) i.getArgument(0)).toPlainString());
        when(debits.isEnabled()).thenReturn(true);
    }

    private void asFinanceUser() {
        when(firms.getFirmByNameOrId("Globex")).thenReturn(firm);
        when(firms.isProprietor(12, senderId)).thenReturn(true);
    }

    private FirmPlayer payer() {
        FirmPlayer p = new FirmPlayer();
        p.setPlayerUuid(payerId.toString());
        p.setCurrentName("Customer");
        return p;
    }

    private DirectDebitMandate mandate(long id, DirectDebitKind kind, DirectDebitPeriod period) {
        DirectDebitMandate m = new DirectDebitMandate();
        m.setMandateId(id);
        m.setFirmId(12);
        m.setPayerUuid(payerId.toString());
        m.setKind(kind);
        m.setPeriod(period);
        m.setCap(new BigDecimal("1000.00"));
        m.setAmount(kind == DirectDebitKind.RECURRING ? new BigDecimal("500.00") : null);
        m.setReference("Rent");
        m.setStatus(DirectDebitStatus.ACTIVE);
        return m;
    }

    // ---- flag ------------------------------------------------------------------------

    @Test
    void everyRouteAnswersDisabledAndTouchesNothingWhileTheFlagIsOff() {
        when(debits.isEnabled()).thenReturn(false);
        FirmName name = new FirmName("Globex");

        commands.proposeRecurring(sender, name, payer(), "weekly", BigDecimal.TEN, "Rent");
        commands.proposeOnDemand(sender, name, payer(), "weekly", BigDecimal.TEN, "Rent");
        commands.charge(sender, name, 1L, BigDecimal.TEN);
        commands.charge(sender, name, 1L, BigDecimal.TEN, "memo");
        commands.cancel(sender, name, 1L);
        commands.listForFirm(sender, name);
        commands.listMine(sender);
        commands.accept(sender, 1L);
        commands.reject(sender, 1L);
        commands.revoke(sender, 1L);
        commands.resume(sender, 1L);

        verify(message, org.mockito.Mockito.times(11)).send(sender, "business.debit.disabled");
        verifyNoMoreInteractions(message);
        verify(debits, org.mockito.Mockito.times(11)).isEnabled();
        verifyNoMoreInteractions(debits);
        verifyNoInteractions(firms, staff, players);
    }

    // ---- propose ---------------------------------------------------------------------

    @Test
    void proposeRejectsAnUnknownPeriodBeforeResolvingTheFirm() {
        commands.proposeRecurring(sender, new FirmName("Globex"), payer(), "fortnightly", BigDecimal.TEN, "Rent");

        verify(message).send(sender, "business.debit.propose.invalid-period", "period", "fortnightly");
        verifyNoInteractions(firms);
        verify(debits, never()).propose(anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void proposeDoesNothingWhenTheFirmGateRefuses() {
        when(firms.getFirmByNameOrId("Globex")).thenReturn(null);

        commands.proposeOnDemand(sender, new FirmName("Globex"), payer(), "daily", BigDecimal.TEN, "Rent");

        verify(message).send(sender, "business.firm.not-found", "firm", "Globex");
        verify(debits, never()).propose(anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void proposeRecurringTellsTheSenderAndThePayerWithRecurringTerms() {
        asFinanceUser();
        DirectDebitMandate m = mandate(7, DirectDebitKind.RECURRING, DirectDebitPeriod.WEEKLY);
        when(debits.propose(
                        12,
                        senderId,
                        payerId,
                        DirectDebitKind.RECURRING,
                        DirectDebitPeriod.WEEKLY,
                        new BigDecimal("500.00"),
                        "Rent"))
                .thenReturn(m);

        commands.proposeRecurring(
                sender, new FirmName("Globex"), payer(), " Weekly ", new BigDecimal("500.00"), "Rent");

        verify(message)
                .send(sender, "business.debit.propose.sent", "id", 7L, "player", "Customer", "terms", "$500.00 weekly");
        verify(message)
                .send(
                        payerId,
                        "business.debit.propose.received",
                        "id",
                        7L,
                        "firm",
                        "Globex",
                        "terms",
                        "$500.00 weekly",
                        "reference",
                        "Rent");
    }

    @Test
    void proposeOnDemandRendersTheCapInTheTerms() {
        asFinanceUser();
        DirectDebitMandate m = mandate(8, DirectDebitKind.ON_DEMAND, DirectDebitPeriod.MONTHLY);
        when(debits.propose(
                        12,
                        senderId,
                        payerId,
                        DirectDebitKind.ON_DEMAND,
                        DirectDebitPeriod.MONTHLY,
                        new BigDecimal("1000.00"),
                        "Rent"))
                .thenReturn(m);

        commands.proposeOnDemand(sender, new FirmName("Globex"), payer(), "monthly", new BigDecimal("1000.00"), "Rent");

        String terms = "up to $1000.00 monthly, on demand";
        verify(message).send(sender, "business.debit.propose.sent", "id", 8L, "player", "Customer", "terms", terms);
        verify(message)
                .send(
                        payerId,
                        "business.debit.propose.received",
                        "id",
                        8L,
                        "firm",
                        "Globex",
                        "terms",
                        terms,
                        "reference",
                        "Rent");
    }

    // ---- charge / cancel -------------------------------------------------------------

    @Test
    void chargeWithoutMemoPassesNullMemoAndReportsAmountAndRemaining() {
        asFinanceUser();
        when(debits.charge(12, 5L, senderId, BigDecimal.TEN, null))
                .thenReturn(new DirectDebitService.Charge(1L, 2L, BigDecimal.TEN, new BigDecimal("90")));

        commands.charge(sender, new FirmName("Globex"), 5L, BigDecimal.TEN);

        verify(message).send(sender, "business.debit.charge.success", "id", 5L, "amount", "$10", "remaining", "$90");
    }

    @Test
    void chargeWithMemoForwardsTheMemo() {
        asFinanceUser();
        when(debits.charge(12, 5L, senderId, BigDecimal.TEN, "March"))
                .thenReturn(new DirectDebitService.Charge(1L, 2L, BigDecimal.TEN, BigDecimal.ZERO));

        commands.charge(sender, new FirmName("Globex"), 5L, BigDecimal.TEN, "March");

        verify(debits).charge(12, 5L, senderId, BigDecimal.TEN, "March");
        verify(message).send(sender, "business.debit.charge.success", "id", 5L, "amount", "$10", "remaining", "$0");
    }

    @Test
    void chargeAndCancelDoNothingWhenTheGateRefuses() {
        when(firms.getFirmByNameOrId("Globex")).thenReturn(firm);
        // not a proprietor, no staff permission

        commands.charge(sender, new FirmName("Globex"), 5L, BigDecimal.TEN);
        commands.charge(sender, new FirmName("Globex"), 5L, BigDecimal.TEN, "memo");
        commands.cancel(sender, new FirmName("Globex"), 5L);
        commands.listForFirm(sender, new FirmName("Globex"));

        verify(message, org.mockito.Mockito.times(4)).send(sender, "business.general.no-permission");
        verify(debits, never()).charge(anyInt(), anyLong(), any(), any(), any());
        verify(debits, never()).cancelByFirm(anyInt(), anyLong(), any());
        verify(debits, never()).listForFirm(anyInt());
    }

    @Test
    void cancelCancelsAndConfirms() {
        asFinanceUser();

        commands.cancel(sender, new FirmName("Globex"), 5L);

        verify(debits).cancelByFirm(12, 5L, senderId);
        verify(message).send(sender, "business.debit.cancelled", "id", 5L);
    }

    // ---- lists -----------------------------------------------------------------------

    @Test
    void firmListSaysEmptyWhenThereAreNoMandates() {
        asFinanceUser();
        when(debits.listForFirm(12)).thenReturn(List.of());

        commands.listForFirm(sender, new FirmName("Globex"));

        verify(message).send(sender, "business.debit.list.empty");
        verify(message, never()).send(eq(sender), eq("business.debit.list.firm-header"), any(Object[].class));
    }

    @Test
    void firmListShowsHeaderThenOneLinePerMandateWithPlayerNameOrUuidFallback() {
        asFinanceUser();
        UUID stranger = UUID.randomUUID();
        DirectDebitMandate known = mandate(1, DirectDebitKind.RECURRING, DirectDebitPeriod.DAILY);
        DirectDebitMandate unknown = mandate(2, DirectDebitKind.ON_DEMAND, DirectDebitPeriod.MONTHLY);
        unknown.setPayerUuid(stranger.toString());
        unknown.setStatus(DirectDebitStatus.PENDING);
        when(debits.listForFirm(12)).thenReturn(List.of(known, unknown));
        when(players.findByUuid(payerId)).thenReturn(Optional.of(payer()));
        when(players.findByUuid(stranger)).thenReturn(Optional.empty());

        commands.listForFirm(sender, new FirmName("Globex"));

        verify(message).send(sender, "business.debit.list.firm-header", "firm", "Globex");
        verify(message)
                .send(
                        sender,
                        "business.debit.list.firm-line",
                        "id",
                        1L,
                        "status",
                        "active",
                        "player",
                        "Customer",
                        "terms",
                        "$500.00 daily",
                        "reference",
                        "Rent");
        verify(message)
                .send(
                        sender,
                        "business.debit.list.firm-line",
                        "id",
                        2L,
                        "status",
                        "pending",
                        "player",
                        stranger.toString(),
                        "terms",
                        "up to $1000.00 monthly, on demand",
                        "reference",
                        "Rent");
    }

    @Test
    void customerListSaysEmptyWhenThereAreNoMandates() {
        when(debits.listForPayer(senderId)).thenReturn(List.of());

        commands.listMine(sender);

        verify(message).send(sender, "business.debit.list.empty");
        verify(message, never()).send(eq(sender), eq("business.debit.list.payer-header"), any(Object[].class));
    }

    @Test
    void customerListFallsBackToHashIdWhenTheFirmIsGone() {
        DirectDebitMandate live = mandate(1, DirectDebitKind.RECURRING, DirectDebitPeriod.WEEKLY);
        DirectDebitMandate gone = mandate(2, DirectDebitKind.RECURRING, DirectDebitPeriod.WEEKLY);
        gone.setFirmId(99);
        gone.setStatus(DirectDebitStatus.SUSPENDED);
        when(debits.listForPayer(senderId)).thenReturn(List.of(live, gone));
        when(firms.getAnyFirmById(12)).thenReturn(firm);
        when(firms.getAnyFirmById(99)).thenReturn(null);

        commands.listMine(sender);

        verify(message).send(sender, "business.debit.list.payer-header");
        verify(message)
                .send(
                        sender,
                        "business.debit.list.payer-line",
                        "id",
                        1L,
                        "status",
                        "active",
                        "firm",
                        "Globex",
                        "terms",
                        "$500.00 weekly",
                        "reference",
                        "Rent");
        verify(message)
                .send(
                        sender,
                        "business.debit.list.payer-line",
                        "id",
                        2L,
                        "status",
                        "suspended",
                        "firm",
                        "#99",
                        "terms",
                        "$500.00 weekly",
                        "reference",
                        "Rent");
    }

    // ---- customer actions --------------------------------------------------------------

    @Test
    void acceptShowsTheAcceptedTerms() {
        when(debits.accept(3L, senderId)).thenReturn(mandate(3, DirectDebitKind.ON_DEMAND, DirectDebitPeriod.DAILY));

        commands.accept(sender, 3L);

        verify(message).send(sender, "business.debit.accepted", "id", 3L, "terms", "up to $1000.00 daily, on demand");
    }

    @Test
    void rejectRevokeAndResumeCallTheMatchingServiceMethodAndConfirm() {
        commands.reject(sender, 3L);
        commands.revoke(sender, 4L);
        commands.resume(sender, 5L);

        verify(debits).reject(3L, senderId);
        verify(debits).revoke(4L, senderId);
        verify(debits).resume(5L, senderId);
        verify(message).send(sender, "business.debit.rejected", "id", 3L);
        verify(message).send(sender, "business.debit.cancelled", "id", 4L);
        verify(message).send(sender, "business.debit.resumed", "id", 5L);
    }

    @Test
    void routesCarryTheDocumentedPermissions() throws Exception {
        assertThat(commands.getClass()
                        .getMethod("accept", Player.class, Long.class)
                        .getAnnotation(io.paradaux.hibernia.framework.commander.annotations.Permission.class)
                        .value())
                .isEqualTo("business.pay");
        assertThat(commands.getClass()
                        .getMethod("cancel", Player.class, FirmName.class, Long.class)
                        .getAnnotation(io.paradaux.hibernia.framework.commander.annotations.Permission.class)
                        .value())
                .isEqualTo("business.finance");
    }
}
