package io.paradaux.treasury.services;

import io.paradaux.treasury.model.economy.AccountMember;

import java.util.List;
import java.util.UUID;

public interface MembershipService {

    // ── Checks (consult both direct-UUID rows and LP group rows) ──

    boolean isMember(int accountId, UUID uuid);
    boolean isAuthorizer(int accountId, UUID uuid);

    /** Read-only viewer of the account (direct UUID or LuckPerms group) — PAR-237. */
    boolean isViewer(int accountId, UUID uuid);

    /**
     * True when {@code uuid} may <em>read</em> the account's balance/history —
     * i.e. is a member (members and authorizers can already view) or a viewer.
     * This grants no spend or management rights.
     */
    boolean canView(int accountId, UUID uuid);

    /**
     * True when {@code uuid} may move money <em>out of</em> the account by virtue of
     * account membership — i.e. is a member or an authorizer. This is the fine-grained,
     * per-account spend gate (viewers do not qualify). Global permission nodes
     * (e.g. {@code treasury.gov.account.transfer}, {@code treasury.gov.admin}) and the
     * console/RCON bypass are the caller's concern and are checked at the command layer.
     */
    boolean canSpend(int accountId, UUID uuid);

    // ── Individual UUID CRUD ──

    void addMember(int accountId, UUID memberUuid, UUID addedByUuid);
    void removeMember(int accountId, UUID memberUuid);
    List<AccountMember> getMembers(int accountId);

    void addAuthorizer(int accountId, UUID authorizerUuid, UUID addedByUuid);
    void removeAuthorizer(int accountId, UUID authorizerUuid);
    List<AccountMember> getAuthorizers(int accountId);

    // ── LP Group CRUD ──

    void addGroupMember(int accountId, String lpGroup, UUID addedByUuid);
    void removeGroupMember(int accountId, String lpGroup);
    List<String> getGroupMembers(int accountId);

    void addGroupAuthorizer(int accountId, String lpGroup, UUID addedByUuid);
    void removeGroupAuthorizer(int accountId, String lpGroup);
    List<String> getGroupAuthorizers(int accountId);

    // ── Read-only viewer tier (PAR-237) ──

    void addViewer(int accountId, UUID viewerUuid, UUID addedByUuid);
    void removeViewer(int accountId, UUID viewerUuid);
    List<AccountMember> getViewers(int accountId);

    void addGroupViewer(int accountId, String lpGroup, UUID addedByUuid);
    void removeGroupViewer(int accountId, String lpGroup);
    List<String> getGroupViewers(int accountId);
}
