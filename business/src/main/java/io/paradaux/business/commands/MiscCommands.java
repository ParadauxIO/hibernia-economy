package io.paradaux.business.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmPlayer;
import io.paradaux.business.services.*;
import io.paradaux.business.commands.resolvers.FirmName;
import io.paradaux.business.commands.resolvers.OnlineFirmName;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.TransactionEntry;
import org.bukkit.entity.Player;

import java.math.BigDecimal;

@Singleton
@Command({"db", "democracybusiness", "business", "firm", "company"})
public class MiscCommands implements CommandHandler {

    private static final int TX_PAGE_SIZE = 10;

    private final FirmService firms;
    private final FirmStaffService staff;
    private final FirmRoleService roles;
    private final FirmTransactionService audit;
    private final TreasuryApi treasury;
    private final Message message;
    private final FirmNotificationService notifications;

    @Inject
    public MiscCommands(FirmService firms, FirmStaffService staff, FirmRoleService roles,
                        FirmTransactionService audit,
                        TreasuryApi treasury, Message message,
                        FirmNotificationService notifications) {
        this.firms = firms;
        this.staff = staff;
        this.roles = roles;
        this.audit = audit;
        this.treasury = treasury;
        this.message = message;
        this.notifications = notifications;
    }

    // ---- BALANCE ----------------------------------------------------------------

    @Route("balance <firm>")
    @Permission("business.finance")
    @Async
    @Description("Show the balance of a firm's treasury account")
    public void balance(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        String firm = firmRef.value();
        Firm f = firms.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }

        String balance = audit.getFormattedAggregateBalance(f.getFirmId());
        message.send(sender, "business.finance.balance", "firm", f.getDisplayName(), "balance", balance);
    }

    // ---- DEPOSIT ----------------------------------------------------------------

    @Route("deposit <firm> <amount>")
    @Permission("business.finance")
    @Async
    @Description("Deposit from your personal account into a firm's treasury account")
    public void deposit(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("amount") BigDecimal amount) {
        doDeposit(sender, firmRef, amount, null);
    }

    @Route("deposit <firm> <amount> <memo>")
    @Permission("business.finance")
    @Async
    @Description("Deposit into a firm's treasury with a memo recorded on the transaction")
    public void deposit(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("amount") BigDecimal amount,
                        @GreedyArg("memo") String memo) {
        doDeposit(sender, firmRef, amount, memo);
    }

    private void doDeposit(Player sender, FirmName firmRef, BigDecimal amount, String memo) {
        String firm = firmRef.value();
        Firm f = firms.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }

        if (!canAccessFirmFinances(f, sender)) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        // Service throws framework semantic exceptions (invalid-amount, insufficient
        // funds, no-permission, no-account) that the framework's ErrorRenderer resolves
        // to the player's locale via the key each carries — no catch-and-hand-format
        // here (plugin-architecture/0002).
        audit.deposit(f.getFirmId(), sender.getUniqueId(), amount, memo);
        String formatted = treasury.formatAmount(amount);
        message.send(sender, "business.finance.deposit.success", "firm", f.getDisplayName(), "amount", formatted);
    }

    // ---- WITHDRAW ---------------------------------------------------------------

    @Route("withdraw <firm> <amount>")
    @Permission("business.finance")
    @Async
    @Description("Withdraw from a firm's treasury account into your personal account")
    public void withdraw(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("amount") BigDecimal amount) {
        String firm = firmRef.value();
        Firm f = firms.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }

        if (!canAccessFirmFinances(f, sender)) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        audit.withdraw(f.getFirmId(), sender.getUniqueId(), amount);
        String formatted = treasury.formatAmount(amount);
        message.send(sender, "business.finance.withdraw.success", "firm", f.getDisplayName(), "amount", formatted);
    }

    // ---- PAY: PLAYER -> BUSINESS ------------------------------------------------

    @Route("pay into <firm> <amount>")
    @Permission("business.pay")
    @Async
    @Description("Pay money from your personal account into a business")
    public void payInto(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("amount") BigDecimal amount) {
        String firm = firmRef.value();
        Firm f = firms.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }

        audit.payIntoFirm(f.getFirmId(), sender.getUniqueId(), amount);
        String formatted = treasury.formatAmount(amount);
        message.send(sender, "business.finance.pay.into.success", "firm", f.getDisplayName(), "amount", formatted);
        notifications.notifyFirmExcept(f.getFirmId(), sender.getUniqueId(), "business.notify.transfer.incoming",
                "firm", f.getDisplayName(), "amount", formatted, "sender", sender.getName());
    }

    // ---- PAY: BUSINESS -> PLAYER ------------------------------------------------

    @Route("pay player <firm> <player> <amount>")
    @Permission("business.finance")
    @Async
    @Description("Pay money from a business to a player")
    public void payPlayer(@Sender Player sender, @Arg("firm") FirmName firmRef,
                          @Arg("player") FirmPlayer target, @Arg("amount") BigDecimal amount) {
        String firm = firmRef.value();
        Firm f = firms.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }

        if (!canAccessFirmFinances(f, sender)) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        audit.payPlayer(f.getFirmId(), target.getUniqueId(), sender.getUniqueId(), amount);
        String formatted = treasury.formatAmount(amount);
        message.send(sender, "business.finance.pay.player.success",
                "firm", f.getDisplayName(), "player", target.getCurrentName(), "amount", formatted);
    }

    // ---- PAY: BUSINESS -> BUSINESS ----------------------------------------------

    @Route("pay business <firm> <target> <amount>")
    @Permission("business.finance")
    @Async
    @Description("Pay money from one business to another")
    public void payBusiness(@Sender Player sender, @Arg("firm") FirmName firmRef,
                            @Arg("target") OnlineFirmName targetRef, @Arg("amount") BigDecimal amount) {
        String firm = firmRef.value();
        String targetFirm = targetRef.value();
        Firm f = firms.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }

        Firm targetF = firms.getFirmByNameOrId(targetFirm);
        if (targetF == null) {
            message.send(sender, "business.firm.not-found", "firm", targetFirm);
            return;
        }

        if (targetF.getFirmId().equals(f.getFirmId())) {
            message.send(sender, "business.finance.pay.same-firm");
            return;
        }

        if (!canAccessFirmFinances(f, sender)) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        audit.payFirm(f.getFirmId(), targetF.getFirmId(), sender.getUniqueId(), amount);
        String formatted = treasury.formatAmount(amount);
        message.send(sender, "business.finance.pay.business.success",
                "firm", f.getDisplayName(), "target", targetF.getDisplayName(), "amount", formatted);
        notifications.notifyFirmExcept(targetF.getFirmId(), sender.getUniqueId(), "business.notify.transfer.incoming",
                "firm", targetF.getDisplayName(), "amount", formatted, "sender", f.getDisplayName());
    }

    // ---- SEND: BUSINESS -> BUSINESS (multi-firm operator) -----------------------

    @Route("send")
    @Permission("business.finance")
    @Async
    @Description("List your firms and their balances to drive a /firm send")
    public void sendList(@Sender Player sender) {
        var myFirms = firms.listOwnedOrMemberFirms(sender.getUniqueId());
        if (myFirms.isEmpty()) {
            message.send(sender, "business.finance.send.no-firms");
            return;
        }
        message.send(sender, "business.finance.send.list-header");
        for (Firm f : myFirms) {
            String balance = audit.getFormattedAggregateBalance(f.getFirmId());
            message.send(sender, "business.finance.send.list-line", "firm", f.getDisplayName(), "balance", balance);
        }
        message.send(sender, "business.finance.send.list-usage");
    }

    @Route("send <source> <target> <amount>")
    @Permission("business.finance")
    @Async
    @Description("Send money from one of your firms to another firm")
    public void send(@Sender Player sender, @Arg("source") FirmName sourceRef,
                     @Arg("target") OnlineFirmName targetRef, @Arg("amount") BigDecimal amount) {
        doSend(sender, sourceRef.value(), targetRef.value(), amount, null);
    }

    @Route("send <source> <target> <amount> <memo>")
    @Permission("business.finance")
    @Async
    @Description("Send money between firms with a memo recorded on the transaction")
    public void send(@Sender Player sender, @Arg("source") FirmName sourceRef,
                     @Arg("target") OnlineFirmName targetRef, @Arg("amount") BigDecimal amount,
                     @GreedyArg("memo") String memo) {
        doSend(sender, sourceRef.value(), targetRef.value(), amount, memo);
    }

    private void doSend(Player sender, String sourceName, String targetName, BigDecimal amount, String memo) {
        Firm source = firms.getFirmByNameOrId(sourceName);
        if (source == null) {
            message.send(sender, "business.firm.not-found", "firm", sourceName);
            return;
        }

        Firm target = firms.getFirmByNameOrId(targetName);
        if (target == null) {
            message.send(sender, "business.firm.not-found", "firm", targetName);
            return;
        }

        if (target.getFirmId().equals(source.getFirmId())) {
            message.send(sender, "business.finance.pay.same-firm");
            return;
        }

        if (!canAccessFirmFinances(source, sender)) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        audit.payFirm(source.getFirmId(), target.getFirmId(), sender.getUniqueId(), amount, memo);
        String formatted = treasury.formatAmount(amount);
        message.send(sender, "business.finance.send.success",
                "source", source.getDisplayName(), "target", target.getDisplayName(), "amount", formatted);
        notifications.notifyFirmExcept(target.getFirmId(), sender.getUniqueId(), "business.notify.transfer.incoming",
                "firm", target.getDisplayName(), "amount", formatted, "sender", source.getDisplayName());
    }

    // ---- TRANSACTIONS -----------------------------------------------------------

    @Route("transactions <firm>")
    @Permission("business.finance")
    @Async
    @Description("Show firm transaction history (page 1)")
    public void transactions(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        transactions(sender, firmRef, 1);
    }

    @Route("transactions <firm> <page>")
    @Permission("business.finance")
    @Async
    @Description("Show firm transaction history")
    public void transactions(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("page") Integer page) {
        String firm = firmRef.value();
        Firm f = firms.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }

        if (!canAccessFirmFinances(f, sender)) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        Page<TransactionEntry> txPage = audit.getAggregateTransactions(f.getFirmId(), page, TX_PAGE_SIZE);

        if (txPage.items().isEmpty()) {
            message.send(sender, "business.finance.transactions.empty", "firm", f.getDisplayName());
            return;
        }

        message.send(sender, "business.finance.transactions.header",
                "firm", f.getDisplayName(), "page", txPage.pageNumber(), "totalPages", txPage.totalPages());

        CommandSupport.renderTransactionLines(message, treasury, sender, txPage,
                "business.finance.transactions.line");

        if (txPage.hasMore()) {
            message.send(sender, "business.finance.transactions.next-page",
                    "firm", f.getDisplayName(), "nextPage", txPage.pageNumber() + 1);
        }
    }

    private boolean canAccessFirmFinances(Firm f, Player player) {
        return CommandSupport.canAccessFirmFinances(firms, staff, f.getFirmId(), player.getUniqueId());
    }
}
