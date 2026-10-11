package io.paradaux.treasuryapi.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.paradaux.business.api.BusinessApi;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasuryapi.TreasuryAPI;
import io.paradaux.treasuryapi.commands.BusinessKeyHandler;
import io.paradaux.treasuryapi.commands.PersonalKeyHandler;
import io.paradaux.treasuryapi.commands.UiAccessHandler;
import io.paradaux.treasuryapi.mappers.ApiKeyMapper;
import io.paradaux.treasuryapi.mappers.ExplorerGroupMapper;
import io.paradaux.treasuryapi.mappers.ExplorerUiMapper;
import io.paradaux.treasuryapi.model.config.ApiConfiguration;
import io.paradaux.treasuryapi.model.config.KeycloakConfiguration;
import io.paradaux.treasuryapi.services.ExplorerUiService;

import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds the injector from the REAL {@link TreasuryAPIModule} plus stand-ins for
 * what {@code HiberniaModule} / {@code DatabaseModule} contribute in production
 * (config components, {@code Message}, the MyBatis mappers), then resolves every
 * command handler the way {@code CommandManager.registerAll()} would.
 *
 * <p>Kept separate from {@link TreasuryAPIModuleSoftDependTest} so that test can
 * also run it inside a classloader that hides {@code net.luckperms.*} — the same
 * view of the world the plugin has on a server without LuckPerms installed.
 */
public final class SoftDependInjectorProbe {

    private SoftDependInjectorProbe() {}

    /** Creates the injector and resolves the handlers; throws whatever Guice throws. */
    public static Injector createInjector() {
        TreasuryAPI plugin = mock(TreasuryAPI.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("soft-depend-probe"));

        Injector injector = Guice.createInjector(
                new TreasuryAPIModule(plugin, mock(TreasuryApi.class), mock(BusinessApi.class)),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(ApiKeyMapper.class).toInstance(mock(ApiKeyMapper.class));
                        bind(ExplorerUiMapper.class).toInstance(mock(ExplorerUiMapper.class));
                        bind(ExplorerGroupMapper.class).toInstance(mock(ExplorerGroupMapper.class));
                        bind(ApiConfiguration.class).toInstance(mock(ApiConfiguration.class));
                        bind(KeycloakConfiguration.class).toInstance(mock(KeycloakConfiguration.class));
                        bind(Message.class).toInstance(mock(Message.class));
                    }
                });

        injector.getInstance(PersonalKeyHandler.class);
        injector.getInstance(BusinessKeyHandler.class);
        injector.getInstance(UiAccessHandler.class);
        return injector;
    }

    /** Runs {@code /treasuryapi ui sync} for a random player against a fresh injector. */
    public static void selfSync() {
        createInjector().getInstance(ExplorerUiService.class).selfSync(UUID.randomUUID());
    }
}
