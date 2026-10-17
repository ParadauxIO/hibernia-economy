package io.paradaux.treasury.services.impl;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.mappers.MembershipMapper;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.config.EconomyConfiguration;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountBalance;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.AccountTypeTotal;
import io.paradaux.treasury.model.economy.BalanceEntry;
import io.paradaux.treasury.model.economy.EconomySummary;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.cache.AccountRedirectCache;
import io.paradaux.treasury.services.cache.PersonalAccountCache;
import org.apache.ibatis.exceptions.PersistenceException;
import org.mybatis.guice.transactional.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Slf4j
public class AccountServiceImpl implements AccountService {

    private final AccountMapper accountMapper;
    private final MembershipMapper membershipMapper;
    private final AccountRedirectCache redirectCache;
    private final PersonalAccountCache personalAccountCache;
    private final EconomyConfiguration economyConfig;
    // DecimalFormat is not thread-safe; use a ThreadLocal so each thread gets its own instance.
    // Rebuilt when the configured pattern changes so `economy.format` reloads live via
    // /treasury reload, consistent with currency-name reload (ADT format-pattern-not-reloaded).
    private volatile ThreadLocal<DecimalFormat> formatter;
    private volatile String formatterPattern;

    @Inject
    public AccountServiceImpl(AccountMapper accountMapper,
                              MembershipMapper membershipMapper,
                              AccountRedirectCache redirectCache,
                              PersonalAccountCache personalAccountCache,
                              EconomyConfiguration economyConfig) {
        this.accountMapper = accountMapper;
        this.membershipMapper = membershipMapper;
        this.redirectCache = redirectCache;
        this.personalAccountCache = personalAccountCache;
        this.economyConfig = economyConfig;
        this.formatterPattern = economyConfig.getEconomyFormat();
        this.formatter = newFormatter(this.formatterPattern);
    }

    private static ThreadLocal<DecimalFormat> newFormatter(String pattern) {
        return ThreadLocal.withInitial(() -> {
            DecimalFormat fmt = new DecimalFormat(pattern);
            fmt.setRoundingMode(RoundingMode.HALF_EVEN);
            return fmt;
        });
    }

    // ---- Balance ----

    @Override
    @Transactional
    public BigDecimal getBalanceReadOnly(int accountId) {
        AccountBalance b = accountMapper.readBalance(accountId);
        return b == null ? BigDecimal.ZERO : b.getBalance();
    }

    @Override
    @Transactional
    public Map<Integer, BigDecimal> getBalancesByIds(Collection<Integer> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, BigDecimal> byId = new LinkedHashMap<>();
        for (AccountBalance b : accountMapper.readBalances(new ArrayList<>(accountIds))) {
            byId.put(b.getAccountId(), b.getBalance());
        }
        return byId;
    }

    @Override
    @Transactional
    public BigDecimal getBalanceByOwnerUuid(UUID ownerUuid) {
        Integer accountId = findPersonalAccountId(ownerUuid);
        if (accountId == null) return BigDecimal.ZERO;
        AccountBalance b = accountMapper.readBalance(accountId);
        return b == null ? BigDecimal.ZERO : b.getBalance();
    }

    @Override
    @Transactional
    public boolean hasFunds(int accountId, BigDecimal amount) {
        AccountBalance b = accountMapper.readBalance(accountId);
        if (b == null) return false;
        return b.getBalance().compareTo(amount) >= 0;
    }

    // ---- Account lookups ----

    @Override
    @Transactional
    public Account getAccountByUUID(UUID ownerUuid) {
        Integer accountId = findPersonalAccountId(ownerUuid);
        if (accountId == null) return null;
        return accountMapper.findById(accountId);
    }

    @Override
    @Transactional
    public Account getAccountById(int accountId) {
        return accountMapper.findById(accountId);
    }

    @Override
    @Transactional
    public Map<Integer, Account> getAccountsByIds(Collection<Integer> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Account> byId = new LinkedHashMap<>();
        for (Account a : accountMapper.findByIds(new ArrayList<>(accountIds))) {
            byId.put(a.getAccountId(), a);
        }
        return byId;
    }

    @Override
    @Transactional
    public List<Account> getAccountsByOwner(UUID ownerUuid) {
        return accountMapper.findAccountsByOwner(ownerUuid);
    }

    @Override
    @Transactional
    public List<Account> getAccountsByTypeAndOwner(AccountType accountType, UUID ownerUuid) {
        return accountMapper.findAccountsByTypeAndOwner(accountType, ownerUuid);
    }

    @Override
    @Transactional
    public List<Account> getAccountsByMember(UUID memberUuid) {
        return accountMapper.findAccountsByMember(memberUuid);
    }

    @Override
    @Transactional
    public boolean hasAccountByAccountId(int accountId) {
        return accountMapper.findById(accountId) != null;
    }

    @Override
    @Transactional
    public boolean hasAccountByOwnerUuid(UUID ownerUuid) {
        return findPersonalAccountId(ownerUuid) != null;
    }

    // ---- Access control checks ----

    @Override
    @Transactional
    public boolean isAccountMember(UUID uuid, int accountId) {
        return membershipMapper.isMember(accountId, uuid) > 0;
    }

    @Override
    @Transactional
    public boolean isOwnerForAccountId(UUID uuid, int accountId) {
        Account account = accountMapper.findById(accountId);
        return account != null && uuid.equals(account.getOwnerUuid());
    }

    @Override
    @Transactional
    public boolean canAccessAccount(UUID uuid, int accountId) {
        return isOwnerForAccountId(uuid, accountId)
                || membershipMapper.isAuthorizer(accountId, uuid) > 0
                || membershipMapper.isMember(accountId, uuid) > 0;
    }

    @Override
    @Transactional
    public boolean accountHasBalance(UUID uuid, int accountId) {
        if (!canAccessAccount(uuid, accountId)) return false;
        return getBalanceReadOnly(accountId).compareTo(BigDecimal.ZERO) > 0;
    }

    // ---- Personal account convenience ----

    @Override
    @Transactional
    public boolean hasPersonalAccount(UUID ownerUuid) {
        return findPersonalAccountId(ownerUuid) != null;
    }

    @Override
    @Transactional
    public Integer findPersonalAccountId(UUID ownerUuid) {
        if (ownerUuid == null) return null;
        Integer cached = personalAccountCache.get(ownerUuid);
        if (cached != null) return cached;
        Integer id = accountMapper.findPersonalAccountId(ownerUuid);
        if (id != null) {
            // Committed read of an immutable UUID->id mapping — safe to cache.
            personalAccountCache.put(ownerUuid, id);
        }
        // Never cache a negative: the account may be created later.
        return id;
    }

    @Override
    @Transactional
    public int getPersonalAccountId(UUID ownerUuid) {
        Integer id = findPersonalAccountId(ownerUuid);
        if (id == null) throw new NoSuchElementException("No PERSONAL account for " + ownerUuid);
        return id;
    }

    @Override
    @Transactional
    public int getOrCreatePersonalAccountId(UUID ownerUuid) {
        Integer existing = findPersonalAccountId(ownerUuid);
        if (existing != null) return existing;
        // Freshly created: do NOT cache here. The insert is only durable once this
        // transaction commits; caching now would leave a stale id if it rolls back.
        // The id is cached on the next resolve, which reads the committed row.
        Account account = buildPersonalAccount(ownerUuid);
        try {
            accountMapper.insertAccount(account);
            accountMapper.seedBalance(account.getAccountId());
            return account.getAccountId();
        } catch (PersistenceException e) {
            // Concurrent first-login: another thread/process inserted this player's
            // PERSONAL account between our check and insert, tripping the
            // uq_one_personal_per_player unique constraint. Re-resolve to the
            // committed row (with a locking read so REPEATABLE READ sees it) instead
            // of propagating the duplicate-key error (ADT-33).
            Integer raced = accountMapper.findPersonalAccountIdLocking(ownerUuid);
            if (raced != null) {
                return raced;
            }
            throw e;
        }
    }

    // ---- System account convenience ----

    @Override
    @Transactional
    public int getOrCreateSystemAccountId(String pluginName, UUID owner) {
        Account existing = accountMapper.findSystemAccountForPlugin(pluginName);
        if (existing != null) return existing.getAccountId();
        // Vault bridge SYSTEM accounts are faucets/sinks. credit_limit = -1
        // disables the credit-limit check — the account can mint and burn freely.
        Account acc = new Account(0, AccountType.SYSTEM, owner, pluginName,
                false, false, true, BigDecimal.valueOf(-1));
        try {
            accountMapper.insertAccount(acc);
            accountMapper.seedBalance(acc.getAccountId());
            log.info("Created SYSTEM account for plugin '{}' (id={})", pluginName, acc.getAccountId());
            return acc.getAccountId();
        } catch (PersistenceException e) {
            // Concurrent first-resolve: another thread/process inserted this
            // plugin's SYSTEM account between our check and insert, tripping
            // uq_one_system_per_plugin (V24). Re-resolve to the committed row (with
            // a locking read so REPEATABLE READ sees it) instead of propagating the
            // duplicate-key error — mirrors getOrCreatePersonalAccountId (ADT-74).
            Integer raced = accountMapper.findSystemAccountIdForPluginLocking(pluginName);
            if (raced != null) {
                return raced;
            }
            throw e;
        }
    }

    // ---- Account lifecycle ----

    @Override
    @Transactional
    public Account createAccount(AccountType accountType, UUID ownerUuid, String displayName) {
        Objects.requireNonNull(accountType, "accountType");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(displayName, "displayName");

        Account account = new Account();
        account.setAccountType(accountType);
        account.setOwnerUuid(ownerUuid);
        account.setDisplayName(displayName);
        account.setRequiresAuthorization(false);
        account.setArchived(false);
        if (accountType == AccountType.SYSTEM) {
            // SYSTEM accounts are faucets/sinks that mint and burn freely — they ignore
            // credit limits. Default them to the -1 sentinel so the sentinel and the
            // type-based OverdraftPolicy check agree (PAR-319). Mirrors the direct
            // faucet defaults in getOrCreateSystemAccountId.
            account.setAllowOverdraft(true);
            account.setCreditLimit(BigDecimal.valueOf(-1));
        } else {
            account.setAllowOverdraft(false);
            account.setCreditLimit(BigDecimal.ZERO);
        }

        accountMapper.insertAccount(account);
        accountMapper.seedBalance(account.getAccountId());
        log.debug("Created {} account '{}' (id={}, owner={})",
                accountType, displayName, account.getAccountId(), ownerUuid);
        return account;
    }

    @Override
    @Transactional
    public void updateAccount(Account account) {
        Objects.requireNonNull(account, "account");
        if (accountMapper.findById(account.getAccountId()) == null) {
            throw new IllegalArgumentException("Account not found: " + account.getAccountId());
        }
        accountMapper.updateAccount(account);
    }

    @Override
    @Transactional
    public void reassignOwner(int accountId, UUID newOwnerUuid) {
        Objects.requireNonNull(newOwnerUuid, "newOwnerUuid");
        Account account = accountMapper.findById(accountId);
        if (account == null) {
            throw new IllegalArgumentException("Account not found: " + accountId);
        }
        accountMapper.updateOwner(accountId, newOwnerUuid);
        log.debug("Reassigned owner of account id={} to {}", accountId, newOwnerUuid);
    }

    @Override
    @Transactional
    public void archiveAccount(int accountId) {
        Account account = accountMapper.findById(accountId);
        if (account == null) throw new IllegalArgumentException("Account not found: " + accountId);
        account.setArchived(true);
        accountMapper.updateAccount(account);
        log.debug("Archived account id={}", accountId);
    }

    @Override
    @Transactional
    public void unarchiveAccount(int accountId) {
        Account account = accountMapper.findById(accountId);
        if (account == null) throw new IllegalArgumentException("Account not found: " + accountId);
        account.setArchived(false);
        accountMapper.updateAccount(account);
        log.debug("Unarchived account id={}", accountId);
    }

    // ---- Government account lookup ----

    @Override
    @Transactional
    public Account getGovernmentAccountByName(String name) {
        return accountMapper.findGovernmentAccountByName(name);
    }

    @Override
    @Transactional
    public boolean governmentAccountExists(String name) {
        return accountMapper.existsGovernmentAccountByName(name);
    }

    @Override
    @Transactional
    public List<Account> listGovernmentAccounts() {
        return accountMapper.findAllGovernmentAccounts();
    }

    @Override
    @Transactional
    public Account getBusinessAccountByName(String name) {
        return accountMapper.findBusinessAccountByName(name);
    }

    @Override
    @Transactional
    public java.util.Optional<Integer> findRedirectedAccount(UUID uuid) {
        if (uuid == null) return java.util.Optional.empty();
        // Cache-backed: the full redirect set is mirrored in memory, so a hit
        // never touches the DB. Stays @Transactional so the one-time lazy warm
        // (a single bulk read) joins the caller's MyBatis session.
        return java.util.Optional.ofNullable(redirectCache.get(uuid));
    }

    // ---- Balance top ----

    @Override
    @Transactional
    public Page<BalanceEntry> getTopBalances(int offset, int limit) {
        List<BalanceEntry> items = accountMapper.getTopBalances(limit, offset);
        int total = accountMapper.countPersonalAccounts();
        return new Page<>(items, total, offset, limit);
    }

    @Override
    @Transactional
    public EconomySummary getEconomySummary() {
        BigDecimal personal = BigDecimal.ZERO;
        BigDecimal business = BigDecimal.ZERO;
        BigDecimal government = BigDecimal.ZERO;

        for (AccountTypeTotal row : accountMapper.getEconomyTotalsByType()) {
            BigDecimal total = row.getTotal() != null ? row.getTotal() : BigDecimal.ZERO;
            switch (row.getAccountType()) {
                case PERSONAL -> personal = total;
                case BUSINESS -> business = total;
                case GOVERNMENT -> government = total;
                default -> { /* SYSTEM is excluded by the query */ }
            }
        }
        return new EconomySummary(personal, business, government);
    }

    // ---- Formatting ----

    @Override
    public String formatAmount(BigDecimal amount) {
        // Rebuild the formatter if economy.format changed via /treasury reload, so the
        // pattern updates live like the currency names (ADT format-pattern-not-reloaded).
        String current = economyConfig.getEconomyFormat();
        if (!current.equals(formatterPattern)) {
            formatter = newFormatter(current);
            formatterPattern = current;
        }
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_EVEN);
        return formatter.get().format(scaled);
    }

    @Override
    public String getCurrencyNameSingular() {
        return economyConfig.getCurrencyNameSingular();
    }

    @Override
    public String getCurrencyNamePlural() {
        return economyConfig.getCurrencyNamePlural();
    }

    // ---- Private helpers ----

    static Account buildPersonalAccount(UUID ownerUuid) {
        Account account = new Account();
        account.setAccountType(AccountType.PERSONAL);
        account.setOwnerUuid(ownerUuid);
        account.setDisplayName(ownerUuid.toString());
        account.setRequiresAuthorization(false);
        account.setArchived(false);
        account.setAllowOverdraft(false);
        account.setCreditLimit(BigDecimal.ZERO);
        return account;
    }
}
