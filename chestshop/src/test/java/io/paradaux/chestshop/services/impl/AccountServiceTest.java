package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.mappers.AccountMapper;
import io.paradaux.chestshop.model.PlayerSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the Bukkit-free account logic now that it lives in a service over a
 * pluggable {@link AccountMapper} — the row-count adjustment and the unique
 * shortened-name allocation, both formerly buried in the static {@code NameManager}
 * God-class and untestable. The fake mapper stands in for the SQLite store.
 */
class AccountServiceTest {

    /** In-memory {@link AccountMapper}: a fixed row count and a set of taken short names. */
    private static final class FakeAccountMapper implements AccountMapper {
        long count = 0;
        final Set<String> takenShortNames = new HashSet<>();
        Account lastSaved;

        @Override public void createTable() {}
        @Override public void addLastSeenColumn() {}
        @Override public void addIgnoreMessagesColumn() {}
        @Override public void createNameUuidIndex() {}

        @Override public Account findLatestByUuid(UUID uuid) { return null; }
        @Override public Account findLatestByName(String name) { return null; }
        @Override public Account findByShortName(String shortName) {
            return takenShortNames.contains(shortName)
                    ? new Account("taken", shortName, UUID.randomUUID())
                    : null;
        }
        @Override public Account findByUuidAndName(UUID uuid, String name) { return null; }
        @Override public void save(Account account) { count++; lastSaved = account; }
        @Override public long count() { return count; }
    }

    private FakeAccountMapper mapper;
    private AccountServiceImpl service;

    @BeforeEach
    void setUp() {
        ChestShopConfiguration config = mock(ChestShopConfiguration.class);
        when(config.getCacheSize()).thenReturn(128);
        mapper = new FakeAccountMapper();
        service = new AccountServiceImpl(mapper, null, config, null, null);
    }

    @Test
    void getAccountCount_excludesTheAdminAccountRow() {
        mapper.count = 5; // four players plus the always-present admin account

        assertThat(service.getAccountCount()).isEqualTo(4);
    }

    @Test
    void storeAccount_defaultsLastSeen_whenMissing() {
        // The admin-shop account is built without a lastSeen; users.db requires it
        // NOT NULL, so storeAccount must default it rather than fail the insert.
        Account account = new Account("AdminShop", "AdminShop", UUID.randomUUID());
        assertThat(account.getLastSeen()).isNull();

        service.storeAccount(account);

        assertThat(mapper.lastSaved).isSameAs(account);
        assertThat(mapper.lastSaved.getLastSeen()).isNotNull();
    }

    @Test
    void storeAccount_keepsAnExistingLastSeen() {
        Account account = new Account("Player", "Player", UUID.randomUUID());
        java.util.Date when = new java.util.Date(1_000_000L);
        account.setLastSeen(when);

        service.storeAccount(account);

        assertThat(mapper.lastSaved.getLastSeen()).isEqualTo(when);
    }

    @Test
    void getNewShortenedName_returnsTheStrippedName_whenItIsFree() {
        String name = service.getNewShortenedName(new PlayerSnapshot(UUID.randomUUID(), "Acrobot"));

        assertThat(name).isEqualTo("Acrobot");
    }

    @Test
    void getNewShortenedName_disambiguates_whenTheBaseNameIsTaken() {
        mapper.takenShortNames.add("Acrobot");

        String name = service.getNewShortenedName(new PlayerSnapshot(UUID.randomUUID(), "Acrobot"));

        assertThat(name).isNotEqualTo("Acrobot");
        assertThat(name).startsWith("Acrobot:");
        assertThat(mapper.findByShortName(name)).isNull(); // the allocated name is actually free
    }
}
