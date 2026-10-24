package io.paradaux.treasury.services;

import java.util.UUID;

/**
 * Persists privileged audit-command usage to the shared {@code explorer_audit}
 * access log, so an in-game audit of another account's ledger shows up in the
 * same "who viewed whose financial data" trail as the web explorer's reads.
 */
public interface AuditService {

    /**
     * Records that {@code actor} used the in-game transaction-audit tool to view
     * an account's history. Logged as an {@code account} access (mirroring how
     * the explorer logs account-history reads) with {@code method=CMD}.
     *
     * <p><b>Fail-open</b>: implementations never throw — a logging failure must
     * not block the audit read itself.
     *
     * @param actorUuid   the staff member who ran the command
     * @param actorName   their name at the time (may be {@code null})
     * @param accountId   the account whose ledger was viewed
     * @param commandPath the command that was run (e.g. {@code /transactions auditaccount 5})
     * @param page        the page of history viewed
     */
    void recordTransactionAudit(UUID actorUuid, String actorName, int accountId,
                                String commandPath, int page);
}
