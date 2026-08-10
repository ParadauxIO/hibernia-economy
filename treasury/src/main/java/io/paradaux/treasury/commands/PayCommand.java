package io.paradaux.treasury.commands;

import com.google.inject.Inject;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.commands.resolvers.PayTarget;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import io.paradaux.treasury.utils.Money;
import io.paradaux.treasury.utils.TreasuryConstants;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Command({"pay"})
@Permission("treasury.pay")
public class PayCommand implements CommandHandler {

    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final PlayerDirectoryService playerDirectory;
    private final Message message;

    @Inject
    public PayCommand(AccountService accountService, LedgerService ledgerService,
                      PlayerDirectoryService playerDirectory, Message message) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.playerDirectory = playerDirectory;
        this.message = message;
    }

    @Route("")
    @Description("Show /pay help")
    public void root(@Sender Player sender) {
        message.send(sender, "treasury.help.pay");
    }

    @Route("help")
    @Description("Show /pay help")
    public void help(@Sender Player sender) {
        message.send(sender, "treasury.help.pay");
    }

    @Route("<target> <amount>")
    @Async
    @Description("Pay another player or government account")
    public void pay(@Sender Player sender,
                    @Arg("target") PayTarget target,
                    @Arg("amount") BigDecimal amount) {
        doPay(sender, target.name(), amount, null);
    }

    @Route("<target> <amount> <memo>")
    @Async
    @Description("Pay another player or government account with a memo")
    public void payMemo(@Sender Player sender,
                        @Arg("target") PayTarget target,
                        @Arg("amount") BigDecimal amount,
                        @GreedyArg("memo") String memo) {
        doPay(sender, target.name(), amount, memo);
    }

    private void doPay(Player sender, String targetName, BigDecimal amount, String memo) {
        BigDecimal normalized = Money.normalize(amount);
        if (normalized.signum() <= 0) {
            message.send(sender, "treasury.general.invalid-amount");
            return;
        }

        // Resolve the target to a real player UUID independently of usercache
        // state: the live cache first, then the DB-backed player directory so an
        // uncached or Bedrock player who types a valid name can still be paid
        // (PAR-150) — matching how /eco, /fine and /gov payout resolve targets —
        // instead of being rejected as "unknown player".
        UUID targetUuid = null;
        String resolvedName = targetName;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(targetName);
        if (cached != null && (cached.hasPlayedBefore() || cached.isOnline())) {
            targetUuid = cached.getUniqueId();
            if (cached.getName() != null) resolvedName = cached.getName();
        } else {
            Optional<UUID> resolved = playerDirectory.resolveUuidByName(targetName);
            if (resolved.isPresent()) {
                targetUuid = resolved.get();
                resolvedName = playerDirectory.resolveNameByUuid(targetUuid).orElse(targetName);
            }
        }
        boolean knownPlayer = targetUuid != null;

        // A name that is BOTH a known player and a non-archived GOVERNMENT account
        // (DemocracyCraft runs governments as player alts of the same name) is
        // ambiguous. Routing must not depend on usercache state, so refuse and
        // require explicit typing via /pay-account rather than silently paying the
        // personal account (PAR-142).
        if (knownPlayer && accountService.governmentAccountExists(targetName)) {
            message.send(sender, "treasury.pay.ambiguous", "target", targetName);
            return;
        }

        if (knownPlayer) {
            payPlayer(sender, targetUuid, resolvedName, normalized, memo);
        } else {
            // Fall back to government account by display name. For anything
            // ambiguous (a player and a business/government sharing a name) use
            // /pay-account <type> <name> <amount> to disambiguate explicitly.
            Account govAccount = accountService.getGovernmentAccountByName(targetName);
            if (govAccount == null || govAccount.isArchived()) {
                message.send(sender, "treasury.general.unknown-player");
                return;
            }
            payGovernmentAccount(sender, govAccount, normalized, memo);
        }
    }

    private void payPlayer(Player sender, UUID targetUuid, String targetName, BigDecimal normalized, String memo) {
        if (sender.getUniqueId().equals(targetUuid)) {
            message.send(sender, "treasury.pay.self");
            return;
        }

        int senderAccountId = accountService.getOrCreatePersonalAccountId(sender.getUniqueId());
        String memoLine = "Payment from " + sender.getName() + " to " + targetName
                + (memo != null ? ": " + memo : "");

        boolean paid = PersonalAccountPayment.pay(sender, senderAccountId,
                () -> accountService.getOrCreatePersonalAccountId(targetUuid), targetName,
                normalized, memoLine, TreasuryConstants.PAY_PLUGIN_SYSTEM,
                accountService, ledgerService, message);
        if (!paid) return;

        Player onlineTarget = Bukkit.getPlayer(targetUuid);
        if (onlineTarget != null) {
            message.send(onlineTarget, "treasury.pay.received",
                    "amount", accountService.formatAmount(normalized),
                    "sender", sender.getName());
        }
    }

    private void payGovernmentAccount(Player sender, Account govAccount, BigDecimal normalized, String memo) {
        int senderAccountId = accountService.getOrCreatePersonalAccountId(sender.getUniqueId());
        String memoLine = "Payment from " + sender.getName() + " to " + govAccount.getDisplayName()
                + (memo != null ? ": " + memo : "");

        PersonalAccountPayment.pay(sender, senderAccountId, govAccount::getAccountId,
                govAccount.getDisplayName(), normalized, memoLine, TreasuryConstants.PAY_PLUGIN_SYSTEM,
                accountService, ledgerService, message);
    }
}
