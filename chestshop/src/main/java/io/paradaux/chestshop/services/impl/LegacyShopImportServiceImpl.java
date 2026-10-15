package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.LegacyShop;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.LegacyShopImportService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.SignService;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.model.economy.AccountType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Streams the export off-thread; Bukkit decoding and API calls run on the server thread. */
@Singleton
@Slf4j
public class LegacyShopImportServiceImpl implements LegacyShopImportService {
    private final JavaPlugin plugin;
    private final File dataFolder;
    private final MarketService market;
    private final AccountService accounts;
    private final SignService signs;
    private final AtomicBoolean running = new AtomicBoolean();

    @Inject
    public LegacyShopImportServiceImpl(JavaPlugin plugin, @Named("dataFolder") File dataFolder,
                                       MarketService market, AccountService accounts, SignService signs) {
        this.plugin = plugin;
        this.dataFolder = dataFolder;
        this.market = market;
        this.accounts = accounts;
        this.signs = signs;
    }

    @Override
    public void importShops(CommandSender sender) {
        if (!market.enabled()) {
            sender.sendMessage("Legacy import requires Treasury MarketApi.");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            sender.sendMessage("A legacy shop import is already running.");
            return;
        }
        sender.sendMessage("Importing legacy-shops.jsonl; existing registry rows will be preserved.");
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> readExport(sender));
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    private void readExport(CommandSender sender) {
        int accepted = 0;
        int rejected = 0;
        int lineNumber = 0;
        String failure = null;
        try (var reader = Files.newBufferedReader(new File(dataFolder, "legacy-shops.jsonl").toPath())) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                LegacyShop row;
                try {
                    row = LegacyShop.parse(line);
                } catch (RuntimeException e) {
                    rejected++;
                    log.warn("Legacy shop import rejected line {}: invalid export row", lineNumber);
                    continue;
                }
                // One row per scheduled task bounds work per tick and memory usage. No world
                // chunks are loaded. Stop at a write/lookup failure; a rerun safely retries it.
                var task = plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                    importRow(row);
                    return null;
                });
                try {
                    task.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    task.cancel(false);
                    throw e;
                }
                accepted++;
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            failure = "Stopped at line " + lineNumber + "; fix the cause and rerun the same export.";
            log.warn("Legacy shop import stopped at line {}", lineNumber, e);
        } finally {
            running.set(false);
        }
        String summary = "Legacy import: " + accepted + " accepted (including existing locations), "
                + rejected + " rejected. " + (failure == null ? "Finished reading export." : failure);
        log.info(summary);
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(summary));
        }
    }

    /** Resolves a snapshot without loading its sign/chest, so unloaded shops are included. */
    void importRow(LegacyShop row) {
        World world = plugin.getServer().getWorld(row.worldUuid());
        if (world == null) throw new IllegalArgumentException("World is not loaded: " + row.worldUuid());
        MarketService.Owner owner;
        if (signs.isAdminShop(row.ownerName())) {
            owner = market.ownerFromUuid(null, true);
        } else {
            Account account = accounts.resolveAccount(row.ownerName());
            if (account == null || account.getUuid() == null) {
                throw new IllegalArgumentException("Unresolved legacy owner: " + row.ownerName());
            }
            owner = market.ownerFromUuid(account.getUuid(), false);
            if (owner.admin() || owner.accountId() == null || owner.type() == null
                    || (AccountType.BUSINESS.name().equals(owner.type()) && owner.firmId() == null)
                    || (AccountType.PERSONAL.name().equals(owner.type()) && owner.ownerUuid() == null)) {
                throw new IllegalArgumentException("Incomplete Treasury/Business owner: " + row.ownerName());
            }
        }
        ItemStack item = ItemStack.deserializeBytes(row.itemBytes());
        if (item.getType().isAir()) throw new IllegalArgumentException("Legacy item is air");
        var shop = market.shop(new Location(world, row.x(), row.y(), row.z()), item, owner,
                row.buyPrice(), row.sellPrice(), row.quantity(), row.stock(),
                row.capacity() < 0 ? null : row.capacity());
        market.market().importShop(shop, row.visible(), row.hologram(), row.observedAt());
    }
}
