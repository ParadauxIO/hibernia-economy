package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.AccountMapper;
import io.paradaux.treasuryrestapi.mapper.AdminFirmOperationsMapper;
import io.paradaux.treasuryrestapi.mapper.FirmMapper;
import io.paradaux.treasuryrestapi.model.Firm;
import io.paradaux.treasuryrestapi.security.AdminScope;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminFirmOperationsService {
    public record Result(
            long firmId, UUID proprietorUuid, boolean nameReleased, boolean taxExempt, List<Long> accountIds) {}

    private final FirmMapper firms;
    private final AccountMapper accounts;
    private final AdminFirmOperationsMapper operations;

    public AdminFirmOperationsService(FirmMapper firms, AccountMapper accounts, AdminFirmOperationsMapper operations) {
        this.firms = firms;
        this.accounts = accounts;
        this.operations = operations;
    }

    @Transactional
    public Result changeProprietor(VerifiedToken token, long id, String owner, UUID actingPlayer) {
        Firm firm = lock(token, id, true);
        if (owner == null || owner.isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OWNER", "Enter a player name or UUID.");
        UUID next;
        try {
            next = UUID.fromString(owner.strip());
        } catch (IllegalArgumentException e) {
            next = firms.findPlayerUuidByName(owner.strip());
        }
        if (next == null) throw new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", "Player not found.");
        UUID actor = actingPlayer != null ? actingPlayer : token.ownerUuid();
        UUID previous = firm.getProprietorUuid();
        if (!next.equals(previous)) {
            operations.removeEmployee(id, previous, actor);
            operations.removeEmployee(id, next, actor);
            operations.setProprietor(id, next);
            operations.cancelTransfers(id);
            operations.recordOverride(
                    id, previous, next, actor, UUID.randomUUID().toString());
            operations.revokeOutgoingKeys(id, previous);
            firm.setProprietorUuid(next);
        }
        return result(firm, reconcile(firm, actor));
    }

    @Transactional
    public Result releaseName(VerifiedToken token, long id) {
        Firm firm = lock(token, id, false);
        if (!firm.isArchived())
            throw new ApiException(HttpStatus.CONFLICT, "FIRM_ACTIVE", "Only a disbanded firm's name can be released.");
        if (!firms.listFirmAccounts(id).isEmpty())
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "LIVE_ACCOUNTS",
                    "Finish disbanding the firm's accounts before releasing its name.");
        operations.releaseName(id);
        return result(firm, List.of());
    }

    @Transactional
    public Result setTaxExempt(VerifiedToken token, long id, boolean exempt) {
        Firm firm = lock(token, id, true);
        operations.setTaxExempt(id, Boolean.toString(exempt));
        return result(firm, List.of());
    }

    @Transactional
    public Result syncAccounts(VerifiedToken token, long id, UUID actingPlayer) {
        Firm firm = lock(token, id, true);
        return result(firm, reconcile(firm, actingPlayer != null ? actingPlayer : token.ownerUuid()));
    }

    private Firm lock(VerifiedToken token, long id, boolean active) {
        AdminScope.require(token);
        Firm firm = firms.lockFirmById(id);
        if (firm == null) throw new ApiException(HttpStatus.NOT_FOUND, "FIRM_NOT_FOUND", "Firm not found.");
        if (active && firm.isArchived())
            throw new ApiException(HttpStatus.CONFLICT, "FIRM_ARCHIVED", "This firm is disbanded.");
        return firm;
    }

    /** Same role tiers as Business: owner/ADMIN authorize, FINANCIAL members spend. */
    private List<Long> reconcile(Firm firm, UUID actor) {
        var members = new LinkedHashSet<>(operations.staffWithAccess(firm.getFirmId(), true));
        var authorizers = new LinkedHashSet<>(operations.staffWithAccess(firm.getFirmId(), false));
        members.add(firm.getProprietorUuid());
        authorizers.add(firm.getProprietorUuid());
        List<Long> ids = new ArrayList<>();
        for (var account : firms.listFirmAccounts(firm.getFirmId())) {
            long id = account.getAccountId();
            accounts.updateOwner(id, firm.getProprietorUuid());
            operations.removeStaleAccess(id, List.copyOf(members));
            for (UUID subject : members) {
                operations.setAccess(id, subject, authorizers.contains(subject) ? "AUTHORIZER" : "MEMBER", actor);
            }
            ids.add(id);
        }
        return ids;
    }

    private Result result(Firm firm, List<Long> accounts) {
        long id = firm.getFirmId();
        return new Result(
                id, firm.getProprietorUuid(), operations.nameReleased(id), operations.taxExempt(id), accounts);
    }
}
