package io.paradaux.treasury.services.impl;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.model.config.SalaryConfiguration;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.model.salary.SalaryPayment;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.EconomyNotifier;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.SalaryService;
import io.paradaux.treasury.utils.Idempotency;
import io.paradaux.treasury.utils.TreasuryConstants;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pays online players a salary from a government account based on their highest
 * LuckPerms-group salary, on a fixed interval. See {@link SalaryConfiguration}.
 */
@Slf4j
public class SalaryServiceImpl implements SalaryService {

    private final SalaryConfiguration config;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final Server server;
    private final EconomyNotifier notifier;
    private final Clock clock;

    /** Optional soft-dependency; injected only when LuckPerms is present. */
    private LuckPerms luckPerms;

    @Inject
    public SalaryServiceImpl(SalaryConfiguration config, AccountService accountService,
                             LedgerService ledgerService, Server server, EconomyNotifier notifier) {
        this(config, accountService, ledgerService, server, notifier, Clock.systemUTC());
    }

    /** Test seam: lets a test pin the clock so the per-run dedup key is deterministic. */
    SalaryServiceImpl(SalaryConfiguration config, AccountService accountService,
                      LedgerService ledgerService, Server server, EconomyNotifier notifier,
                      Clock clock) {
        this.config = config;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.server = server;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Inject(optional = true)
    public void setLuckPerms(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
        log.info("LuckPerms integration active — government salaries enabled.");
    }

    @Override
    public List<SalaryPayment> planPayroll() {
        if (!config.isEnabled() || config.getAmounts().isEmpty()) {
            return List.of();
        }
        if (luckPerms == null) {
            log.warn("Salaries are enabled but LuckPerms is unavailable — no payroll computed.");
            return List.of();
        }

        List<SalaryPayment> plan = new ArrayList<>();
        for (Player player : server.getOnlinePlayers()) {
            if (config.isSkipAfk() && isAfk(player)) {
                continue; // don't pay UBI/salary to AFK-idling players (PAR-139)
            }
            UUID uuid = player.getUniqueId();
            config.highestSalary(groupsOf(uuid)).ifPresent(best ->
                    plan.add(new SalaryPayment(uuid, best.getKey(), best.getValue())));
        }
        return plan;
    }

    /**
     * Whether a player is AFK, per the configured LuckPerms context (default
     * {@code afk=true}). Servers expose AFK as a LuckPerms context via Essentials
     * or a calculator; this reads it generically so no Essentials build
     * dependency is needed. Returns false if LuckPerms can't supply a context.
     */
    private boolean isAfk(Player player) {
        ContextManager contextManager = luckPerms.getContextManager();
        if (contextManager == null) {
            return false;
        }
        ImmutableContextSet contexts = contextManager.getContext(player);
        return contexts != null
                && contexts.contains(config.getAfkContextKey(), config.getAfkContextValue());
    }

    @Override
    public int payout(List<SalaryPayment> payments) {
        if (payments == null || payments.isEmpty()) {
            return 0;
        }
        Account gov = accountService.getGovernmentAccountByName(config.getGovernmentAccount());
        if (gov == null) {
            log.warn("Salary government account '{}' not found — no salaries paid.", config.getGovernmentAccount());
            return 0;
        }
        int fromAccountId = gov.getAccountId();

        // One deterministic run id per payout period: derived from the current
        // period bucket so two overlapping runs (a manual run racing the scheduled
        // SalaryTask, or a retry after a partial failure) within the same interval
        // collapse to a single credit via the ledger's dedup UNIQUE — while the
        // next scheduled period still pays a fresh salary (PAR / ADT-8).
        long runId = currentPeriodStart();

        int paid = 0;
        for (SalaryPayment payment : payments) {
            try {
                int toAccountId = accountService.getOrCreatePersonalAccountId(payment.player());
                byte[] dedupKey = Idempotency.sha256("salary:" + runId + ":" + payment.player());

                // transferChecked reports whether it actually moved money. It returns
                // created=false when this period's dedup key already had a txn — the
                // ADT-8 never-double-pay guard collapsing a retry or a manual run that
                // overlaps the scheduled SalaryTask. Detection is inside the insert, so
                // it is race-safe even when two runs both pass a pre-check (the losing
                // run collapses on uq_ledger_dedup and still reports created=false).
                LedgerService.TransferResult result = ledgerService.transferChecked(new TransferRequest(
                        fromAccountId,
                        toAccountId,
                        payment.amount(),
                        "Government salary (" + payment.group() + ")",
                        TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                        null,
                        "treasury-salary",
                        dedupKey));

                if (result.created()) {
                    // A real payout happened — notify and count it.
                    notifier.notifySalaryPaid(payment.player(), payment.amount());
                    paid++;
                } else {
                    // Collapsed onto an existing payout for this period: no new money
                    // moved, so don't re-notify the player or inflate the paid count.
                    // Expected for retries/overlapping manual runs; but if the *scheduled*
                    // SalaryTask keeps colliding (two fires bucketed into one period under
                    // tick lag) a whole payout period is silently skipped — surface it so
                    // that is diagnosable.
                    log.warn("Salary for {} (group {}, period start {}) collapsed onto an existing "
                                    + "dedup key — no new payout for this period. Expected for a retry/overlapping "
                                    + "run; if it recurs on the scheduled timer, a payout period is being skipped.",
                            payment.player(), payment.group(), runId);
                }
            } catch (Exception e) {
                log.warn("Salary payment to {} failed: {}", payment.player(), e.getMessage());
            }
        }
        if (paid > 0) {
            log.info("Paid {} salary(ies) from {}.", paid, config.getGovernmentAccount());
        }
        return paid;
    }

    /**
     * Start-of-period epoch seconds for the current payout, bucketed by the
     * configured interval. Stable for the whole interval (so retries/overlaps
     * dedup) and advances each interval (so the next run pays again).
     */
    private long currentPeriodStart() {
        long interval = Math.max(1L, config.getIntervalSeconds());
        long now = clock.instant().getEpochSecond();
        return now - Math.floorMod(now, interval);
    }

    /** LuckPerms inherited group names for a player (empty if unknown/offline). */
    private Set<String> groupsOf(UUID uuid) {
        User user = luckPerms.getUserManager().getUser(uuid);
        if (user == null) {
            return Set.of();
        }
        return user.getInheritedGroups(user.getQueryOptions()).stream()
                   .map(Group::getName)
                   .collect(Collectors.toSet());
    }
}
