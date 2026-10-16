package io.paradaux.business.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.paradaux.business.Business;
import io.paradaux.business.api.BusinessApi;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("placeholderapi-absent")
class BusinessWithoutPlaceholderApiTest {
    @Test
    void apiIsActuallyAbsent() {
        assertThatThrownBy(() -> Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void pluginAndHookCanBeReflectedOverWithoutTheOptionalApi() {
        assertThatCode(() -> {
                    for (Class<?> type : new Class<?>[] {Business.class, BusinessPlaceholderHook.class}) {
                        assertThat(type.getDeclaredFields()).isNotNull();
                        assertThat(type.getDeclaredMethods()).isNotNull();
                        assertThat(type.getDeclaredConstructors()).isNotNull();
                    }
                })
                .doesNotThrowAnyException();
    }

    @Test
    void absentPluginSkipsRegistrationAndCleanupIsSafe() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager plugins = mock(PluginManager.class);
        BusinessApi api = mock(BusinessApi.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(plugins);
        assertThatCode(() -> BusinessPlaceholderHook.register(plugin, api).run())
                .doesNotThrowAnyException();
        verify(plugins).isPluginEnabled("PlaceholderAPI");
        verifyNoInteractions(api);
    }
}
