package io.paradaux.treasury.commands;

import com.google.inject.Inject;
import io.paradaux.common.AmountInput;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import java.math.BigDecimal;
import org.bukkit.entity.Player;

/**
 * Explicitly-typed payment from the sender's own personal account into a chosen
 * account, so a player and a same-named business/government account are never
 * confused (the ambiguity {@code /pay} can hit):
 *
 *   /pay-account <player|business|government|account> <name|id> <amount> [memo]
 *   e.g. /pay-account business Acme 500 invoice 42
 *        /pay-account player Steve 100
 *
 * Same source (the sender), same permission ({@code treasury.pay}) and same
 * "pay into" semantics as {@code /pay} — only the recipient resolution differs.
 */
@Command({"pay-account", "paya"})
@Permission("treasury.pay")
public class PayAccountCommand implements CommandHandler {

    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final Message message;
    private final AccountResolver accountResolver;

    @Inject
    public PayAccountCommand(
            AccountService accountService,
            LedgerService ledgerService,
            Message message,
            AccountResolver accountResolver) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.message = message;
        this.accountResolver = accountResolver;
    }

    @Route("")
    @Description("Pay a specific account by type")
    public void root(@Sender Player sender) {
        sender.sendMessage("§eUsage: §f/pay-account <player|business|government|account> <name|id> <amount> [memo]");
    }

    @Route("<type> <account> <amount>")
    @Async
    @Description("Pay an explicitly-typed account from your own account")
    public void payAccount(
            @Sender Player sender,
            @Arg("type") String type,
            @Arg("account") String account,
            @Arg("amount") BigDecimal amount) {
        doPay(sender, type, account, amount, null);
    }

    @Route("<type> <account> <amount> <memo>")
    @Async
    @Description("Pay an explicitly-typed account from your own account with a memo")
    public void payAccountMemo(
            @Sender Player sender,
            @Arg("type") String type,
            @Arg("account") String account,
            @Arg("amount") BigDecimal amount,
            @GreedyArg("memo") String memo) {
        doPay(sender, type, account, amount, memo);
    }

    private void doPay(Player sender, String type, String account, BigDecimal amount, String memo) {
        // Sub-cent, non-positive and out-of-ledger-range amounts are rejected
        // (not rounded) — see AmountInput.
        BigDecimal normalized = AmountInput.positive(amount).orElse(null);
        if (normalized == null) {
            message.send(sender, "treasury.general.invalid-amount");
            return;
        }

        AccountResolver.Resolved target = accountResolver.resolve(sender, "target", type, account, true);
        if (target == null) return;
        var targetAccount = accountService.getAccountById(target.accountId());
        if (targetAccount == null || targetAccount.isArchived()) {
            message.send(sender, "treasury.pay.target-archived");
            return;
        }

        int senderAccountId = accountService.getOrCreatePersonalAccountId(sender.getUniqueId());
        if (senderAccountId == target.accountId()) {
            message.send(sender, "treasury.pay.self");
            return;
        }

        String memoLine = memo != null
                ? "Payment from " + sender.getName() + " to " + target.label() + ": " + memo
                : "Payment from " + sender.getName() + " to " + target.label();

        PersonalAccountPayment.pay(
                sender,
                senderAccountId,
                target::accountId,
                target.label(),
                normalized,
                memoLine,
                null,
                accountService,
                ledgerService,
                message);
    }
}
