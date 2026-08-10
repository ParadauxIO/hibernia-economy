package io.paradaux.treasury.commands;

import com.google.inject.Inject;
import com.google.inject.Provider;
import io.paradaux.hibernia.framework.commander.CommandManager;
import io.paradaux.hibernia.framework.commander.HelpGenerator;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.Treasury;
import io.paradaux.treasury.api.ingest.IngestApi;
import io.paradaux.treasury.api.ingest.IngestReport;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.ConfigReloadService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.MembershipService;
import io.paradaux.treasury.utils.GovDedupKeys;
import io.paradaux.treasury.utils.Money;
import io.paradaux.treasury.utils.TreasuryConstants;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Command({"treasury", "tr"})
@Permission("treasury.command")
public class TreasuryCommand implements CommandHandler {

    private final Treasury plugin;
    private final Message message;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final MembershipService membershipService;
    private final AccountResolver accountResolver;
    private final ConfigReloadService configReloadService;
    // Provider breaks the construction cycle: CommandManager injects Set<CommandHandler>
    // (which includes this handler), so this handler must defer resolving it until help
    // is rendered, after the injector is fully built.
    private final Provider<CommandManager> commandManager;

    @Inject
    public TreasuryCommand(Treasury plugin, Message message,
                           AccountService accountService, LedgerService ledgerService,
                           MembershipService membershipService, AccountResolver accountResolver,
                           ConfigReloadService configReloadService,
                           Provider<CommandManager> commandManager) {
        this.plugin = plugin;
        this.message = message;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.membershipService = membershipService;
        this.accountResolver = accountResolver;
        this.configReloadService = configReloadService;
        this.commandManager = commandManager;
    }

    /**
     * {@code /treasury reload} — re-read config.yml + messages.properties and
     * refresh the live config objects (salary amounts, tax brackets/rates,
     * government account names, log level). DB pool, schedules and the currency
     * formatter still need a restart.
     */
    @Route("reload")
    @Permission("treasury.admin.reload")
    @Description("Admin: reload config.yml and messages.properties at runtime")
    public void reload(@Sender CommandSender sender) {
        try {
            configReloadService.reloadAll();
        } catch (RuntimeException e) {
            log.warn("Config reload failed", e);
            message.send(sender, "treasury.admin.reload.failed", "error", e.getMessage());
            return;
        }
        message.send(sender, "treasury.admin.reload.success");
    }

    @Route("")
    @Description("Show Treasury plugin information")
    public void info(@Sender CommandSender sender) {
        message.send(sender, "treasury.info",
                "version", plugin.getDescription().getVersion());
    }

    @Route("help")
    @Description("Show Treasury help index")
    public void help(@Sender CommandSender sender) {
        helpPage(sender, 1);
    }

    @Route("help <page>")
    @Description("Show a page of the Treasury help index")
    public void helpPage(@Sender CommandSender sender, @Arg("page") int page) {
        HelpGenerator help = new HelpGenerator(commandManager.get());
        sender.sendMessage(help.render(sender, "treasury", page));
    }

    @Route("admin ingest <source>")
    @Permission("treasury.admin.ingest")
    @Async
    @Description("Ingest player + balance data from a legacy economy backend")
    public void adminIngest(@Sender CommandSender sender, @Arg("source") String source) {
        String wanted = source.toLowerCase(Locale.ROOT);
        IngestApi target = null;
        for (RegisteredServiceProvider<IngestApi> rsp :
                Bukkit.getServicesManager().getRegistrations(IngestApi.class)) {
            if (rsp.getProvider().supportedSources().contains(wanted)) {
                target = rsp.getProvider();
                break;
            }
        }
        if (target == null) {
            message.send(sender, "treasury.admin.ingest.no-provider", "source", source);
            return;
        }
        message.send(sender, "treasury.admin.ingest.starting", "source", wanted);
        try {
            IngestReport report = target.ingest(wanted, sender);
            message.send(sender, "treasury.admin.ingest.complete",
                    "created", report.playersCreated(),
                    "skipped", report.playersSkipped(),
                    "failed", report.playersFailed(),
                    "scanned", report.filesScanned(),
                    "total", report.totalIngestedAmount(),
                    "millis", report.durationMillis());
        } catch (Throwable t) {
            log.error("Ingest from source '{}' failed", wanted, t);
            message.send(sender, "treasury.admin.ingest.failed",
                    "error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // =====================================================================
    // ADMIN TRANSFER
    //
    // Explicitly-typed, console-capable transfer between ANY two accounts.
    // Each side names its own kind — player / government / business / account
    // (the last being a raw account id) — so a player and a business that share
    // a display name are never confused. Bypasses requires_authorization (the
    // treasury.admin.transfer permission IS the authority); overdraft rules and
    // the source's funds still apply.
    //
    //   /treasury admin transfer <fromType> <from> <toType> <to> <amount> [reason]
    //   e.g. /tr admin transfer business Acme government DCGovernment 5000 grant
    //        /tr admin transfer account 42 player Steve 100
    // =====================================================================

    @Route("admin transfer <fromType> <from> <toType> <to> <amount>")
    @Permission("treasury.admin.transfer")
    @Async
    @Description("Admin transfer between any two accounts (player/government/business/account)")
    public void adminTransfer(@Sender CommandSender sender,
                              @Arg("fromType") String fromType,
                              @Arg("from") String from,
                              @Arg("toType") String toType,
                              @Arg("to") String to,
                              @Arg("amount") BigDecimal amount) {
        doAdminTransfer(sender, fromType, from, toType, to, amount, null);
    }

    @Route("admin transfer <fromType> <from> <toType> <to> <amount> <reason>")
    @Permission("treasury.admin.transfer")
    @Async
    @Description("Admin transfer between any two accounts with a reason")
    public void adminTransferReason(@Sender CommandSender sender,
                                    @Arg("fromType") String fromType,
                                    @Arg("from") String from,
                                    @Arg("toType") String toType,
                                    @Arg("to") String to,
                                    @Arg("amount") BigDecimal amount,
                                    @GreedyArg("reason") String reason) {
        doAdminTransfer(sender, fromType, from, toType, to, amount, reason);
    }

    @Route("admin balance <type> <id>")
    @Permission("treasury.admin.inspect")
    @Async // resolves + reads balance off the main thread, like every sibling route (ADT-18).
    @Description("Show the balance of any account (player/government/business/account)")
    public void adminBalance(@Sender CommandSender sender,
                             @Arg("type") String type, @Arg("id") String id) {
        AccountResolver.Resolved r = accountResolver.resolve(sender, "target", type, id, false);
        if (r == null) return;
        BigDecimal balance = accountService.getBalanceReadOnly(r.accountId());
        message.send(sender, "treasury.admin.balance",
                "label", r.label(),
                "balance", accountService.formatAmount(balance));
    }

    @Route("admin info <type> <id>")
    @Permission("treasury.admin.inspect")
    @Async // ~5 sequential DB round-trips — must not run on the Bukkit main thread (ADT-18).
    @Description("Dump account details (id, type, balance, flags, members) for debugging")
    public void adminInfo(@Sender CommandSender sender,
                          @Arg("type") String type, @Arg("id") String id) {
        AccountResolver.Resolved r = accountResolver.resolve(sender, "target", type, id, false);
        if (r == null) return;
        Account a = accountService.getAccountById(r.accountId());
        if (a == null) {
            message.send(sender, "treasury.admin.info.gone", "id", r.accountId());
            return;
        }
        BigDecimal balance = accountService.getBalanceReadOnly(a.getAccountId());
        message.send(sender, "treasury.admin.info.header", "label", r.label());
        message.send(sender, "treasury.admin.info.identity",
                "id", a.getAccountId(),
                "type", a.getAccountType(),
                "name", a.getDisplayName());
        message.send(sender, "treasury.admin.info.owner", "owner", a.getOwnerUuid());
        message.send(sender, "treasury.admin.info.balance",
                "balance", accountService.formatAmount(balance));
        message.send(sender, "treasury.admin.info.flags",
                "archived", a.isArchived(),
                "requiresAuth", a.isRequiresAuthorization(),
                "overdraft", a.isAllowOverdraft(),
                "creditLimit", a.getCreditLimit());
        message.send(sender, "treasury.admin.info.members",
                "members", membershipService.getMembers(a.getAccountId()).size(),
                "authorizers", membershipService.getAuthorizers(a.getAccountId()).size());
    }

    private void doAdminTransfer(CommandSender sender, String fromType, String fromToken,
                                 String toType, String toToken, BigDecimal amount, String reason) {
        BigDecimal normalized = Money.normalize(amount);
        if (normalized.signum() <= 0) {
            message.send(sender, "treasury.admin.transfer.invalid-amount");
            return;
        }

        AccountResolver.Resolved from = accountResolver.resolve(sender, "from", fromType, fromToken, true);
        if (from == null) return;
        AccountResolver.Resolved to = accountResolver.resolve(sender, "to", toType, toToken, true);
        if (to == null) return;

        if (from.accountId() == to.accountId()) {
            message.send(sender, "treasury.admin.transfer.same-account", "label", from.label());
            return;
        }

        UUID initiator = CommandSenders.actorOf(sender);
        String memo = reason != null
                ? "Admin transfer: " + reason
                : "Admin transfer " + from.label() + " -> " + to.label();
        byte[] dedupKey = GovDedupKeys.adminTransfer(
                initiator, from.accountId(), to.accountId(), normalized, Instant.now());

        try {
            ledgerService.adminTransfer(new TransferRequest(
                    from.accountId(),
                    to.accountId(),
                    normalized,
                    memo,
                    initiator,
                    null,
                    TreasuryConstants.TREASURY_PLUGIN_NAME,
                    dedupKey));
        } catch (IllegalStateException e) {
            BigDecimal balance = accountService.getBalanceReadOnly(from.accountId());
            message.send(sender, "treasury.admin.transfer.insufficient",
                    "from", from.label(),
                    "balance", accountService.formatAmount(balance));
            return;
        } catch (RuntimeException e) {
            log.warn("Admin transfer failed ({} -> {}, {})", from.label(), to.label(), normalized, e);
            message.send(sender, "treasury.admin.transfer.failed", "error", e.getMessage());
            return;
        }

        message.send(sender, "treasury.admin.transfer.success",
                "amount", accountService.formatAmount(normalized),
                "from", from.label(),
                "to", to.label());
    }

}
