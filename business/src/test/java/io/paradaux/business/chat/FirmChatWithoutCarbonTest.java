package io.paradaux.business.chat;

import io.paradaux.business.Business;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CarbonChat is only a softdepend, so {@link FirmChatServiceImpl} must load, be
 * reflected over (as Guice does) and soft-disable itself on a server without it.
 * Runs only in the {@code testWithoutCarbon} Gradle task, which strips
 * carbonchat-api from the classpath — the first test guards that it really did,
 * so the others can't pass vacuously. This file must not reference a Carbon type.
 */
@Tag("carbon-absent")
class FirmChatWithoutCarbonTest {

    @Test
    void carbonIsNotOnTheClasspath() {
        assertThatThrownBy(() -> Class.forName("net.draycia.carbon.api.CarbonChat"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void serviceCanBeReflectedOverTheWayGuiceDoes() {
        // A Carbon type in any declared member — including the synthetic method javac
        // emits for a lambda body — makes these throw NoClassDefFoundError.
        assertThatCode(() -> {
            FirmChatServiceImpl.class.getDeclaredFields();
            FirmChatServiceImpl.class.getDeclaredMethods();
            FirmChatServiceImpl.class.getDeclaredConstructors();
        }).doesNotThrowAnyException();
    }

    @Test
    void initialiseSoftDisablesFirmChat() {
        Business plugin = mock(Business.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        FirmChatServiceImpl service =
                new FirmChatServiceImpl(mock(FirmService.class), mock(FirmStaffService.class), plugin);

        service.initialise();

        assertThat(service.available()).isFalse();
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        assertThat(service.enterChat(player, 1)).isEmpty();
        assertThatCode(() -> service.leaveChat(player)).doesNotThrowAnyException();
        assertThatCode(() -> service.membershipChanged(1)).doesNotThrowAnyException();
        assertThat(service.activeFirm(uuid)).isEmpty();
    }
}
