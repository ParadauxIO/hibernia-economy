package io.paradaux.business.chat;

import io.paradaux.business.Business;
import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Pure state-machine tests for {@link FirmChatService} (business/testing/0001):
 * the spy-registry toggles, the active-firm pointer, membership resolution and
 * session cleanup. CarbonChat is absent in the test JVM, so {@code available()}
 * is false and the channel-select paths are not exercised here — this pins the
 * in-memory logic that owns the routing decisions.
 */
@ExtendWith(MockitoExtension.class)
class FirmChatServiceTest {

    @Mock FirmService firms;
    @Mock FirmStaffService staff;
    @Mock Business plugin;

    private FirmChatService service;

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        service = new FirmChatServiceImpl(firms, staff, plugin);
    }

    private static Firm firm(int id, String displayName) {
        Firm f = new Firm();
        f.setFirmId(id);
        f.setDisplayName(displayName);
        return f;
    }

    // ---- CarbonChat absent → feature soft-disabled ----------------------------

    @Test
    void available_isFalseWithoutCarbonChat() {
        assertThat(service.available()).isFalse();
    }

    @Test
    void enterChat_returnsEmptyWhenUnavailable() {
        // No CarbonChat registered, so enterChat short-circuits before touching membership.
        assertThat(service.enterChat(null, 1)).isEmpty();
    }

    // ---- global spy toggle ----------------------------------------------------

    @Test
    void toggleGlobalSpy_flipsOnThenOff() {
        UUID u = UUID.randomUUID();
        assertThat(service.toggleGlobalSpy(u)).isTrue();
        assertThat(service.isAnySpy(u)).isTrue();
        assertThat(service.isSpyingOn(u, 42)).isTrue(); // global spy sees any firm

        assertThat(service.toggleGlobalSpy(u)).isFalse();
        assertThat(service.isAnySpy(u)).isFalse();
        assertThat(service.isSpyingOn(u, 42)).isFalse();
    }

    // ---- per-firm spy toggle --------------------------------------------------

    @Test
    void toggleFirmSpy_watchesOnlyThatFirm() {
        UUID u = UUID.randomUUID();
        assertThat(service.toggleFirmSpy(u, 7)).isTrue();
        assertThat(service.isSpyingOn(u, 7)).isTrue();
        assertThat(service.isSpyingOn(u, 8)).isFalse(); // a different firm is not watched
        assertThat(service.isAnySpy(u)).isTrue();
    }

    @Test
    void toggleFirmSpy_offForLastFirm_dropsTheSpyEntirely() {
        UUID u = UUID.randomUUID();
        service.toggleFirmSpy(u, 7);
        assertThat(service.toggleFirmSpy(u, 7)).isFalse();
        assertThat(service.isSpyingOn(u, 7)).isFalse();
        // With no firms left watched the player is no longer a spy at all.
        assertThat(service.isAnySpy(u)).isFalse();
    }

    @Test
    void toggleFirmSpy_multipleFirms_areIndependent() {
        UUID u = UUID.randomUUID();
        service.toggleFirmSpy(u, 7);
        service.toggleFirmSpy(u, 9);
        // Turning one off leaves the other watched.
        assertThat(service.toggleFirmSpy(u, 7)).isFalse();
        assertThat(service.isSpyingOn(u, 7)).isFalse();
        assertThat(service.isSpyingOn(u, 9)).isTrue();
        assertThat(service.isAnySpy(u)).isTrue();
    }

    @Test
    void isSpyingOn_nullFirmId_isFalseUnlessGlobal() {
        UUID u = UUID.randomUUID();
        service.toggleFirmSpy(u, 7);
        assertThat(service.isSpyingOn(u, null)).isFalse();

        service.toggleGlobalSpy(u);
        assertThat(service.isSpyingOn(u, null)).isTrue(); // global spy sees everything
    }

    // ---- active-firm pointer + cleanup ---------------------------------------

    @Test
    void activeFirm_absentByDefault() {
        assertThat(service.activeFirm(UUID.randomUUID())).isEmpty();
    }

    @Test
    void forget_clearsActiveFirmAndAllSpyState() {
        UUID u = UUID.randomUUID();
        service.toggleGlobalSpy(u);
        service.toggleFirmSpy(u, 7);

        service.forget(u);

        assertThat(service.isAnySpy(u)).isFalse();
        assertThat(service.isSpyingOn(u, 7)).isFalse();
        assertThat(service.activeFirm(u)).isEmpty();
    }

    // ---- firmName lookup ------------------------------------------------------

    @Test
    void firmName_nullId_returnsNull() {
        assertThat(service.firmName(null)).isNull();
    }

    @Test
    void firmName_unknownFirm_returnsNull() {
        when(firms.getFirmById(99)).thenReturn(null);
        assertThat(service.firmName(99)).isNull();
    }

    @Test
    void firmName_knownFirm_returnsDisplayName() {
        when(firms.getFirmById(3)).thenReturn(firm(3, "Acme"));
        assertThat(service.firmName(3)).isEqualTo("Acme");
    }

    // ---- multi-firm membership ------------------------------------------------

    @Test
    void inMultipleFirms_trueOnlyWhenMoreThanOne() {
        UUID solo = UUID.randomUUID();
        UUID multi = UUID.randomUUID();
        when(firms.listOwnedOrMemberFirms(solo)).thenReturn(List.of(firm(1, "One")));
        when(firms.listOwnedOrMemberFirms(multi)).thenReturn(List.of(firm(1, "One"), firm(2, "Two")));

        assertThat(service.inMultipleFirms(solo)).isFalse();
        assertThat(service.inMultipleFirms(multi)).isTrue();
    }
}
