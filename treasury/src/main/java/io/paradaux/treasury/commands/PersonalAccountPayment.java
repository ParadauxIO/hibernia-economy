package io.paradaux.treasury.commands;

import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.function.IntSupplier;

/**
 * The shared "pay from the sender's own personal account" orchestration behind
 * {@code /pay} and {@code /pay-account}: balance pre-check, the ledger transfer,
 * uniform insufficient-funds handling, and the success message. The differing
 * bits (target resolution, memo text, plugin-system tag, any recipient
 * notification) stay with each caller.
 */
final class PersonalAccountPayment {

    private PersonalAccountPayment() {
    }

    /**
     * Moves {@code amount} from {@code sender}'s personal account to
     * {@code targetAccountId}, messaging the sender on insufficient funds and, on
     * success, sending {@code treasury.pay.success} with {@code targetLabel}.
     *
     * <p>{@code targetAccountId} is resolved lazily, only after the balance
     * pre-check passes, so a rejected pay never mints the target's account as a
     * side effect.
     *
     * @return {@code true} if the transfer settled, {@code false} if it was
     *         rejected for insufficient funds (the sender has already been messaged).
     */
    static boolean pay(Player sender, int senderAccountId, IntSupplier targetAccountId, String targetLabel,
                       BigDecimal amount, String memoLine, String pluginSystem,
                       AccountService accountService, LedgerService ledgerService, Message message) {
        BigDecimal senderBalance = accountService.getBalanceReadOnly(senderAccountId);
        if (senderBalance.compareTo(amount) < 0) {
            message.send(sender, "treasury.pay.insufficient",
                    "balance", accountService.formatAmount(senderBalance));
            return false;
        }

        try {
            ledgerService.transfer(new TransferRequest(
                    senderAccountId,
                    targetAccountId.getAsInt(),
                    amount,
                    memoLine,
                    sender.getUniqueId(),
                    null,
                    pluginSystem,
                    null));
        } catch (IllegalStateException e) {
            BigDecimal current = accountService.getBalanceReadOnly(senderAccountId);
            message.send(sender, "treasury.pay.insufficient",
                    "balance", accountService.formatAmount(current));
            return false;
        }

        message.send(sender, "treasury.pay.success",
                "target", targetLabel,
                "amount", accountService.formatAmount(amount));
        return true;
    }
}
