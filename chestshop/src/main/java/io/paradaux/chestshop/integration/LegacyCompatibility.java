package io.paradaux.chestshop.integration;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.CreatedShop;
import io.paradaux.chestshop.model.DestroyedShop;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.ProtectionCheck;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.ShopService;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Deliberately small ABI boundary for existing server add-ons. Native services remain
 * authoritative; the legacy facade never opens databases or settles money itself.
 */
@Singleton
public final class LegacyCompatibility implements Listener {
    private static Injector services;
    private static boolean forwardingRemoval;
    private final ShopService shops;

    @Inject
    public LegacyCompatibility(ShopService shops) {
        this.shops = shops;
    }

    public static void install(Injector injector) {
        services = injector;
    }

    public static void clear() {
        services = null;
        forwardingRemoval = false;
    }

    public static <T> T service(Class<T> type) {
        if (services == null) throw new IllegalStateException("ChestShop is not enabled");
        return services.getInstance(type);
    }

    public static void refresh(ChestShopConfiguration config) {
        com.Acrobot.ChestShop.Configuration.Properties.VALID_PLAYERNAME_REGEXP = config.getValidPlayernameRegexp();
        com.Acrobot.ChestShop.Configuration.Properties.TURN_OFF_DEFAULT_PROTECTION_WHEN_PROTECTED_EXTERNALLY =
                config.isTurnOffDefaultProtectionWhenProtectedExternally();
        com.Acrobot.ChestShop.Configuration.Properties.REMOVE_LWC_PROTECTION_AUTOMATICALLY =
                config.isRemoveLwcProtectionAutomatically();
    }

    public static void fire(Event event) {
        if (services != null) Bukkit.getPluginManager().callEvent(event);
    }

    public static void beforeTrade(PendingTransaction trade) {
        if (!trade.isCancelled()) fire(new com.Acrobot.ChestShop.Events.PreTransactionEvent(trade));
    }

    public static void afterTrade(Transaction trade) {
        fire(new com.Acrobot.ChestShop.Events.TransactionEvent(trade));
    }

    public static void checkProtection(ProtectionCheck check) {
        fire(new com.Acrobot.ChestShop.Events.Protection.ProtectionCheckEvent(check));
    }

    public static void created(CreatedShop shop) {
        fire(new com.Acrobot.ChestShop.Events.ShopCreatedEvent(
                shop.getPlayer(), shop.getSign(), shop.getContainer(), shop.getSignLines()));
        if (services == null || shop.getOwnerAccount() == null) return;
        ChestShopConfiguration config = service(ChestShopConfiguration.class);
        if (config.isProtectSignWithLwc()) protect(shop.getSign().getBlock(), shop, config.getLwcSignProtectionType());
        if (config.isProtectChestWithLwc() && shop.getContainer() != null)
            protect(shop.getContainer().getBlock(), shop, config.getLwcChestProtectionType());
    }

    public static void edited(CreatedShop shop, String[] oldLines) {
        var owner = shop.getOwnerAccount();
        var legacyOwner = owner == null
                ? null
                : new com.Acrobot.ChestShop.Database.Account(owner.getName(), owner.getShortName(), owner.getUuid());
        if (legacyOwner != null) {
            legacyOwner.setIgnoreMessages(owner.isIgnoreMessages());
            legacyOwner.setLastSeen(
                    owner.getLastSeen() == null
                            ? null
                            : new java.util.Date(owner.getLastSeen().getTime()));
        }
        fire(new com.Acrobot.ChestShop.Events.ShopEditedEvent(
                shop.getPlayer(), shop.getSign(), shop.getContainer(), oldLines, shop.getSignLines(), legacyOwner));
    }

    private static void protect(org.bukkit.block.Block block, CreatedShop shop, String type) {
        fire(new com.Acrobot.ChestShop.Events.Protection.ProtectBlockEvent(
                block,
                shop.getPlayer(),
                shop.getOwnerAccount().getUuid(),
                com.Acrobot.ChestShop.Security.Type.valueOf(type.toUpperCase(java.util.Locale.ROOT))));
    }

    public static void destroyed(DestroyedShop shop) {
        if (forwardingRemoval) return;
        forwardingRemoval = true;
        try {
            fire(new com.Acrobot.ChestShop.Events.ShopDestroyedEvent(
                    shop.getDestroyer(), shop.getSign(), shop.getContainer()));
        } finally {
            forwardingRemoval = false;
        }
    }
    /** VaultStorage emits this event itself before removing a stored shop's sign. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void externalRemoval(com.Acrobot.ChestShop.Events.ShopDestroyedEvent event) {
        if (forwardingRemoval) return;
        forwardingRemoval = true;
        try {
            shops.onDestroyed(new DestroyedShop(event.getDestroyer(), event.getSign(), event.getContainer()));
        } finally {
            forwardingRemoval = false;
        }
    }
}
