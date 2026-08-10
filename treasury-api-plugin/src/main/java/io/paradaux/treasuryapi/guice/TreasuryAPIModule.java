package io.paradaux.treasuryapi.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import io.paradaux.business.api.BusinessApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasuryapi.TreasuryAPI;
import io.paradaux.treasuryapi.commands.BusinessKeyHandler;
import io.paradaux.treasuryapi.commands.PersonalKeyHandler;
import io.paradaux.treasuryapi.commands.UiAccessHandler;
import io.paradaux.treasuryapi.services.ApiKeyService;
import io.paradaux.treasuryapi.services.ExplorerUiService;
import io.paradaux.treasuryapi.services.KeycloakAdminClient;
import io.paradaux.treasuryapi.services.impl.ApiKeyServiceImpl;
import io.paradaux.treasuryapi.services.impl.ExplorerUiServiceImpl;
import io.paradaux.treasuryapi.services.impl.KeycloakAdminClientImpl;

/**
 * Plugin-specific bindings that {@link io.paradaux.hibernia.framework.guice.HiberniaModule}
 * does not provide. HiberniaModule binds {@code JavaPlugin}/{@code Plugin} (but NOT the
 * concrete {@code TreasuryAPI} subtype — this module binds that, mirroring Treasury's
 * {@code TreasuryModule}, so handlers injecting {@code TreasuryAPI} don't get a second
 * instance JIT-constructed), the {@link io.paradaux.hibernia.framework.configurator.ConfigurationLoader},
 * every discovered {@code @ConfigurationComponent} (as a {@code toInstance} singleton),
 * {@link io.paradaux.hibernia.framework.i18n.Message} (eager), the command/resolver/listener
 * multibinders and PlaceholderAPI support — so this module must NOT re-bind any of those.
 *
 * <p>Reload note: there is no config-reload path today. HiberniaModule captures each config
 * component as a {@code toInstance} singleton, so if a {@code ConfigurationLoader.reload()} path
 * is ever added under framework 1.2.0, those captured instances (and any singleton service that
 * snapshotted their values) would go stale. A reload would then need to re-fetch each component
 * via {@code ConfigurationLoader.getComponent(...)} and re-issue the affected bindings. No reload
 * command is added here.</p>
 */
public class TreasuryAPIModule extends AbstractModule {

    private final TreasuryAPI plugin;
    private final TreasuryApi treasuryApi;
    private final BusinessApi businessApi;

    public TreasuryAPIModule(TreasuryAPI plugin, TreasuryApi treasuryApi, BusinessApi businessApi) {
        this.plugin = plugin;
        this.treasuryApi = treasuryApi;
        this.businessApi = businessApi;
    }

    @Override
    protected void configure() {
        // Bind the concrete plugin instance so handlers injecting TreasuryAPI reuse
        // it instead of having Guice JIT-construct a second JavaPlugin (which throws
        // "Plugin already initialized!"). HiberniaModule only binds JavaPlugin/Plugin.
        bind(TreasuryAPI.class).toInstance(plugin);

        // Economy APIs obtained from Bukkit services (Treasury/Business load first).
        bind(TreasuryApi.class).toInstance(treasuryApi);
        bind(BusinessApi.class).toInstance(businessApi);

        // Services
        bind(ApiKeyService.class).to(ApiKeyServiceImpl.class).in(Singleton.class);
        bind(ExplorerUiService.class).to(ExplorerUiServiceImpl.class).in(Singleton.class);
        bind(KeycloakAdminClient.class).to(KeycloakAdminClientImpl.class).in(Singleton.class);

        // Command handlers
        bind(PersonalKeyHandler.class).in(Singleton.class);
        bind(BusinessKeyHandler.class).in(Singleton.class);
        bind(UiAccessHandler.class).in(Singleton.class);

        // LuckPerms (optional softdepend) for the group reconciliation cron. Guard
        // the class reference behind Class.forName so the bare LuckPerms.class
        // literal never throws NoClassDefFoundError when LuckPerms is absent.
        if (isClassPresent("net.luckperms.api.LuckPerms")) {
            bindLuckPerms();
        }
    }

    private void bindLuckPerms() {
        net.luckperms.api.LuckPerms lp =
                org.bukkit.Bukkit.getServicesManager().load(net.luckperms.api.LuckPerms.class);
        if (lp != null) {
            bind(net.luckperms.api.LuckPerms.class).toInstance(lp);
        }
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
