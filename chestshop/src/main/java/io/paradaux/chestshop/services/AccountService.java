package io.paradaux.chestshop.services;

import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.mappers.AccountMapper;
import io.paradaux.chestshop.model.PlayerSnapshot;
import org.apache.ibatis.exceptions.PersistenceException;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import java.util.UUID;

/**
 * Owns the account <em>logic</em>: the username/UUID/short-name caches, get-or-create
 * resolution, shortened-name allocation, the admin and server-economy accounts, and
 * the name-access rules. All persistence is delegated to the {@link AccountMapper}.
 *
 * <p>This is the service half of the {@code NameManager} split: the old static
 * God-class mixed raw SQLite access, caching, and these business rules in one place.
 * The DB access now lives in the MyBatis mapper, leaving this a service the rest of the
 * plugin reaches through {@link ChestShop#accounts()}.
 */
public interface AccountService {

    int getAccountCount();

    /**
     * Get or create an account for a player (only if the player has both a UUID and a name).
     * @throws IllegalArgumentException when an invalid player object was passed
     */
    Account getOrCreateAccount(OfflinePlayer player);

    /**
     * Get or create an account for a player.
     * @throws IllegalArgumentException when id or name are null
     */
    Account getOrCreateAccount(UUID id, String name);

    /** Account info from a UUID, or {@code null} if none was found. */
    Account getAccount(UUID uuid);

    /**
     * Account info from a non-shortened username, or {@code null} if none was found.
     * @throws IllegalArgumentException if the username is empty or null
     */
    Account getAccount(String fullName);

    /**
     * Account info from a username that might be shortened, or {@code null} if none was found.
     * @throws IllegalArgumentException if the username is empty
     */
    Account getAccountFromShortName(String shortName);

    /**
     * Resolve an owner name to its account: a business token ({@code B:…}) via the
     * Treasury/Business API ({@link ChestShop#economy()}), otherwise a player account.
     * Replaces the {@code AccountQueryEvent} dispatch.
     */
    Account resolveAccount(String name);

    /**
     * Store the username of a player into the database and the username-uuid cache.
     * @return The stored/updated account, or {@code null} if there was an error updating it
     */
    Account storeUsername(final PlayerSnapshot player);

    /**
     * Store an account into the database.
     * @throws PersistenceException if the backing store could not be written
     */
    void storeAccount(Account account);

    boolean canUseName(Player player, String base, String name);

    /**
     * Whether {@code player} may use/own {@code account}: a business firm's CHESTSHOP
     * permission via the Treasury/Business API, or owning the account by UUID. Replaces
     * the {@code AccountAccessEvent} dispatch.
     */
    boolean canAccess(Player player, Account account);

    /**
     * Whether {@code player} may use the owner name on {@code sign} for the given permission
     * {@code base} (e.g. create/access/destroy). A null sign or blank owner is permitted.
     */
    boolean hasPermission(Player player, String base, Sign sign);

    /** Whether {@code player} is the shop's owner — by UUID, or firm membership for a business shop. */
    boolean isOwner(Player player, Sign sign);

    /** Whether {@code player} may access (open/trade-with) the shop on {@code sign}. */
    boolean canAccess(Player player, Sign sign);

    /** Whether {@code player} has muted shop sale/stock notifications (the {@code /chestshop notify} toggle). */
    boolean isIgnoring(OfflinePlayer player);

    /** Whether the player with {@code playerId} has muted shop notifications. */
    boolean isIgnoring(UUID playerId);

    boolean isAdminShop(UUID uuid);

    boolean isServerEconomyAccount(UUID uuid);

    void load();

    Account getServerEconomyAccount();

    void setUuidVersion(int uuidVersion);

    int getUuidVersion();
}
