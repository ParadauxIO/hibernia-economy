package io.paradaux.business.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmAccount;
import io.paradaux.business.testsupport.IntegrationTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

        assertThat(firmAccounts.listActiveAccountLinks()).singleElement().satisfies(link -> {
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

    @Test
    void accountResolutionExcludesRemovedLinksAndKeepsArchivedFirmLinks() throws Exception {
        int retained = insertStubAccount("Retained");
        int removed = insertStubAccount("Removed");
        firmAccounts.insertFirmAccount(firmId, retained);
        firmAccounts.insertFirmAccount(firmId, removed);
        firmAccounts.removeFirmAccount(firmId, removed);
        firms.archiveFirm(firmId, firms.getFirmById(firmId).getProprietorUuid());

        assertThat(firmAccounts.listAccountsByFirm(firmId))
                .extracting(FirmAccount::getAccountId)
                .containsExactly(retained);
        assertThat(firmAccounts.getFirmIdByAccountId(retained)).isEqualTo(firmId);
        assertThat(firmAccounts.getFirmIdByAccountId(removed)).isNull();
        assertThat(firmAccounts.listAccountsByFirm(-1)).isEmpty();
    }

    @Test
    void shopAliases_areUniqueCaseInsensitiveAndClearable() throws Exception {
        int first = insertStubAccount("First");
        int second = insertStubAccount("Second");
        firmAccounts.insertFirmAccount(firmId, first);
        firmAccounts.insertFirmAccount(firmId, second);
        assertThat(firmAccounts.setShopAlias(firmId, first, "ShopName")).isEqualTo(1);
        assertThat(firmAccounts.getShopAlias(firmId, first)).isEqualTo("ShopName");
        assertThat(firmAccounts.resolveShopAlias("SHOPNAME")).isEqualTo(first);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> firmAccounts.setShopAlias(firmId, second, "shopname"))
                .isInstanceOf(org.apache.ibatis.exceptions.PersistenceException.class);
        firmAccounts.setShopAlias(firmId, first, null);
        // V35's index protects current aliases. Historical reservations are
        // enforced by the service, rather than the withdrawn V40 triggers.
        assertThat(firmAccounts.setShopAlias(firmId, second, "shopname")).isEqualTo(1);
        assertThat(firmAccounts.resolveShopAlias("SHOPNAME")).isEqualTo(second);
        firmAccounts.setShopAlias(firmId, second, null);
        firmAccounts.setShopAlias(firmId, first, "shopname");
        assertThat(firmAccounts.resolveShopAlias("SHOPNAME")).isEqualTo(first);
        firmAccounts.removeFirmAccount(firmId, first);
        assertThat(firmAccounts.resolveShopAlias("shopname")).isNull();
        assertThat(firmAccounts.setShopAlias(firmId, first, "other")).isZero();
    }

    @Test
    void shopAliases_disbandedFirmDoesNotResolve() throws Exception {
        int account = insertStubAccount("Shop");
        firmAccounts.insertFirmAccount(firmId, account);
        firmAccounts.setShopAlias(firmId, account, "shopname");
        firms.archiveFirm(firmId, firms.getFirmById(firmId).getProprietorUuid());
        assertThat(firmAccounts.resolveShopAlias("shopname")).isNull();
        assertThat(firmAccounts.setShopAlias(firmId, account, "other")).isZero();
    }

    @Test
    void shopAliasReservationSurvivesClearingAndCannotChangeAccount() throws Exception {
        int original = insertStubAccount("Original");
        int other = insertStubAccount("Other");
        firmAccounts.insertFirmAccount(firmId, original);
        firmAccounts.insertFirmAccount(firmId, other);
        firmAccounts.reserveShopAlias("ShopName", original);
        firmAccounts.setShopAlias(firmId, original, "ShopName");
        firmAccounts.setShopAlias(firmId, original, null);
        assertThat(firmAccounts.resolveShopAlias("ShopName")).isNull();
        firmAccounts.reserveShopAlias("SHOPNAME", other);
        assertThat(firmAccounts.getShopAliasReservation("shopname")).isEqualTo(original);
        firmAccounts.reserveShopAlias("ShopName", original);
        firmAccounts.setShopAlias(firmId, original, "ShopName");
        assertThat(firmAccounts.resolveShopAlias("shopname")).isEqualTo(original);
    }
}
