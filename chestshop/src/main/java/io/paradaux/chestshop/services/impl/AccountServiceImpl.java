package io.paradaux.chestshop.services.impl;
import lombok.extern.slf4j.Slf4j;

import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.services.BusinessAccountService;
import io.paradaux.chestshop.services.AdminBypassService;
import io.paradaux.chestshop.services.AccountService;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.utils.Permissions;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.mappers.AccountMapper;
import io.paradaux.chestshop.model.PlayerSnapshot;
import io.paradaux.chestshop.utils.NameUtil;
import io.paradaux.chestshop.utils.NumberUtil;
import io.paradaux.chestshop.utils.SimpleCache;
import io.paradaux.chestshop.utils.encoding.Base62;
import org.apache.ibatis.exceptions.PersistenceException;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;

import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

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
@Singleton
@Slf4j
public class AccountServiceImpl implements AccountService {

    private final AccountMapper accounts;
    // Business-account resolution / firm access checks delegate to the leaf
    // BusinessAccountService (no cycle — it depends on no other ChestShop service).
    private final BusinessAccountService businessAccounts;

    private final Object accountsLock = new Object();

    private final SimpleCache<String, Account> usernameToAccount;
    private final SimpleCache<UUID, Account> uuidToAccount;
    private final SimpleCache<String, Account> shortToAccount;
    private final SimpleCache<String, Boolean> invalidPlayers;

    private final ChestShopConfiguration config;
    private final SignService signService;

    private Account adminAccount;
    private Account serverEconomyAccount;
    private int uuidVersion = -1;

    private final AdminBypassService adminBypass;

    @Inject
    public AccountServiceImpl(AccountMapper accounts, BusinessAccountService businessAccounts,
                          ChestShopConfiguration config, SignService signService, AdminBypassService adminBypass) {
        this.adminBypass = adminBypass;
        this.accounts = accounts;
        this.businessAccounts = businessAccounts;
        this.config = config;
        this.signService = signService;
        this.usernameToAccount = new SimpleCache<>(config.getCacheSize());
        this.uuidToAccount = new SimpleCache<>(config.getCacheSize());
        this.shortToAccount = new SimpleCache<>(config.getCacheSize());
        this.invalidPlayers = new SimpleCache<>(config.getCacheSize());
    }

    @Override
    public int getAccountCount() {
        try {
            return NumberUtil.toInt(accounts.count() - 1);
        } catch (PersistenceException e) {
            return 0;
        }
    }

    @Override
    public Account getOrCreateAccount(OfflinePlayer player) {
        Preconditions.checkNotNull(player.getName(), "Name of player " + player.getUniqueId() + " is null?");
        Preconditions.checkArgument(player instanceof Player || !config.isEnsureCorrectPlayerid() || uuidVersion < 0 || player.getUniqueId().version() == uuidVersion,
                "Invalid OfflinePlayer! " + player.getUniqueId() + " has version " + player.getUniqueId().version() + " and not server version " + uuidVersion + ". " +
                        "If you believe that is an error and your setup allows such UUIDs then set the ENSURE_CORRECT_PLAYERID config option to false.");
        return getOrCreateAccount(player.getUniqueId(), player.getName());
    }

    @Override
    public Account getOrCreateAccount(UUID id, String name) {
        Preconditions.checkNotNull(id, "UUID of player is null?");
        Preconditions.checkNotNull(name, "Name of player " + id + " is null?");

        Account account = getAccount(id);
        if (account == null) {
            account = storeUsername(new PlayerSnapshot(id, name));
        }
        return account;
    }

    /** Account info from a UUID, or {@code null} if none was found. */
    @Override
    public Account getAccount(UUID uuid) {
        try {
            synchronized (accountsLock) {
                return uuidToAccount.get(uuid, () -> {
                    try {
                        Account account = accounts.findLatestByUuid(uuid);
                        if (account != null) {
                            account.setUuid(uuid);
                            shortToAccount.put(account.getShortName(), account);
                            usernameToAccount.put(account.getName(), account);
                            return account;
                        }
                    } catch (PersistenceException e) {
                        log.warn("Error while getting account for " + uuid + ":", e);
                    }
                    throw new Exception("Could not find account for " + uuid);
                });
            }
        } catch (ExecutionException ignored) {
            return null;
        }
    }

    @Override
    public Account getAccount(String fullName) {
        Preconditions.checkNotNull(fullName, "fullName cannot be null!");
        Preconditions.checkArgument(!fullName.isEmpty(), "fullName cannot be empty!");
        try {
            synchronized (accountsLock) {
                return usernameToAccount.get(fullName, () -> {
                    try {
                        Account account = accounts.findLatestByName(fullName);
                        if (account != null) {
                            account.setName(fullName);
                            shortToAccount.put(account.getShortName(), account);
                            return account;
                        }
                    } catch (PersistenceException e) {
                        log.warn("Error while getting account for " + fullName + ":", e);
                    }
                    throw new Exception("Could not find account for " + fullName);
                });
            }
        } catch (ExecutionException ignored) {
            return null;
        }
    }

    @Override
    public Account getAccountFromShortName(String shortName) {
        Preconditions.checkNotNull(shortName, "shortName cannot be null!");
        Preconditions.checkArgument(!shortName.isEmpty(), "shortName cannot be empty!");
        Account account = null;

        try {
            synchronized (accountsLock) {
                account = shortToAccount.get(shortName, () -> {
                    try {
                        Account a = accounts.findByShortName(shortName);
                        if (a != null) {
                            a.setShortName(shortName);
                            return a;
                        }
                    } catch (PersistenceException e) {
                        log.warn("Error while getting account for " + shortName + ":", e);
                    }
                    throw new Exception("Could not find account for " + shortName);
                });
            }
        } catch (ExecutionException e) {
            // The cache loader logged the cause above before throwing; fall through with a null account.
        }
        return account;
    }

    @Override
    public Account resolveAccount(String name) {
        Account business = businessAccounts.resolveBusinessAccount(name);
        if (business != null) {
            return business;
        }
        return getLastAccountFromName(name, false);
    }

    /**
     * The information from the last time a player logged in that previously used the (shortened) name.
     * @throws IllegalArgumentException if the username is empty
     */
    private Account getLastAccountFromName(String name, boolean searchOfflinePlayer) {
        Account account = getAccountFromShortName(name); // first get the account associated with the short name
        if (account == null) {
            account = getAccount(name);
        }
        if (account == null && searchOfflinePlayer && !invalidPlayers.contains(name.toLowerCase(Locale.ROOT))) {
            // no account with that shortname was found, try to get an offline player with that name
            OfflinePlayer offlinePlayer = ChestShop.getBukkitServer().getOfflinePlayer(name);
            if (offlinePlayer != null && offlinePlayer.getName() != null && offlinePlayer.getUniqueId() != null
                    && (!config.isEnsureCorrectPlayerid() || offlinePlayer.getUniqueId().version() == uuidVersion)) {
                account = storeUsername(new PlayerSnapshot(offlinePlayer.getUniqueId(), offlinePlayer.getName()));
            } else {
                invalidPlayers.put(name.toLowerCase(Locale.ROOT), true);
            }
        }
        if (account != null) {
            return getAccount(account.getUuid()); // then get the last account that was online with that UUID
        }
        return null;
    }

    @Override
    public Account storeUsername(final PlayerSnapshot player) {
        final UUID uuid = player.getUniqueId();

        Account latestAccount;
        synchronized (accountsLock) {
            try {
                latestAccount = accounts.findByUuidAndName(uuid, player.getName());
            } catch (PersistenceException e) {
                log.warn("Error while searching for latest account of " + player.getName() + "/" + uuid + ":", e);
                latestAccount = null;
            }

            if (latestAccount == null) {
                latestAccount = new Account(player.getName(), getNewShortenedName(player), player.getUniqueId());
            }

            latestAccount.setLastSeen(new Date());
            try {
                storeAccount(latestAccount);
            } catch (PersistenceException e) {
                log.warn("Error while updating account " + latestAccount + ":", e);
                return null;
            }

            usernameToAccount.put(latestAccount.getName(), latestAccount);
            uuidToAccount.put(uuid, latestAccount);
            shortToAccount.put(latestAccount.getShortName(), latestAccount);
        }

        return latestAccount;
    }

    @Override
    public void storeAccount(Account account) {
        if (account.getLastSeen() == null) {
            // lastSeen is NOT NULL in users.db. The per-player paths set it, but the
            // admin-shop account is built without it — default it here so any account
            // built without a lastSeen still persists instead of failing the insert.
            account.setLastSeen(new Date());
        }
        accounts.save(account);
    }

    /**
     * Get a new unique shortened name that hasn't been used by another player yet
     * (a maximum of 15 chars long).
     */
    String getNewShortenedName(PlayerSnapshot player) {
        String shortenedName = NameUtil.stripUsername(player.getName());

        Account account = getAccountFromShortName(shortenedName);
        if (account == null) {
            return shortenedName;
        }
        for (int id = 0; account != null; id++) {
            String baseId = Base62.encode(id);
            shortenedName = NameUtil.stripUsername(player.getName(), 15 - 1 - baseId.length()) + ":" + baseId;
            account = getAccountFromShortName(shortenedName);
        }

        return shortenedName;
    }

    @Override
    public boolean canUseName(Player player, String base, String name) {
        if (signService.isAdminShop(name)) {
            if (adminBypass.has(player, Permissions.ADMIN_SHOP)) {
                return true;
            } else {
                ChestShop.logDebug(player.getName() + " cannot use the name " + name + " as it's an admin shop and they don't have the permission " + Permissions.ADMIN_SHOP);
                return false;
            }
        }

        boolean isBusinessAccount = SignService.isBusinessAccount(name);

        // For business accounts, skip permission-based shortcuts — access is controlled
        // solely by Treasury/Business via canAccess(). Only ChestShop admins bypass this.
        if (isBusinessAccount) {
            if (adminBypass.has(player, Permissions.ADMIN)) {
                return true;
            }
        } else {
            if (adminBypass.otherName(player, base, name)) {
                return true;
            }
        }

        Account account = resolveAccount(name);
        if (account == null) {
            // There is no account by the provided name, but it matches the player name
            // Return true as they specified their own name and a new account should get created
            if (player.getName().equalsIgnoreCase(name)) {
                return true;
            }
            ChestShop.logDebug(player.getName() + " cannot use the name " + name + " for a shop as no account with that name exists");
            return false;
        }
        if (!isBusinessAccount && !account.getName().equalsIgnoreCase(name) && adminBypass.otherName(player, base, account.getName())) {
            return true;
        }
        return canAccess(player, account);
    }

    @Override
    public boolean canAccess(Player player, Account account) {
        if (businessAccounts.canAccessBusinessAccount(player, account)) {
            return true;
        }
        if (player.getUniqueId().equals(account.getUuid())) {
            return true;
        }
        ChestShop.logDebug(player.getName() + "/" + player.getUniqueId()
                + " cannot access the account " + account.getName() + "/" + account.getUuid()
                + " as their UUID doesn't match!");
        return false;
    }

    // ---- shop-sign access predicates (were the static SignService methods; PAR-282) ----

    @Override
    public boolean hasPermission(Player player, String base, Sign sign) {
        if (player == null) return false;
        if (sign == null) return true;

        String name = SignService.getOwner(sign);
        if (name == null || name.isEmpty()) return true;

        return canUseName(player, base, name);
    }

    /** Whether {@code player} is the shop's owner — by UUID, or firm membership for a business shop. */
    @Override
    public boolean isOwner(Player player, Sign sign) {
        if (player == null || sign == null) return false;

        String name = SignService.getOwner(sign);
        if (name == null || name.isEmpty()) return false;

        Account account = resolveAccount(name);
        if (account == null) {
            return player.getName().equalsIgnoreCase(name);
        }

        // For business accounts the UUID is synthetic and never matches a player UUID;
        // delegate to canAccess so firm membership/ownership is checked via the API.
        if (SignService.isBusinessAccount(name)) {
            return canAccess(player, account);
        }

        return account.getUuid().equals(player.getUniqueId());
    }

    /** Whether {@code player} may access (open/trade-with) the shop on {@code sign}. */
    @Override
    public boolean canAccess(Player player, Sign sign) {
        return hasPermission(player, Permissions.OTHER_NAME_ACCESS, sign);
    }

    /** Whether {@code player} has muted shop sale/stock notifications (the {@code /chestshop notify} toggle). */
    @Override
    public boolean isIgnoring(OfflinePlayer player) {
        return player != null && getOrCreateAccount(player).isIgnoreMessages();
    }

    /** Whether the player with {@code playerId} has muted shop notifications. */
    @Override
    public boolean isIgnoring(UUID playerId) {
        Account account = getAccount(playerId);
        return account != null && account.isIgnoreMessages();
    }

    @Override
    public boolean isAdminShop(UUID uuid) {
        return adminAccount != null && uuid.equals(adminAccount.getUuid());
    }

    @Override
    public boolean isServerEconomyAccount(UUID uuid) {
        return serverEconomyAccount != null && uuid.equals(serverEconomyAccount.getUuid());
    }

    @Override
    public void load() {
        if (getUuidVersion() < 0) {
            if (Bukkit.getOnlineMode()) {
                setUuidVersion(4);
            } else if (!Bukkit.getOnlinePlayers().isEmpty()) {
                setUuidVersion(Bukkit.getOnlinePlayers().iterator().next().getUniqueId().version());
            }
        }
        try {
            try {
                adminAccount = new Account(config.getAdminShopName(), Bukkit.getOfflinePlayer(config.getAdminShopName()).getUniqueId());
            } catch (NullPointerException ratelimitedException) {
                // This happens when the server was ratelimited by Mojang. Unfortunately there is no nice way to check that.
                // We fall back to the method used by CraftBukkit to generate an OfflinePlayer's UUID
                adminAccount = new Account(config.getAdminShopName(), UUID.nameUUIDFromBytes(("OfflinePlayer:" + config.getAdminShopName()).getBytes(Charsets.UTF_8)));
                log.warn("Your server appears to be ratelimited by Mojang and can't query UUID data from their API. If you run into issues with admin shops please report them!");
            }
            storeAccount(adminAccount);

            if (!config.getServerEconomyAccount().isEmpty()) {
                serverEconomyAccount = getAccount(config.getServerEconomyAccount());
            }
            if (serverEconomyAccount == null && !config.getServerEconomyAccount().isEmpty() && !config.getServerEconomyAccountUuid().equals(new UUID(0, 0))) {
                serverEconomyAccount = getOrCreateAccount(config.getServerEconomyAccountUuid(), config.getServerEconomyAccount());
            }
            if (serverEconomyAccount == null || serverEconomyAccount.getUuid() == null) {
                serverEconomyAccount = null;
                if (!config.getServerEconomyAccount().isEmpty()) {
                    log.warn("Server economy account setting '"
                            + config.getServerEconomyAccount()
                            + "' doesn't seem to be the name of a known player account!" +
                            " Please specify the SERVER_ECONOMY_ACCOUNT_UUID" +
                            " or log in at least once and create a player shop with that account" +
                            " in order for the server economy account to work.");
                }
            }
        } catch (PersistenceException e) {
            log.error("Error while trying to setup accounts", e);
        }
    }

    @Override
    public Account getServerEconomyAccount() {
        return serverEconomyAccount;
    }

    @Override
    public void setUuidVersion(int uuidVersion) {
        this.uuidVersion = uuidVersion;
    }

    @Override
    public int getUuidVersion() {
        return uuidVersion;
    }
}
