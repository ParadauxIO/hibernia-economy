package io.paradaux.treasury.commands;

import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.model.economy.TransactionEntry;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.utils.MiniMessageText;
import org.bukkit.command.CommandSender;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Renders a {@link TransactionEntry} as a {@code treasury.transactions.entry}
 * line — signed, coloured amount; sanitized memo (with {@code message} fallback
 * and an em-dash when absent); settlement time — shared by {@code /transactions}
 * and {@code /gov account history} so the two stay identical.
 */
final class TransactionEntryRenderer {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

    private TransactionEntryRenderer() {
    }

    static void send(CommandSender recipient, Message message,
                     AccountService accountService, TransactionEntry entry) {
        String formattedAmount = accountService.formatAmount(entry.getAmount().abs());
        String sign = entry.getAmount().signum() >= 0 ? "+" : "-";
        String colorTag = entry.getAmount().signum() >= 0 ? "green" : "red";
        String coloredAmount = "<" + colorTag + ">" + sign + formattedAmount + "</" + colorTag + ">";
        String memo = entry.getMemo() != null ? entry.getMemo() : entry.getMessage();
        if (memo == null) memo = "—";
        memo = MiniMessageText.sanitize(memo);
        String time = entry.getSettlementTime() != null
                ? TIME_FMT.format(entry.getSettlementTime()) : "—";

        message.send(recipient, "treasury.transactions.entry",
                "txn", String.valueOf(entry.getTxnId()),
                "amount", coloredAmount,
                "memo", memo,
                "time", time);
    }
}
