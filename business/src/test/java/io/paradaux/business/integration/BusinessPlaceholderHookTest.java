package io.paradaux.business.integration;

import io.paradaux.business.api.BusinessApi;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BusinessPlaceholderHookTest {
    @Test
    void registersOnceAndReturnsTheRegisteredExpansionForCleanup() {
        JavaPlugin plugin = plugin();
        BusinessApi api = mock(BusinessApi.class);
        try (var constructions = mockConstruction(BusinessPlaceholderExpansion.class, (expansion, context) -> {
            assertThat(context.arguments().toArray()).containsExactly(api, "2.3.0");
            when(expansion.register()).thenReturn(true);
        })) {
            Runnable cleanup = BusinessPlaceholderHook.register(plugin, api);
            assertThat(constructions.constructed()).hasSize(1);
            BusinessPlaceholderExpansion expansion = constructions.constructed().getFirst();
            verify(expansion).register();
            assertThat(cleanup).isSameAs(expansion);
        }
    }

    @Test
    void failedRegistrationDoesNotUnregisterAnotherExpansion() {
        JavaPlugin plugin = plugin();
        try (var constructions = mockConstruction(BusinessPlaceholderExpansion.class)) {
            BusinessPlaceholderHook.register(plugin, mock(BusinessApi.class)).run();
            verify(constructions.constructed().getFirst()).register();
            verify(constructions.constructed().getFirst(), never()).unregister();
            verify(plugin.getLogger()).warning(anyString());
        }
    }

    @Test
    void cleanupUnregistersTheExpansion() {
        BusinessPlaceholderExpansion expansion = spy(
                new BusinessPlaceholderExpansion(mock(BusinessApi.class), "2.3.0"));
        doReturn(true).when(expansion).unregister();
        expansion.run();
        verify(expansion).unregister();
    }

    private static JavaPlugin plugin() {
        JavaPlugin plugin = mock(JavaPlugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")).thenReturn(true);
        when(plugin.getPluginMeta().getVersion()).thenReturn("2.3.0");
        return plugin;
    }
}
