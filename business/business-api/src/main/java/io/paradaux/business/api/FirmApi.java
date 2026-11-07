package io.paradaux.business.api;

import io.paradaux.business.model.Firm;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Firm lookup and modification operations.
 *
 * <p>Methods may throw {@link RuntimeException} subclasses for invalid operations
 * (e.g., firm not found, duplicate name, insufficient permissions).
 */
public interface FirmApi {

    /**
     * Resolve a shop alias (without the A: sign prefix) to a live firm's unarchived Treasury account.
     * Returns null for an unknown or inactive alias. Aliases are 1–12 ASCII letters, case-insensitive; existing API
     * implementations without alias support return null.
     */
    default @Nullable Integer resolveShopAlias(String alias) {
        return null;
    }

    /**
     * Looks up a firm by its display name or numeric ID.
     *
     * @param nameOrId the firm name or ID string
     * @return the firm, or {@code null} if not found
     */
    @Nullable
    Firm getFirm(String nameOrId);

    /**
     * Typed lookup by numeric firm id — prefer this over {@link #getFirm(String)}
     * when you already hold the id, so callers aren't forced through the
     * name-or-id string parser (ADT-108). Default-implemented for source/binary
     * compatibility with existing {@code FirmApi} implementations.
     *
     * @param firmId the firm id
     * @return the firm, or {@code null} if not found
     */
    default @Nullable Firm getFirmById(int firmId) {
        return getFirm(Integer.toString(firmId));
    }

    /**
     * Typed lookup by display name — the name-only counterpart of
     * {@link #getFirmById(int)} (ADT-108). The built-in implementation treats
     * numeric strings as names and includes archived firms. The default delegates
     * to the legacy parser for compatibility with third-party implementations.
     *
     * @param name the firm display name
     * @return the firm, or {@code null} if not found
     */
    default @Nullable Firm getFirmByName(String name) {
        return getFirm(name);
    }

    /**
     * Lists firms with pagination.
     *
     * @param page     1-based page number
     * @param pageSize number of results per page
     * @return list of firms for the requested page
     */
    List<Firm> listFirms(int page, int pageSize);

    /**
     * Gets all firms a player owns or is employed by.
     *
     * @param playerId the player's UUID
     * @return list of firms (may be empty)
     */
    List<Firm> getPlayerFirms(UUID playerId);

    /**
     * Creates a new firm with the given name, owned by the specified player.
     *
     * @param name          the firm display name
     * @param proprietorId  UUID of the founding proprietor
     * @return the newly created firm
     */
    Firm createFirm(String name, UUID proprietorId);

    /**
     * Disbands (archives) a firm. The actor must be the firm's proprietor.
     *
     * <p>Each of the firm's accounts is drained to the proprietor independently. If
     * any could not be drained the firm is still archived, the affected accounts
     * keep their balance, and this throws {@link IllegalStateException}; calling it
     * again on the same firm retries just those accounts.
     *
     * @param firmId  the firm ID
     * @param actorId UUID of the player performing the action
     * @throws IllegalStateException if the actor is not the proprietor, or the
     *                               firm was disbanded but not fully drained
     */
    void disbandFirm(int firmId, UUID actorId);

    /**
     * Sets the HQ region/plot for a firm. Requires ADMIN permission.
     *
     * @param firmId   the firm ID
     * @param plotName the plot/region name
     * @param actorId  UUID of the player performing the action
     */
    void setHq(int firmId, String plotName, UUID actorId);

    /**
     * Sets the Discord URL for a firm. Requires ADMIN permission.
     *
     * @param firmId  the firm ID
     * @param url     the Discord invite URL
     * @param actorId UUID of the player performing the action
     */
    void setDiscord(int firmId, String url, UUID actorId);

    /**
     * Checks whether a player is the proprietor of a firm.
     *
     * @param firmId   the firm ID
     * @param playerId the player's UUID
     * @return {@code true} if the player owns the firm
     */
    boolean isProprietor(int firmId, UUID playerId);

    /**
     * Looks up the firm that owns a given Treasury account.
     *
     * <p>Because each Treasury account belongs to at most one firm, this returns
     * {@code null} when the account is a personal account or is not registered
     * with any firm. Removed links are excluded; archived firms with remaining
     * links are included. This is a relationship lookup, not an access check.
     *
     * @param accountId the Treasury account ID (as returned by {@code Account.getAccountId()})
     * @return the owning firm, or {@code null} if the account is not a firm account
     */
    @Nullable
    Firm getFirmByAccountId(int accountId);

    /**
     * Lists Treasury account IDs currently linked to a firm (not just its default).
     * Removed links are excluded; an unknown firm or one without links returns an
     * empty list. Archived firms may retain links after an incomplete disband, so
     * this lookup includes those links. It does not check Treasury account status
     * or grant permission to use an account. No ordering is guaranteed.
     *
     * @param firmId the firm ID
     * @return the linked account IDs, never null
     * @throws UnsupportedOperationException if an older provider does not support
     *         this lookup; upgrade the Business plugin alongside the API
     */
    default List<Integer> getAccountIds(int firmId) {
        throw new UnsupportedOperationException("Firm account lookup requires an updated Business provider");
    }

    /**
     * Reads the firm's configured default Treasury account ID without creating an
     * account or falling back to another linked account. Includes archived firms,
     * matching {@link #getFirmById(int)}; this is not an account-access check.
     *
     * @param firmId the firm ID
     * @return the configured account ID, or null for an unknown firm or unset default
     */
    default @Nullable Integer getDefaultAccountId(int firmId) {
        Firm firm = getFirmById(firmId);
        return firm == null ? null : firm.getDefaultAccountId();
    }

    /**
     * Resolves a Treasury account ID to its linked firm ID, including archived
     * firms. Removed links, personal accounts and unknown accounts return null.
     *
     * @param accountId the Treasury account ID
     * @return the firm ID, or null if no linked firm exists
     */
    default @Nullable Integer getFirmIdByAccountId(int accountId) {
        Firm firm = getFirmByAccountId(accountId);
        return firm == null ? null : firm.getFirmId();
    }

    /**
     * Returns a firm's total balance, summed across all of its treasury accounts.
     * A firm with no live accounts (e.g. disbanded) reads as zero.
     *
     * @param firmId the firm ID
     * @return the aggregate balance, or zero if the firm has no accounts
     */
    BigDecimal getTotalBalance(int firmId);

    /**
     * Returns a firm's total balance, summed across all accounts, formatted as a
     * currency string by Treasury. A firm with no live accounts reads as zero.
     *
     * @param firmId the firm ID
     * @return the formatted aggregate balance
     */
    String getFormattedTotalBalance(int firmId);
}
