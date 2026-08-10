package io.paradaux.treasury.commands;

import com.google.inject.Inject;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.TransactionEntry;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.AuditService;
import io.paradaux.treasury.services.DataExportService;
import io.paradaux.treasury.services.LedgerService;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

@Slf4j
@Command({"transactions", "txns"})
@Permission("treasury.transactions")
public class TransactionsCommand implements CommandHandler {

    private static final int PAGE_SIZE = 10;

    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final DataExportService dataExportService;
    private final AuditService auditService;
    private final Message message;

    @Inject
    public TransactionsCommand(AccountService accountService,
                               LedgerService ledgerService,
                               DataExportService dataExportService,
                               AuditService auditService,
                               Message message) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.dataExportService = dataExportService;
        this.auditService = auditService;
        this.message = message;
    }

    @Route("help")
    @Description("Show /transactions help")
    public void help(@Sender Player sender) {
        message.send(sender, "treasury.help.transactions");
    }

    @Route("")
    @Async
    @Description("View your transaction history")
    public void transactions(@Sender Player sender) {
        showTransactions(sender, 1);
    }

    @Route("<page>")
    @Async
    @Description("View your transaction history")
    public void transactionsPage(@Sender Player sender,
                                 @Arg("page") int page) {
        showTransactions(sender, page);
    }

    @Route("audit <target>")
    @Permission("treasury.transactions.audit")
    @Async
    @Description("View another player's transaction history (staff/DOC)")
    public void auditPlayer(@Sender Player sender, @Arg("target") OfflinePlayer target) {
        showPlayerAudit(sender, target, 1);
    }

    @Route("audit <target> <page>")
    @Permission("treasury.transactions.audit")
    @Async
    @Description("View another player's transaction history (staff/DOC)")
    public void auditPlayerPage(@Sender Player sender,
                                @Arg("target") OfflinePlayer target,
                                @Arg("page") int page) {
        showPlayerAudit(sender, target, page);
    }

    @Route("auditaccount <accountId>")
    @Permission("treasury.transactions.audit")
    @Async
    @Description("View any account's transaction history by id (staff/DOC)")
    public void auditAccount(@Sender Player sender, @Arg("accountId") int accountId) {
        showAccountAudit(sender, accountId, 1);
    }

    @Route("auditaccount <accountId> <page>")
    @Permission("treasury.transactions.audit")
    @Async
    @Description("View any account's transaction history by id (staff/DOC)")
    public void auditAccountPage(@Sender Player sender,
                                 @Arg("accountId") int accountId,
                                 @Arg("page") int page) {
        showAccountAudit(sender, accountId, page);
    }

    @Route("export")
    @Permission("treasury.transactions.export")
    @Async
    @Description("Export your transaction history as CSV")
    public void exportTransactions(@Sender Player sender) {
        int accountId = accountService.getOrCreatePersonalAccountId(sender.getUniqueId());
        String url = dataExportService.exportTransactionsFor(accountId);
        message.send(sender, "treasury.transactions.export.success", "url", url);
    }

    @Route("export <accountId>")
    @Permission("treasury.transactions.export")
    @Async
    @Description("Export transaction history for an account you are a member of")
    public void exportTransactionsForAccount(@Sender Player sender,
                                             @Arg("accountId") int accountId) {
        if (!accountService.hasAccountByAccountId(accountId)) {
            message.send(sender, "treasury.transactions.export.not-found");
            return;
        }
        if (!accountService.canAccessAccount(sender.getUniqueId(), accountId)) {
            message.send(sender, "treasury.transactions.export.no-access");
            return;
        }
        String url = dataExportService.exportTransactionsFor(accountId);
        message.send(sender, "treasury.transactions.export.success", "url", url);
    }

    /** Hard cap so a malicious /transactions <huge> can't push MariaDB into a giant OFFSET scan. */
    private static final int MAX_PAGE = 10_000;

    private static int clampPage(int page) {
        if (page < 1) return 1;
        return Math.min(page, MAX_PAGE);
    }

    private void showTransactions(Player sender, int page) {
        page = clampPage(page);
        int offset = (page - 1) * PAGE_SIZE;

        int accountId = accountService.getOrCreatePersonalAccountId(sender.getUniqueId());
        Page<TransactionEntry> result = ledgerService.getTransactionHistory(accountId, offset, PAGE_SIZE);

        if (result.items().isEmpty()) {
            message.send(sender, "treasury.transactions.empty");
            return;
        }

        message.send(sender, "treasury.transactions.header",
                "page", String.valueOf(result.pageNumber()),
                "pages", String.valueOf(result.totalPages()));

        sendEntries(sender, result);

        if (result.hasMore()) {
            message.send(sender, "treasury.transactions.footer",
                    "next", String.valueOf(page + 1));
        }
    }

    /**
     * Renders another player's history for an auditor. Requires the target to
     * have actually played (the framework resolver returns a synthetic
     * OfflinePlayer for unknown names) and to own a PERSONAL account — never
     * creates one.
     *
     * <p>Like the rest of this audit surface, access is governed solely by the
     * {@code treasury.transactions.audit} node and intentionally <em>bypasses</em>
     * {@code account_access}: it is the in-game government's audit tool for
     * viewing any account, not an account-membership feature (ADT-18). Every
     * audit is logged in {@link #renderAudit} for accountability.
     */
    private void showPlayerAudit(Player viewer, OfflinePlayer target, int page) {
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            message.send(viewer, "treasury.general.unknown-player");
            return;
        }
        Integer accountId = accountService.findPersonalAccountId(target.getUniqueId());
        if (accountId == null) {
            message.send(viewer, "treasury.transactions.audit.no-account", "target", target.getName());
            return;
        }
        renderAudit(viewer, accountId, page, target.getName(), "/transactions audit " + target.getName());
    }

    /**
     * Renders any account's history by id for an auditor (covers business/government accounts).
     *
     * <p>Intentionally gated only by {@code treasury.transactions.audit} (op by
     * default; granted to the in-game government), with no {@code canAccessAccount}
     * check — viewing accounts the auditor is not a member of is the whole point.
     * The asymmetry with the export route ({@code canAccessAccount}-gated) is
     * deliberate: export is a player-facing {@code default:true} node, this is a
     * government audit override (ADT-18).
     */
    private void showAccountAudit(Player viewer, int accountId, int page) {
        if (!accountService.hasAccountByAccountId(accountId)) {
            message.send(viewer, "treasury.transactions.audit.not-found");
            return;
        }
        Account account = accountService.getAccountById(accountId);
        String label = account != null && account.getDisplayName() != null
                ? account.getDisplayName() : ("#" + accountId);
        renderAudit(viewer, accountId, page, label, "/transactions auditaccount " + accountId);
    }

    private void renderAudit(Player viewer, int accountId, int page, String subjectLabel, String navBase) {
        page = clampPage(page);
        int offset = (page - 1) * PAGE_SIZE;

        Page<TransactionEntry> result = ledgerService.getTransactionHistory(accountId, offset, PAGE_SIZE);

        // Leave a server-log trail of who audited whom, mirroring the explorer's auditView.
        log.info("{} audited transactions of {} (account #{}, page {})",
                viewer.getName(), subjectLabel, accountId, page);
        // Durable trail in the shared explorer_audit access log (fail-open).
        auditService.recordTransactionAudit(viewer.getUniqueId(), viewer.getName(), accountId, navBase, page);

        if (result.items().isEmpty()) {
            message.send(viewer, "treasury.transactions.audit.empty", "target", subjectLabel);
            return;
        }

        message.send(viewer, "treasury.transactions.audit.header",
                "target", subjectLabel,
                "page", String.valueOf(result.pageNumber()),
                "pages", String.valueOf(result.totalPages()));

        sendEntries(viewer, result);

        if (result.hasMore()) {
            message.send(viewer, "treasury.transactions.audit.footer",
                    "command", navBase + " " + (page + 1));
        }
    }

    private void sendEntries(Player viewer, Page<TransactionEntry> result) {
        for (TransactionEntry entry : result.items()) {
            TransactionEntryRenderer.send(viewer, message, accountService, entry);
        }
    }
}
