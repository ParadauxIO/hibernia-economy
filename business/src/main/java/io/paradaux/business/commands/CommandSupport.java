package io.paradaux.business.commands;

import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.TransactionEntry;
import org.bukkit.command.CommandSender;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Shared helpers for the firm command handlers — the small pieces that were
 * copy-pasted across {@code AccountCommands}, {@code SalesCommands},
 * {@code MiscCommands} and {@code FirmCommands} (business/structure/0001–0003).
 *
 * <p>Behaviour-preserving: each helper reproduces exactly what the call sites
 * inlined, so there is a single copy of the finance-access predicate and the
 * transaction-line rendering loop. (The Discord invite regex — the third
 * duplicated rule — lives on {@link io.paradaux.business.utils.NameValidator}
 * so the service layer can share it without depending on this package.)
 */
public final class CommandSupport {

    private CommandSupport() {
    }

    /** How firm transaction times are rendered on the per-line output. */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

    /**
     * Firm-finance access predicate (business/structure/0001): the account owner
     * (proprietor) or staff holding {@code ADMIN} or {@code FINANCIAL}.
     */
    public static boolean canAccessFirmFinances(FirmService firms, FirmStaffService staff,
                                                Integer firmId, UUID playerId) {
        return firms.isProprietor(firmId, playerId)
                || staff.hasPermission(firmId, playerId, RolePermission.ADMIN)
                || staff.hasPermission(firmId, playerId, RolePermission.FINANCIAL);
    }

    /**
     * Render each transaction on a page as a message-line (business/structure/0002).
     * Signs the amount, formats it via Treasury, formats the settlement time, and
     * substitutes a null message for the empty string — exactly the loop that was
     * duplicated in {@code AccountCommands} and {@code MiscCommands}.
     *
     * @param lineKey the i18n key for a single line (differs per command surface)
     */
    public static void renderTransactionLines(Message message, TreasuryApi treasury,
                                              CommandSender sender, Page<TransactionEntry> page,
                                              String lineKey) {
        for (TransactionEntry entry : page.items()) {
            String sign = entry.getAmount().signum() >= 0 ? "+" : "";
            String formatted = sign + treasury.formatAmount(entry.getAmount());
            String time = TIME_FMT.format(entry.getSettlementTime());
            String msg = entry.getMessage() != null ? entry.getMessage() : "";
            message.send(sender, lineKey, "time", time, "amount", formatted, "message", msg);
        }
    }
}
