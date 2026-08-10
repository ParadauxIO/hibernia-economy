package io.paradaux.treasury.commands;

import com.google.inject.Inject;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.exceptions.FineAlreadyRevokedException;
import io.paradaux.treasury.api.exceptions.FineNotFoundException;
import io.paradaux.treasury.api.exceptions.GovAccountNotFoundException;
import io.paradaux.treasury.api.exceptions.InsufficientFineFundsException;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.GovernmentFine;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.FineWebhookService;
import io.paradaux.treasury.services.GovService;
import io.paradaux.treasury.services.MembershipService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import io.paradaux.treasury.utils.MiniMessageText;
import io.paradaux.treasury.utils.Money;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Command({"fine"})
@Permission("treasury.gov.fine")
public class FineCommand implements CommandHandler {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AccountService accountService;
    private final GovService govService;
    private final MembershipService membershipService;
    private final PlayerDirectoryService playerDirectory;
    private final FineWebhookService fineWebhook;
    private final Message message;

    @Inject
    public FineCommand(AccountService accountService,
                       GovService govService,
                       MembershipService membershipService,
                       PlayerDirectoryService playerDirectory,
                       FineWebhookService fineWebhook,
                       Message message) {
        this.accountService    = accountService;
        this.govService        = govService;
        this.membershipService = membershipService;
        this.playerDirectory   = playerDirectory;
        this.fineWebhook       = fineWebhook;
        this.message           = message;
    }

    @Route("")
    @Description("Show /fine help")
    public void root(@Sender Player sender) {
        message.send(sender, "treasury.help.fine");
    }

    @Route("help")
    @Description("Show /fine help")
    public void help(@Sender Player sender) {
        message.send(sender, "treasury.help.fine");
    }

    @Route("issue <account> <player> <amount> <reason>")
    @Async
    @Description("Issue a fine paid into a government account")
    public void issue(@Sender Player sender,
                      @Arg("account") String accountName,
                      @Arg("player") OfflinePlayer target,
                      @Arg("amount") BigDecimal amount,
                      @GreedyArg("reason") String reason) {
        // Access is decided per-account: a member or authorizer of the account
        // (or an admin) may fine into it — the same gate as /government pay.
        Account account = resolveGovAccount(accountName, sender);
        if (account == null) return;
        if (!canFineFrom(sender, account)) {
            message.send(sender, "treasury.gov.no-access");
            return;
        }

        UUID targetUuid;
        String targetName;
        if (target != null && (target.hasPlayedBefore() || target.isOnline())) {
            targetUuid = target.getUniqueId();
            targetName = target.getName();
        } else if (target != null && target.getName() != null) {
            // Usercache miss — resolve the typed name through the player directory
            // so an uncached / Bedrock target can still be fined (PAR-150).
            java.util.Optional<UUID> resolved = playerDirectory.resolveUuidByName(target.getName());
            if (resolved.isEmpty()) {
                message.send(sender, "treasury.general.unknown-player");
                return;
            }
            targetUuid = resolved.get();
            targetName = playerDirectory.resolveNameByUuid(targetUuid).orElse(target.getName());
        } else {
            message.send(sender, "treasury.general.unknown-player");
            return;
        }
        // Pre-validate against the service's full amount rules (positive, ≤ 2
        // decimal places, ≥ $0.01) so a malformed amount surfaces as
        // "invalid-amount" instead of leaking through the service's IAE path
        // below — which was previously mis-routed to "unknown-player".
        try {
            Money.requirePositive(amount, "fine amount > 0");
        } catch (IllegalArgumentException e) {
            message.send(sender, "treasury.general.invalid-amount");
            return;
        }

        GovernmentFine fine;
        try {
            fine = govService.issueFine(targetUuid, account.getAccountId(), amount, reason, sender.getUniqueId());
        } catch (InsufficientFineFundsException e) {
            message.send(sender, "treasury.fine.insufficient", "player", targetName);
            return;
        } catch (GovAccountNotFoundException e) {
            message.send(sender, "treasury.gov.not-found", "account", e.getIdentifier());
            return;
        } catch (IllegalArgumentException e) {
            // Player has no PERSONAL account on file (rare; covered by the
            // resolution preconditions above for normal flows).
            message.send(sender, "treasury.general.unknown-player");
            return;
        }

        String formattedAmount = accountService.formatAmount(fine.getAmount());
        message.send(sender, "treasury.fine.issued",
                "player", targetName,
                "amount", formattedAmount,
                "reason", reason);
        fineWebhook.sendFineIssued(fine);

        Player online = Bukkit.getPlayer(targetUuid);
        if (online != null) {
            message.send(online, "treasury.fine.issued.notify",
                    "amount", formattedAmount,
                    "reason", reason);
        }
    }

    @Route("firm <account> <firm> <amount> <reason>")
    @Async
    @Description("Fine a firm's business account, paid into a government account")
    public void fineFirm(@Sender Player sender,
                         @Arg("account") String accountName,
                         @Arg("firm") String firmAccountName,
                         @Arg("amount") BigDecimal amount,
                         @GreedyArg("reason") String reason) {
        Account govAccount = resolveGovAccount(accountName, sender);
        if (govAccount == null) return;
        if (!canFineFrom(sender, govAccount)) {
            message.send(sender, "treasury.gov.no-access");
            return;
        }

        Account business = accountService.getBusinessAccountByName(firmAccountName);
        if (business == null) {
            message.send(sender, "treasury.fine.firm.not-found", "firm", firmAccountName);
            return;
        }

        try {
            Money.requirePositive(amount, "fine amount > 0");
        } catch (IllegalArgumentException e) {
            message.send(sender, "treasury.general.invalid-amount");
            return;
        }

        GovernmentFine fine;
        try {
            fine = govService.issueFine(business.getAccountId(), govAccount.getAccountId(), amount, reason, sender.getUniqueId());
        } catch (InsufficientFineFundsException e) {
            message.send(sender, "treasury.fine.firm.insufficient", "firm", business.getDisplayName());
            return;
        } catch (GovAccountNotFoundException e) {
            message.send(sender, "treasury.gov.not-found", "account", e.getIdentifier());
            return;
        }

        String formattedAmount = accountService.formatAmount(fine.getAmount());
        message.send(sender, "treasury.fine.firm.issued",
                "firm", business.getDisplayName(),
                "amount", formattedAmount,
                "reason", reason);
        fineWebhook.sendFineIssued(fine);
    }

    @Route("revoke <id>")
    @Async
    @Description("Revoke a previously issued fine")
    public void revoke(@Sender Player sender, @Arg("id") long fineId) {
        // Only someone with access to the account the fine was paid into may
        // revoke it (refunds come out of that account).
        GovernmentFine existing = govService.getFine(fineId);
        if (existing == null) {
            message.send(sender, "treasury.fine.not-found", "id", String.valueOf(fineId));
            return;
        }
        Account account = accountService.getAccountById(existing.getGovAccountId());
        if (account == null) {
            message.send(sender, "treasury.gov.not-found", "account", String.valueOf(existing.getGovAccountId()));
            return;
        }
        if (!canFineFrom(sender, account)) {
            message.send(sender, "treasury.gov.no-access");
            return;
        }

        GovernmentFine fine;
        try {
            fine = govService.revokeFine(fineId, sender.getUniqueId());
        } catch (FineNotFoundException e) {
            message.send(sender, "treasury.fine.not-found", "id", String.valueOf(fineId));
            return;
        } catch (FineAlreadyRevokedException e) {
            message.send(sender, "treasury.fine.already-revoked", "id", String.valueOf(fineId));
            return;
        } catch (GovAccountNotFoundException e) {
            message.send(sender, "treasury.gov.not-found", "account", e.getIdentifier());
            return;
        }

        String playerName = resolveDebtorLabel(fine);
        String formattedAmount = accountService.formatAmount(fine.getAmount());
        message.send(sender, "treasury.fine.revoked",
                "id", String.valueOf(fineId),
                "player", playerName,
                "amount", formattedAmount);
        fineWebhook.sendFineRevoked(fine);

        Player online = Bukkit.getPlayer(fine.getPlayerUuid());
        if (online != null) {
            message.send(online, "treasury.fine.revoked.notify",
                    "amount", formattedAmount,
                    "reason", fine.getReason());
        }
    }

    @Route("list")
    @Permission("treasury.gov.fine.view")
    @Async
    @Description("List your own fines")
    public void listSelf(@Sender Player sender) {
        listFines(sender, sender);
    }

    @Route("list <player>")
    @Permission("treasury.gov.fine.view")
    @Async
    @Description("List fines for a player")
    public void listPlayer(@Sender Player sender, @Arg("player") OfflinePlayer target) {
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            message.send(sender, "treasury.general.unknown-player");
            return;
        }
        listFines(sender, target);
    }

    @Route("info <id>")
    @Permission("treasury.gov.fine.view")
    @Async
    @Description("View details of a fine")
    public void info(@Sender Player sender, @Arg("id") long fineId) {
        GovernmentFine fine = govService.getFine(fineId);
        if (fine == null) {
            message.send(sender, "treasury.fine.not-found", "id", String.valueOf(fineId));
            return;
        }

        String playerName = resolveDebtorLabel(fine);
        String issuerName = resolvePlayerName(fine.getIssuedBy());
        String formattedAmount = accountService.formatAmount(fine.getAmount());
        String issuedDate = fine.getIssuedAt() != null ? DATE_FMT.format(fine.getIssuedAt()) : "—";

        message.send(sender, "treasury.fine.info",
                "id", String.valueOf(fineId),
                "player", playerName,
                "amount", formattedAmount);
        message.send(sender, "treasury.fine.info.detail",
                "reason", MiniMessageText.sanitize(fine.getReason()),
                "issuer", issuerName,
                "date", issuedDate);

        if (fine.isRevoked()) {
            String revokerName = fine.getRevokedBy() != null ? resolvePlayerName(fine.getRevokedBy()) : "—";
            String revokeDate = fine.getRevokedAt() != null ? DATE_FMT.format(fine.getRevokedAt()) : "—";
            message.send(sender, "treasury.fine.info.revoked",
                    "revoker", revokerName,
                    "revoke_date", revokeDate);
        }
    }

    // ---- Private helpers ----

    /** Resolve a non-archived GOVERNMENT account by name, messaging the sender on miss. */
    private Account resolveGovAccount(String name, Player sender) {
        Account account = accountService.getGovernmentAccountByName(name);
        if (account == null || account.isArchived()) {
            message.send(sender, "treasury.gov.not-found", "account", name);
            return null;
        }
        return account;
    }

    /**
     * Who may fine into / revoke from a government account: members and authorizers
     * of the account, or an admin. Mirrors {@code GovCommand.canTransferFrom} so the
     * same people who can move a department's money can also manage its fines.
     */
    private boolean canFineFrom(Player player, Account account) {
        // The per-account member/authorizer check now lives in the service
        // (MembershipService.canSpend), matching GovCommand.canTransferFrom; the
        // global admin node and the coarse @Permission gate stay at the command layer.
        return player.hasPermission("treasury.gov.admin")
                || membershipService.canSpend(account.getAccountId(), player.getUniqueId());
    }

    private void listFines(Player sender, OfflinePlayer target) {
        List<GovernmentFine> fines = govService.getPlayerFines(target.getUniqueId());
        if (fines.isEmpty()) {
            message.send(sender, "treasury.fine.list.empty");
            return;
        }
        message.send(sender, "treasury.fine.list.header", "player", target.getName());
        for (GovernmentFine fine : fines) {
            String formattedAmount = accountService.formatAmount(fine.getAmount());
            String date = fine.getIssuedAt() != null ? DATE_FMT.format(fine.getIssuedAt()) : "—";
            String revokedTag = fine.isRevoked() ? " <gray>[REVOKED]</gray>" : "";
            message.send(sender, "treasury.fine.list.entry",
                    "id", String.valueOf(fine.getFineId()),
                    "amount", formattedAmount,
                    "reason", MiniMessageText.sanitize(fine.getReason()),
                    "date", date,
                    "revoked_tag", revokedTag);
        }
    }

    private String resolvePlayerName(UUID uuid) {
        return CommandSenders.resolvePlayerName(uuid);
    }

    /**
     * Display label for whoever a fine was levied against: the player's name for a
     * player fine, or the debtor account's name for a firm fine.
     */
    private String resolveDebtorLabel(GovernmentFine fine) {
        if (fine.getPlayerUuid() != null) {
            return resolvePlayerName(fine.getPlayerUuid());
        }
        if (fine.getDebtorAccountId() != null) {
            Account account = accountService.getAccountById(fine.getDebtorAccountId());
            if (account != null && account.getDisplayName() != null) {
                return account.getDisplayName();
            }
        }
        return "—";
    }
}
