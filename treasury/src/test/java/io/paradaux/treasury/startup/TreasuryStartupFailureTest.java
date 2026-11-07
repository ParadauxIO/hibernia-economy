package io.paradaux.treasury.startup;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.paradaux.treasury.Treasury;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class TreasuryStartupFailureTest {
    @Test
    void incompleteReleaseStopsServerBeforePublishingServices() {
        var plugin = mock(Treasury.class, CALLS_REAL_METHODS);
        var server = mock(Server.class);
        var manager = mock(PluginManager.class);
        doReturn(server).when(plugin).getServer();
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        doReturn(new PluginDescriptionFile("Treasury", "new", Treasury.class.getName()))
                .when(plugin)
                .getDescription();
        when(server.getPluginManager()).thenReturn(manager);
        assertThatThrownBy(plugin::onEnable).isInstanceOf(IllegalStateException.class);
        verify(manager).disablePlugin(plugin);
        verify(server).shutdown();
        verify(server, never()).getServicesManager();
        assertThat(plugin.getInjector()).isNull();
    }
}
