package io.paradaux.business.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.commands.resolvers.FirmName;
import io.paradaux.business.model.directdebit.DirectDebitKind;
import io.paradaux.business.model.directdebit.DirectDebitMandate;
import io.paradaux.business.model.directdebit.DirectDebitPeriod;
import io.paradaux.business.model.internal.Firm;
import io.paradaux.business.model.internal.FirmPlayer;
import io.paradaux.business.services.DirectDebitService;
import io.paradaux.business.services.FirmPlayerService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.threading.BusinessMessages;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.treasury.api.TreasuryApi;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Direct debits (PAR-557). Firm-side commands need firm-finance access; the
 * customer-side ones act only on the sender's own mandates. Every command answers
 * {@code business.debit.disabled} while the release flag is off.
 */
@Singleton
@Command({"db", "democracybusiness", "business", "firm", "company"})
public class DirectDebitCommands implements CommandHandler {

    private final DirectDebitService debits;
    private final FirmService firms;
    private final FirmPlayerService players;
    private final FirmGate firmGate;
    private final TreasuryApi treasury;
    private final BusinessMessages message;

    @Inject
    public DirectDebitCommands(
            DirectDebitService debits,
            FirmService firms,
            FirmPlayerService players,
            FirmGate firmGate,
            TreasuryApi treasury,
            BusinessMessages message) {
        this.debits = debits;
        this.firms = firms;
        this.players = players;
        this.firmGate = firmGate;
        this.treasury = treasury;
        this.message = message;
    }

    // ---- FIRM SIDE ----------------------------------------------------------------

    @Route("debit propose recurring <firm> <player> <period> <amount> <reference>")
    @Permission("business.finance")
    @Async
    @Description("Ask a customer to approve a fixed charge every day, week or month")
    public void proposeRecurring(
            @Sender Player sender,
            @Arg("firm") FirmName firmRef,
            @Arg("player") FirmPlayer payer,
            @Arg("period") String period,
            @Arg("amount") BigDecimal amount,
            @GreedyArg("reference") String reference) {
        propose(sender, firmRef, payer, DirectDebitKind.RECURRING, period, amount, reference);
    }

    @Route("debit propose ondemand <firm> <player> <period> <cap> <reference>")
    @Permission("business.finance")
    @Async
    @Description("Ask a customer to approve charges up to a cap per day, week or month")
    public void proposeOnDemand(
            @Sender Player sender,
            @Arg("firm") FirmName firmRef,
            @Arg("player") FirmPlayer payer,
            @Arg("period") String period,
            @Arg("cap") BigDecimal cap,
            @GreedyArg("reference") String reference) {
        propose(sender, firmRef, payer, DirectDebitKind.ON_DEMAND, period, cap, reference);
    }

    private void propose(
            Player sender,
            FirmName firmRef,
            FirmPlayer payer,
            DirectDebitKind kind,
            String rawPeriod,
            BigDecimal limit,
            String reference) {
        if (!requireEnabled(sender)) return;
        DirectDebitPeriod period = DirectDebitPeriod.parse(rawPeriod).orElse(null);
        if (period == null) {
            message.send(sender, "business.debit.propose.invalid-period", "period", rawPeriod);
            return;
        }
        var resolved = firmGate.resolveNamedForFinances(sender, firmRef.value());
        if (resolved.isEmpty()) return;
        Firm firm = resolved.get();

        DirectDebitMandate mandate = debits.propose(
                firm.getFirmId(), sender.getUniqueId(), payer.getUniqueId(), kind, period, limit, reference);
        message.send(
                sender,
                "business.debit.propose.sent",
                "id",
                mandate.getMandateId(),
                "player",
                payer.getCurrentName(),
                "terms",
                terms(mandate));
        message.send(
                payer.getUniqueId(),
                "business.debit.propose.received",
                "id",
                mandate.getMandateId(),
                "firm",
                firm.getDisplayName(),
                "terms",
                terms(mandate),
                "reference",
                mandate.getReference());
    }

    @Route("debit charge <firm> <id> <amount>")
    @Permission("business.finance")
    @Async
    @Description("Charge a customer under an on-demand direct debit")
    public void charge(
            @Sender Player sender,
            @Arg("firm") FirmName firmRef,
            @Arg("id") Long id,
            @Arg("amount") BigDecimal amount) {
        charge(sender, firmRef, id, amount, null);
    }

    @Route("debit charge <firm> <id> <amount> <memo>")
    @Permission("business.finance")
    @Async
    @Description("Charge a customer under an on-demand direct debit, with a memo")
    public void charge(
            @Sender Player sender,
            @Arg("firm") FirmName firmRef,
            @Arg("id") Long id,
            @Arg("amount") BigDecimal amount,
            @GreedyArg("memo") String memo) {
        if (!requireEnabled(sender)) return;
        var resolved = firmGate.resolveNamedForFinances(sender, firmRef.value());
        if (resolved.isEmpty()) return;

        DirectDebitService.Charge charge =
                debits.charge(resolved.get().getFirmId(), id, sender.getUniqueId(), amount, memo);
        message.send(
                sender,
                "business.debit.charge.success",
                "id",
                id,
                "amount",
                treasury.formatAmount(charge.amount()),
                "remaining",
                treasury.formatAmount(charge.remainingThisPeriod()));
    }

    @Route("debit cancel <firm> <id>")
    @Permission("business.finance")
    @Async
    @Description("Cancel one of your firm's direct debits")
    public void cancel(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("id") Long id) {
        if (!requireEnabled(sender)) return;
        var resolved = firmGate.resolveNamedForFinances(sender, firmRef.value());
        if (resolved.isEmpty()) return;

        debits.cancelByFirm(resolved.get().getFirmId(), id, sender.getUniqueId());
        message.send(sender, "business.debit.cancelled", "id", id);
    }

    @Route("debit list <firm>")
    @Permission("business.finance")
    @Async
    @Description("List your firm's open direct debits")
    public void listForFirm(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        if (!requireEnabled(sender)) return;
        var resolved = firmGate.resolveNamedForFinances(sender, firmRef.value());
        if (resolved.isEmpty()) return;
        Firm firm = resolved.get();

        List<DirectDebitMandate> mandates = debits.listForFirm(firm.getFirmId());
        if (mandates.isEmpty()) {
            message.send(sender, "business.debit.list.empty");
            return;
        }
        message.send(sender, "business.debit.list.firm-header", "firm", firm.getDisplayName());
        for (DirectDebitMandate m : mandates) {
            message.send(
                    sender,
                    "business.debit.list.firm-line",
                    "id",
                    m.getMandateId(),
                    "status",
                    status(m),
                    "player",
                    playerName(m.getPayerUuid()),
                    "terms",
                    terms(m),
                    "reference",
                    m.getReference());
        }
    }

    // ---- CUSTOMER SIDE --------------------------------------------------------------

    @Route("debits")
    @Permission("business.pay")
    @Async
    @Description("List the direct debits you have approved or been asked to approve")
    public void listMine(@Sender Player sender) {
        if (!requireEnabled(sender)) return;
        List<DirectDebitMandate> mandates = debits.listForPayer(sender.getUniqueId());
        if (mandates.isEmpty()) {
            message.send(sender, "business.debit.list.empty");
            return;
        }
        message.send(sender, "business.debit.list.payer-header");
        for (DirectDebitMandate m : mandates) {
            Firm firm = firms.getAnyFirmById(m.getFirmId());
            message.send(
                    sender,
                    "business.debit.list.payer-line",
                    "id",
                    m.getMandateId(),
                    "status",
                    status(m),
                    "firm",
                    firm == null ? "#" + m.getFirmId() : firm.getDisplayName(),
                    "terms",
                    terms(m),
                    "reference",
                    m.getReference());
        }
    }

    @Route("debit accept <id>")
    @Permission("business.pay")
    @Async
    @Description("Approve a direct debit a firm asked you for")
    public void accept(@Sender Player sender, @Arg("id") Long id) {
        if (!requireEnabled(sender)) return;
        DirectDebitMandate mandate = debits.accept(id, sender.getUniqueId());
        message.send(sender, "business.debit.accepted", "id", id, "terms", terms(mandate));
    }

    @Route("debit reject <id>")
    @Permission("business.pay")
    @Async
    @Description("Decline a direct debit a firm asked you for")
    public void reject(@Sender Player sender, @Arg("id") Long id) {
        if (!requireEnabled(sender)) return;
        debits.reject(id, sender.getUniqueId());
        message.send(sender, "business.debit.rejected", "id", id);
    }

    @Route("debit revoke <id>")
    @Permission("business.pay")
    @Async
    @Description("Cancel a direct debit you approved")
    public void revoke(@Sender Player sender, @Arg("id") Long id) {
        if (!requireEnabled(sender)) return;
        debits.revoke(id, sender.getUniqueId());
        message.send(sender, "business.debit.cancelled", "id", id);
    }

    @Route("debit resume <id>")
    @Permission("business.pay")
    @Async
    @Description("Resume a direct debit suspended after missed payments")
    public void resume(@Sender Player sender, @Arg("id") Long id) {
        if (!requireEnabled(sender)) return;
        debits.resume(id, sender.getUniqueId());
        message.send(sender, "business.debit.resumed", "id", id);
    }

    // ---- Rendering --------------------------------------------------------------------

    private boolean requireEnabled(Player sender) {
        if (debits.isEnabled()) return true;
        message.send(sender, "business.debit.disabled");
        return false;
    }

    /** e.g. "500.00 every week" or "up to 1,000.00 per month, charged on demand". */
    private String terms(DirectDebitMandate m) {
        String period = m.getPeriod().name().toLowerCase(Locale.ROOT);
        return m.getKind() == DirectDebitKind.RECURRING
                ? treasury.formatAmount(m.getAmount()) + " " + period
                : "up to " + treasury.formatAmount(m.getCap()) + " " + period + ", on demand";
    }

    private String playerName(String uuid) {
        return players.findByUuid(UUID.fromString(uuid))
                .map(FirmPlayer::getCurrentName)
                .orElse(uuid);
    }

    private static String status(DirectDebitMandate m) {
        return m.getStatus().name().toLowerCase(Locale.ROOT);
    }
}
