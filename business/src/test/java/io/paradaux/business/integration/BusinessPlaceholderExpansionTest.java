package io.paradaux.business.integration;

import io.paradaux.business.api.BusinessApi;
import io.paradaux.business.api.FirmApi;
import io.paradaux.business.model.Firm;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BusinessPlaceholderExpansionTest {
    private final BusinessApi api = mock(BusinessApi.class);
    private final FirmApi firms = mock(FirmApi.class);
    private final BusinessPlaceholderExpansion expansion = new BusinessPlaceholderExpansion(api, "2.3.0");

    @BeforeEach
    void setUp() {
        when(api.firms()).thenReturn(firms);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Acme", "Acme_Holdings", "Acme Holdings", "Acme.Co-UK"})
    void resolvesTheWholeNameAndUsesThePublicAggregate(String name) {
        Firm firm = firm(false);
        when(firms.getFirmByName(name)).thenReturn(firm);
        when(firms.getTotalBalance(42)).thenReturn(new BigDecimal("1234567.89"));

        assertThat(expansion.onRequest(null, "firm_balance_" + name)).isEqualTo("1234567.89");
        verify(firms).getTotalBalance(42);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-10.25", "1E+9"})
    void returnsPlainDecimalForOnlineAndOfflineContexts(String balance) {
        when(firms.getFirmByName("Acme")).thenReturn(firm(false));
        when(firms.getTotalBalance(42)).thenReturn(new BigDecimal(balance));
        assertThat(expansion.onRequest(mock(OfflinePlayer.class), "firm_balance_Acme"))
                .isEqualTo(new BigDecimal(balance).toPlainString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "firm_balance_", "firm_balance", "firm_name_Acme", "balance_Acme"})
    void unknownOrIncompletePlaceholdersDoNotQueryTheApi(String params) {
        assertThat(expansion.onRequest(null, params)).isNull();
        verifyNoInteractions(firms);
    }

    @Test
    void missingAndArchivedFirmsStayUnresolvedRatherThanDisplayingZero() {
        assertThat(expansion.onRequest(null, "firm_balance_missing")).isNull();
        when(firms.getFirmByName("archived")).thenReturn(firm(true));
        assertThat(expansion.onRequest(null, "firm_balance_archived")).isNull();
        verify(firms, never()).getTotalBalance(anyInt());
    }

    @Test
    void readsFreshBalancesAndPersistsAcrossPapiReloads() {
        when(firms.getFirmByName("Acme")).thenReturn(firm(false));
        when(firms.getTotalBalance(42)).thenReturn(BigDecimal.ONE, BigDecimal.TEN);
        assertThat(expansion.onRequest(null, "firm_balance_Acme")).isEqualTo("1");
        assertThat(expansion.onRequest(null, "firm_balance_Acme")).isEqualTo("10");
        assertThat(expansion.persist()).isTrue();
        assertThat(expansion.getIdentifier()).isEqualTo("business");
        assertThat(expansion.getVersion()).isEqualTo("2.3.0");
        assertThat(expansion.getAuthor()).isNotBlank();
    }

    private static Firm firm(boolean archived) {
        Firm firm = new Firm();
        firm.setFirmId(42);
        firm.setArchived(archived);
        return firm;
    }
}
