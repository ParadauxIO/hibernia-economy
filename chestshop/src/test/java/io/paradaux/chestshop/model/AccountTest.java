package io.paradaux.chestshop.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The {@link Account} name/uuid constructor's short-name derivation (the rest is Lombok). */
class AccountTest {

    @Test
    void nameUuidConstructor_derivesAShortNameTruncatedTo15Chars() {
        UUID id = UUID.randomUUID();

        assertThat(new Account("Steve", id).getShortName()).isEqualTo("Steve");

        Account longName = new Account("SixteenCharName1", id);
        assertThat(longName.getName()).isEqualTo("SixteenCharName1");
        assertThat(longName.getShortName()).isEqualTo("SixteenCharName");
        assertThat(longName.getUuid()).isEqualTo(id);
    }
}
