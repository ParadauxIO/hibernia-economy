package io.paradaux.chestshop.model;

import io.paradaux.chestshop.utils.NameUtil;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers the three hand-written {@link Account} constructors (the Lombok accessors aside). */
class AccountTest {

    @Test
    void emptyConstructor_leavesFieldsUnset() {
        Account account = new Account();
        assertThat(account.getName()).isNull();
        assertThat(account.getUuid()).isNull();
        assertThat(account.getShortName()).isNull();
    }

    @Test
    void nameUuidConstructor_derivesShortNameFromName() {
        UUID id = UUID.randomUUID();
        Account account = new Account("Steve", id);

        assertThat(account.getName()).isEqualTo("Steve");
        assertThat(account.getUuid()).isEqualTo(id);
        assertThat(account.getShortName()).isEqualTo(NameUtil.stripUsername("Steve"));
    }

    @Test
    void nameShortNameUuidConstructor_storesAllVerbatim() {
        UUID id = UUID.randomUUID();
        Account account = new Account("AVeryLongUsername", "short", id);

        assertThat(account.getName()).isEqualTo("AVeryLongUsername");
        assertThat(account.getShortName()).isEqualTo("short");
        assertThat(account.getUuid()).isEqualTo(id);
    }

    @Test
    void setters_roundTrip() {
        Account account = new Account();
        Date now = new Date();
        account.setName("Alex");
        account.setShortName("Alex");
        account.setLastSeen(now);
        account.setIgnoreMessages(true);

        assertThat(account.getName()).isEqualTo("Alex");
        assertThat(account.getLastSeen()).isEqualTo(now);
        assertThat(account.isIgnoreMessages()).isTrue();
    }
}
