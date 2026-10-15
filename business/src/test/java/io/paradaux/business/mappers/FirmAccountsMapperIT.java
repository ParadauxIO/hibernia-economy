package io.paradaux.business.mappers;

import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmAccount;
import io.paradaux.business.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FirmAccountsMapperIT extends IntegrationTestBase {

    private FirmAccountsMapper firmAccounts;
    private FirmMapper firms;
    private int firmId;

    @BeforeEach
    void seed() {
        firmAccounts = mapper(FirmAccountsMapper.class);
        firms = mapper(FirmMapper.class);

        Firm f = new Firm();
        f.setDisplayName("Acme");
        f.setProprietorUuid(UUID.randomUUID().toString());
        firms.createFirm(f);
        firmId = f.getFirmId();
    }

    @Test
    void recoveryWorklistIncludesLiveLinksAndExcludesRemovedLinks() throws Exception {
        int live = insertStubAccount("Live");
        int removed = insertStubAccount("Removed");
        firmAccounts.insertFirmAccount(firmId, live);
        firmAccounts.insertFirmAccount(firmId, removed);
        firmAccounts.removeFirmAccount(firmId, removed);

        assertThat(firmAccounts.listActiveAccountLinks())
                .singleElement().satisfies(link -> {
                    assertThat(link.getFirmId()).isEqualTo(firmId);
                    assertThat(link.getAccountId()).isEqualTo(live);
                });
    }

    @Test
    void insertFirmAccount_persistsRow() throws Exception {
        int accountId = insertStubAccount("Acme Main");
        firmAccounts.insertFirmAccount(firmId, accountId);

        assertThat(firmAccounts.isFirmAccount(firmId, accountId)).isTrue();
        assertThat(firmAccounts.isFirmAccount(firmId, accountId + 1)).isFalse();
    }

    @Test
    void listAccountsByFirm_returnsAllRowsOrdered() throws Exception {
        int a = insertStubAccount("First");
        int b = insertStubAccount("Second");
        firmAccounts.insertFirmAccount(firmId, a);
        firmAccounts.insertFirmAccount(firmId, b);

        List<FirmAccount> accounts = firmAccounts.listAccountsByFirm(firmId);
        assertThat(accounts).extracting(FirmAccount::getAccountId).containsExactlyInAnyOrder(a, b);
        assertThat(accounts).allSatisfy(fa -> assertThat(fa.getAddedAt()).isNotNull());
    }

    @Test
    void removeFirmAccount_returnsRowCount() throws Exception {
        int a = insertStubAccount("Main");
        firmAccounts.insertFirmAccount(firmId, a);

        assertThat(firmAccounts.removeFirmAccount(firmId, a)).isEqualTo(1);
        assertThat(firmAccounts.removeFirmAccount(firmId, a)).isZero();
        assertThat(firmAccounts.isFirmAccount(firmId, a)).isFalse();
    }

    @Test
    void getFirmAccount_returnsRowOrNull() throws Exception {
        int a = insertStubAccount("Main");
        firmAccounts.insertFirmAccount(firmId, a);
        FirmAccount fa = firmAccounts.getFirmAccount(firmId, a);
        assertThat(fa).isNotNull();
        assertThat(fa.getAccountId()).isEqualTo(a);
        assertThat(firmAccounts.getFirmAccount(firmId, 9999)).isNull();
    }

    @Test
    void getAnyAccountId_andGetFirmIdByAccountId() throws Exception {
        int a = insertStubAccount("Main");
        firmAccounts.insertFirmAccount(firmId, a);
        assertThat(firmAccounts.getAnyAccountId(firmId)).isEqualTo(a);
        assertThat(firmAccounts.getFirmIdByAccountId(a)).isEqualTo(firmId);
        assertThat(firmAccounts.getFirmIdByAccountId(9999)).isNull();
    }
}
