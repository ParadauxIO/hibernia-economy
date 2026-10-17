package io.paradaux.treasury.services.impl;

import com.google.inject.Inject;
import io.paradaux.treasury.mappers.ExplorerAuditMapper;
import io.paradaux.treasury.model.economy.ExplorerAuditEntry;
import io.paradaux.treasury.services.AuditService;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class AuditServiceImpl implements AuditService {

    /** Effective capability recorded for an in-game audit. */
    private static final String AUDIT_ROLE = "treasury.transactions.audit";
    /** Distinguishes an in-game command from the explorer's HTTP methods. */
    private static final String CMD_METHOD = "CMD";
    private static final String TARGET_ACCOUNT = "account";
    private static final int OUTCOME_OK = 200;

    private final ExplorerAuditMapper explorerAuditMapper;

    @Inject
    public AuditServiceImpl(ExplorerAuditMapper explorerAuditMapper) {
        this.explorerAuditMapper = explorerAuditMapper;
    }

    // Not @Transactional: the single insert auto-commits, and any failure is
    // swallowed (fail-open) so logging can never break the audit read it records.
    @Override
    public void recordTransactionAudit(UUID actorUuid, String actorName, int accountId,
                                       String commandPath, int page) {
        try {
            ExplorerAuditEntry entry = new ExplorerAuditEntry();
            entry.setActorSub("minecraft:" + actorUuid);
            entry.setActorUuid(actorUuid);
            entry.setActorName(actorName);
            entry.setActorRole(AUDIT_ROLE);
            entry.setMethod(CMD_METHOD);
            entry.setPath(page > 1 ? commandPath + " (page " + page + ")" : commandPath);
            entry.setTargetType(TARGET_ACCOUNT);
            entry.setTargetId(String.valueOf(accountId));
            entry.setOutcome(OUTCOME_OK);
            explorerAuditMapper.insert(entry);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist transaction-audit log (actor={}, account={}): {}",
                    actorName, accountId, ex.toString());
        }
    }
}
