package io.paradaux.business.services;

import io.paradaux.business.model.Firm;

import java.util.List;
import java.util.UUID;

public interface FirmService {

    Firm createFirm(String name, UUID actorId);
    void disbandFirm(String firmName, UUID actorId);

    List<Firm> listAllFirms(int page, int pageSize);
    List<Firm> listOwnedOrMemberFirms(UUID playerId);

    void updateFirmHq(String firmName, String plotName, UUID actorId);
    void updateFirmDiscord(String firmName, String url, UUID actorId);

    // ---- int-id overloads (structure/0004) ----------------------------------
    // For internal callers (the BusinessApi delegate) that already hold the firm
    // id: resolve straight by id instead of round-tripping through
    // getFirmByNameOrId(String.valueOf(id)). The String overloads stay for the
    // command entrypoints resolving user name-or-id input.

    void disbandFirm(int firmId, UUID actorId);
    void updateFirmHq(int firmId, String plotName, UUID actorId);
    void updateFirmDiscord(int firmId, String url, UUID actorId);

    // ---- Staff/DOC administrative overrides (PAR-11) -------------------------
    // These bypass the proprietor/firm-permission checks; the calling command is
    // responsible for gating them on the `business.admin.*` permission surface.

    /** Force-disband a firm regardless of proprietorship (funds still returned to its proprietor). */
    void adminDisbandFirm(String firmName);

    /** Rename a firm's display name, validated like creation (format + uniqueness). Returns the updated firm. */
    Firm renameFirm(String firmName, String newName);

    /** Set a firm's HQ region without the ADMIN firm-permission check. */
    void adminSetHq(String firmName, String plotName);

    /** Set a firm's Discord URL without the ADMIN firm-permission check. */
    void adminSetDiscord(String firmName, String url);

    /**
     * Reassign a firm's proprietor without the transfer-acceptance flow (staff/DOC
     * override). Reassigns the firm's treasury accounts to the new proprietor and
     * records the forced handover as an ADMIN_OVERRIDE audit row. {@code actorId}
     * is the staff member performing the override.
     */
    void adminSetProprietor(String firmName, UUID newProprietor, UUID actorId);

    /**
     * Re-points a firm's default treasury account. System-initiated (no actor /
     * permission check) — used to self-heal a firm whose default account went
     * stale. Callers that act on behalf of a player should go through
     * {@code FirmAccountService.setDefaultAccount}.
     */
    void updateDefaultAccount(Integer firmId, Integer accountId);

    void updateProprietor(Integer firmId, UUID playerId);
    boolean isProprietor(String firmName, UUID playerId);
    boolean isProprietor(Integer firmId, UUID playerId);

    /**
     * Resolves an <em>active</em> firm by name or id; returns {@code null} for an
     * unknown <em>or disbanded</em> firm. This is the lookup command/action paths
     * use, so disbanded firms cleanly stop resolving (PAR-87). Use
     * {@link #getAnyFirmByNameOrId(String)} when you need to see archived firms.
     */
    Firm getFirmByNameOrId(String nameOrId);

    /**
     * Resolves an <em>active</em> firm directly by its integer id (ADT-99). Prefer
     * this over {@code getFirmByNameOrId(Integer.toString(id))} on hot paths that
     * already hold the id — it avoids the int→String→int round-trip and the
     * dependency on the "names can't start with a digit" invariant.
     */
    Firm getFirmById(int firmId);

    /** Resolves a firm by name or id <em>including archived</em> ones (readers / idempotency checks). */
    Firm getAnyFirmByNameOrId(String nameOrId);

    /**
     * Archived-inclusive counterpart of {@link #getFirmById(int)} (ADT-96). Prefer
     * this over {@code getAnyFirmByNameOrId(String.valueOf(id))} on paths that
     * already hold the id — same int→String→int round-trip avoidance as
     * {@link #getFirmById(int)}, but for readers that must still see disbanded firms.
     */
    Firm getAnyFirmById(int firmId);

    List<Firm> listAllActiveFirms();

}
