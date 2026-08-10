package io.paradaux.business.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmPlayer;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmNotificationService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.business.services.FirmPlayerService;
import io.paradaux.business.services.FirmTransactionService;
import io.paradaux.business.commands.resolvers.FirmName;
import io.paradaux.business.commands.resolvers.OnlineFirmName;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountMember;
import io.paradaux.treasury.model.economy.TransactionEntry;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.List;

@Singleton
@Command({"db", "democracybusiness", "business", "firm", "company"})
public class AccountCommands implements CommandHandler {

    private static final int TX_PAGE_SIZE = 10;

    private final FirmAccountService accounts;
    private final FirmService firms;
    private final FirmStaffService staff;
    private final FirmTransactionService audit;
    private final TreasuryApi treasury;
    private final Message message;
    private final FirmNotificationService notifications;
    private final FirmPlayerService firmPlayers;

    @Inject
    public AccountCommands(FirmAccountService accounts, FirmService firms, FirmStaffService staff,
                           FirmTransactionService audit, TreasuryApi treasury, Message message,
                           FirmNotificationService notifications, FirmPlayerService firmPlayers) {
        this.accounts = accounts;
        this.firms = firms;
        this.staff = staff;
        this.audit = audit;
        this.treasury = treasury;
        this.message = message;
        this.notifications = notifications;
        this.firmPlayers = firmPlayers;
    }

    /**
     * Resolve a player's display name from the DB-backed FirmPlayer cache rather
     * than {@code Bukkit.getOfflinePlayer(uuid).getName()}, which blocks on the
     * Mojang API / usercache off the main thread and can return null (ADT-70).
     * Falls back to the raw UUID if the player isn't in the cache.
     */
    private String resolveName(java.util.UUID uuid) {
        return firmPlayers.findByUuid(uuid)
                .map(FirmPlayer::getCurrentName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(uuid.toString());
    }

    @Route("account create <firm> <name>")
    @Permission("business.account.create")
    @Async
    @Description("Create a new Treasury account for your firm")
    public void createAccount(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("name") String accountName) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        Account account = accounts.createAccount(firm.getFirmId(), accountName, sender.getUniqueId());
        message.send(sender, "business.account.create.success",
                "firm", firm.getDisplayName(),
                "account", account.getDisplayName(),
                "accountId", account.getAccountId());
    }

    @Route("account list <firm>")
    @Permission("business.account.list")
    @Async
    @Description("List all Treasury accounts for a firm")
    public void listAccounts(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        List<Account> accountList = accounts.listAccounts(firm.getFirmId());

        if (accountList.isEmpty()) {
            message.send(sender, "business.account.list.empty", "firm", firm.getDisplayName());
            return;
        }

        message.send(sender, "business.account.list.header", "firm", firm.getDisplayName(), "count", accountList.size());
        for (Account account : accountList) {
            String defaultMarker = (firm.getDefaultAccountId() != null && firm.getDefaultAccountId().equals(account.getAccountId())) ? " [DEFAULT]" : "";
            String shopId = "B:" + Integer.toString(account.getAccountId(), 36).toUpperCase();
            message.send(sender, "business.account.list.entry",
                    "accountId", account.getAccountId(),
                    "name", account.getDisplayName(),
                    "archived", account.isArchived() ? " [ARCHIVED]" : "",
                    "default", defaultMarker,
                    "shopId", shopId);
        }
    }

    @Route("account setdefault <firm> <accountId>")
    @Permission("business.account.setdefault")
    @Async
    @Description("Set the default Treasury account for a firm")
    public void setDefaultAccount(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("accountId") Integer accountId) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        accounts.setDefaultAccount(firm.getFirmId(), accountId, sender.getUniqueId());
        message.send(sender, "business.account.setdefault.success",
                "firm", firm.getDisplayName(),
                "accountId", accountId);
    }

    @Route("account archive <firm> <accountId>")
    @Permission("business.account.archive")
    @Async
    @Description("Archive a Treasury account for a firm")
    public void archiveAccount(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("accountId") Integer accountId) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        accounts.archiveAccount(firm.getFirmId(), accountId, sender.getUniqueId());
        message.send(sender, "business.account.archive.success",
                "firm", firm.getDisplayName(),
                "accountId", accountId);
    }

    @Route("account sync <firm> <accountId>")
    @Permission("business.account.sync")
    @Async
    @Description("Manually sync firm staff to a Treasury account based on their roles")
    public void syncAccount(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("accountId") Integer accountId) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        accounts.syncAccountMembers(firm.getFirmId(), accountId);
        message.send(sender, "business.account.sync.success",
                "firm", firm.getDisplayName(),
                "accountId", accountId);
    }

    @Route("account addmember <firm> <accountId> <player>")
    @Permission("business.account.addmember")
    @Async
    @Description("Manually add a member to a firm's Treasury account")
    public void addMember(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("accountId") Integer accountId, @Arg("player") FirmPlayer player) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        accounts.addMemberToAccount(firm.getFirmId(), accountId, player.getUniqueId(), sender.getUniqueId());
        message.send(sender, "business.account.addmember.success",
                "firm", firm.getDisplayName(),
                "accountId", accountId,
                "player", player.getCurrentName());
    }

    @Route("account removemember <firm> <accountId> <player>")
    @Permission("business.account.removemember")
    @Async
    @Description("Manually remove a member from a firm's Treasury account")
    public void removeMember(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("accountId") Integer accountId, @Arg("player") FirmPlayer player) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        accounts.removeMemberFromAccount(firm.getFirmId(), accountId, player.getUniqueId(), sender.getUniqueId());
        message.send(sender, "business.account.removemember.success",
                "firm", firm.getDisplayName(),
                "accountId", accountId,
                "player", player.getCurrentName());
    }

    @Route("account addauthorizer <firm> <accountId> <player>")
    @Permission("business.account.addauthorizer")
    @Async
    @Description("Manually add an authorizer to a firm's Treasury account")
    public void addAuthorizer(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("accountId") Integer accountId, @Arg("player") FirmPlayer player) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        accounts.addAuthorizerToAccount(firm.getFirmId(), accountId, player.getUniqueId(), sender.getUniqueId());
        message.send(sender, "business.account.addauthorizer.success",
                "firm", firm.getDisplayName(),
                "accountId", accountId,
                "player", player.getCurrentName());
    }

    @Route("account removeauthorizer <firm> <accountId> <player>")
    @Permission("business.account.removeauthorizer")
    @Async
    @Description("Manually remove an authorizer from a firm's Treasury account")
    public void removeAuthorizer(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("accountId") Integer accountId, @Arg("player") FirmPlayer player) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        accounts.removeAuthorizerFromAccount(firm.getFirmId(), accountId, player.getUniqueId(), sender.getUniqueId());
        message.send(sender, "business.account.removeauthorizer.success",
                "firm", firm.getDisplayName(),
                "accountId", accountId,
                "player", player.getCurrentName());
    }

    @Route("account members <firm> <accountId>")
    @Permission("business.account.members")
    @Async
    @Description("List all members of a firm's Treasury account")
    public void listMembers(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("accountId") Integer accountId) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        List<AccountMember> members = accounts.getAccountMembers(firm.getFirmId(), accountId);

        if (members.isEmpty()) {
            message.send(sender, "business.account.members.empty",
                    "firm", firm.getDisplayName(),
                    "accountId", accountId);
            return;
        }

        message.send(sender, "business.account.members.header",
                "firm", firm.getDisplayName(),
                "accountId", accountId,
                "count", members.size());

        for (AccountMember member : members) {
            String playerName = resolveName(member.getMemberUuid());
            message.send(sender, "business.account.members.entry",
                    "player", playerName,
                    "uuid", member.getMemberUuid());
        }
    }

    @Route("account authorizers <firm> <accountId>")
    @Permission("business.account.authorizers")
    @Async
    @Description("List all authorizers of a firm's Treasury account")
    public void listAuthorizers(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("accountId") Integer accountId) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        List<AccountMember> authorizers = accounts.getAccountAuthorizers(firm.getFirmId(), accountId);

        if (authorizers.isEmpty()) {
            message.send(sender, "business.account.authorizers.empty",
                    "firm", firm.getDisplayName(),
                    "accountId", accountId);
            return;
        }

        message.send(sender, "business.account.authorizers.header",
                "firm", firm.getDisplayName(),
                "accountId", accountId,
                "count", authorizers.size());

        for (AccountMember authorizer : authorizers) {
            String playerName = resolveName(authorizer.getMemberUuid());
            message.send(sender, "business.account.authorizers.entry",
                    "player", playerName,
                    "uuid", authorizer.getMemberUuid());
        }
    }

    // ---- ACCOUNT BALANCE --------------------------------------------------------

    @Route("account balance <firm> <accountId>")
    @Permission("business.account.finance")
    @Async
    @Description("Show the balance of a specific firm account")
    public void accountBalance(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("accountId") Integer accountId) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        if (!canAccessFirmFinances(firm, sender)) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        String balance = audit.getFormattedAccountBalance(firm.getFirmId(), accountId);
        message.send(sender, "business.account.balance",
                "firm", firm.getDisplayName(), "accountId", accountId, "balance", balance);
    }

    // ---- ACCOUNT DEPOSIT --------------------------------------------------------

    @Route("account deposit <firm> <accountId> <amount>")
    @Permission("business.account.finance")
    @Async
    @Description("Deposit from your personal account into a specific firm account")
    public void accountDeposit(@Sender Player sender, @Arg("firm") FirmName firmRef,
                               @Arg("accountId") Integer accountId, @Arg("amount") BigDecimal amount) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        if (!canAccessFirmFinances(firm, sender)) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        // The service throws framework semantic exceptions that the ErrorRenderer maps
        // to the player's locale via each exception's key (plugin-architecture/0002).
        audit.depositToAccount(firm.getFirmId(), accountId, sender.getUniqueId(), amount);
        String formatted = treasury.formatAmount(amount);
        message.send(sender, "business.account.deposit.success",
                "firm", firm.getDisplayName(), "accountId", accountId, "amount", formatted);
    }

    // ---- ACCOUNT WITHDRAW -------------------------------------------------------

    @Route("account withdraw <firm> <accountId> <amount>")
    @Permission("business.account.finance")
    @Async
    @Description("Withdraw from a specific firm account into your personal account")
    public void accountWithdraw(@Sender Player sender, @Arg("firm") FirmName firmRef,
                                @Arg("accountId") Integer accountId, @Arg("amount") BigDecimal amount) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        if (!canAccessFirmFinances(firm, sender)) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        audit.withdrawFromAccount(firm.getFirmId(), accountId, sender.getUniqueId(), amount);
        String formatted = treasury.formatAmount(amount);
        message.send(sender, "business.account.withdraw.success",
                "firm", firm.getDisplayName(), "accountId", accountId, "amount", formatted);
    }

    // ---- ACCOUNT PAY: PLAYER -> BUSINESS ----------------------------------------

    @Route("account pay into <firm> <accountId> <amount>")
    @Permission("business.pay")
    @Async
    @Description("Pay money from your personal account into a specific firm account")
    public void accountPayInto(@Sender Player sender, @Arg("firm") FirmName firmRef,
                               @Arg("accountId") Integer accountId, @Arg("amount") BigDecimal amount) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        audit.payIntoAccount(firm.getFirmId(), accountId, sender.getUniqueId(), amount);
        String formatted = treasury.formatAmount(amount);
        message.send(sender, "business.account.pay.into.success",
                "firm", firm.getDisplayName(), "accountId", accountId, "amount", formatted);
    }

    // ---- ACCOUNT PAY: BUSINESS -> PLAYER ----------------------------------------

    @Route("account pay player <firm> <accountId> <player> <amount>")
    @Permission("business.account.finance")
    @Async
    @Description("Pay money from a specific firm account to a player")
    public void accountPayPlayer(@Sender Player sender, @Arg("firm") FirmName firmRef,
                                 @Arg("accountId") Integer accountId, @Arg("player") FirmPlayer target,
                                 @Arg("amount") BigDecimal amount) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        if (!canAccessFirmFinances(firm, sender)) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        audit.payPlayerFromAccount(firm.getFirmId(), accountId, target.getUniqueId(), sender.getUniqueId(), amount);
        String formatted = treasury.formatAmount(amount);
        message.send(sender, "business.account.pay.player.success",
                "firm", firm.getDisplayName(), "accountId", accountId,
                "player", target.getCurrentName(), "amount", formatted);
    }

    // ---- ACCOUNT PAY: BUSINESS -> BUSINESS --------------------------------------

    @Route("account pay business <firm> <accountId> <target> <amount>")
    @Permission("business.account.finance")
    @Async
    @Description("Pay money from a specific firm account to another business")
    public void accountPayBusiness(@Sender Player sender, @Arg("firm") FirmName firmRef,
                                   @Arg("accountId") Integer accountId, @Arg("target") OnlineFirmName targetRef,
                                   @Arg("amount") BigDecimal amount) {
        String firmName = firmRef.value();
        String targetFirm = targetRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        Firm targetF = firms.getFirmByNameOrId(targetFirm);
        if (targetF == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        if (targetF.getFirmId().equals(firm.getFirmId())) {
            message.send(sender, "business.finance.pay.same-firm");
            return;
        }

        if (!canAccessFirmFinances(firm, sender)) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        audit.payFirmFromAccount(firm.getFirmId(), accountId, targetF.getFirmId(), sender.getUniqueId(), amount);
        String formatted = treasury.formatAmount(amount);
        message.send(sender, "business.account.pay.business.success",
                "firm", firm.getDisplayName(), "accountId", accountId,
                "target", targetF.getDisplayName(), "amount", formatted);
        notifications.notifyFirmExcept(targetF.getFirmId(), sender.getUniqueId(), "business.notify.transfer.incoming",
                "firm", targetF.getDisplayName(), "amount", formatted, "sender", firm.getDisplayName());
    }

    // ---- ACCOUNT TRANSACTIONS ---------------------------------------------------

    @Route("account transactions <firm> <accountId>")
    @Permission("business.account.finance")
    @Async
    @Description("Show transaction history for a specific firm account (page 1)")
    public void accountTransactions(@Sender Player sender, @Arg("firm") FirmName firmRef,
                                    @Arg("accountId") Integer accountId) {
        accountTransactions(sender, firmRef, accountId, 1);
    }

    @Route("account transactions <firm> <accountId> <page>")
    @Permission("business.account.finance")
    @Async
    @Description("Show transaction history for a specific firm account")
    public void accountTransactions(@Sender Player sender, @Arg("firm") FirmName firmRef,
                                    @Arg("accountId") Integer accountId, @Arg("page") Integer page) {
        String firmName = firmRef.value();
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }

        if (!canAccessFirmFinances(firm, sender)) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        Page<TransactionEntry> txPage = audit.getAccountTransactions(firm.getFirmId(), accountId, page, TX_PAGE_SIZE);

        if (txPage.items().isEmpty()) {
            message.send(sender, "business.account.transactions.empty",
                    "firm", firm.getDisplayName(), "accountId", accountId);
            return;
        }

        message.send(sender, "business.account.transactions.header",
                "firm", firm.getDisplayName(), "accountId", accountId,
                "page", txPage.pageNumber(), "totalPages", txPage.totalPages());

        CommandSupport.renderTransactionLines(message, treasury, sender, txPage,
                "business.account.transactions.line");

        if (txPage.hasMore()) {
            message.send(sender, "business.account.transactions.next-page",
                    "firm", firm.getDisplayName(), "accountId", accountId,
                    "nextPage", txPage.pageNumber() + 1);
        }
    }

    private boolean canAccessFirmFinances(Firm firm, Player player) {
        return CommandSupport.canAccessFirmFinances(firms, staff, firm.getFirmId(), player.getUniqueId());
    }
}
