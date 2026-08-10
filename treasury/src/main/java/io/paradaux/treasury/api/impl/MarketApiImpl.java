package io.paradaux.treasury.api.impl;

import com.google.inject.Inject;
import io.paradaux.treasury.api.MarketApi;
import io.paradaux.treasury.api.market.ChestShopSaleRecord;
import io.paradaux.treasury.api.market.ChestShopShopRecord;
import io.paradaux.treasury.event.ChestShopSaleEvent;
import io.paradaux.treasury.mappers.ChestShopMarketMapper;
import org.bukkit.Bukkit;
import org.mybatis.guice.transactional.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Persists the ChestShop sales tracker + live shop registry on behalf of
 * ChestShop-3 (which has no economy-DB connection of its own). Fail-soft: a
 * persistence error is logged and swallowed so it can never break a trade or a
 * shop edit — this is analytics, not money.
 */
public class MarketApiImpl implements MarketApi {

    private static final Logger log = LoggerFactory.getLogger(MarketApiImpl.class);

    private final ChestShopMarketMapper mapper;

    @Inject
    public MarketApiImpl(ChestShopMarketMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void recordSale(ChestShopSaleRecord s) {
        try {
            Map<String, Object> p = new HashMap<>();
            p.put("txnId", s.txnId());
            p.put("direction", s.direction());
            p.put("customerUuid", s.customerUuid());
            p.put("shopAccountId", s.shopAccountId());
            p.put("shopAccountType", s.shopAccountType());
            p.put("shopFirmId", s.shopFirmId());
            p.put("shopOwnerUuid", s.shopOwnerUuid());
            p.put("adminShop", s.adminShop());
            p.put("material", s.material());
            p.put("itemKey", s.itemKey());
            p.put("itemName", s.itemName());
            p.put("itemCustom", s.itemCustom());
            p.put("itemData", s.itemData());
            p.put("quantity", s.quantity());
            p.put("unitPrice", s.unitPrice());
            p.put("totalPrice", s.totalPrice());
            p.put("taxAmount", s.taxAmount());
            p.put("world", s.world());
            p.put("signX", s.signX());
            p.put("signY", s.signY());
            p.put("signZ", s.signZ());
            p.put("shopStock", s.shopStock());
            mapper.insertSale(p);
            // Signal listeners (Business drives firm sale notifications, PAR-179)
            // after the sale is persisted. Synchronous — recordSale runs on the
            // main thread from the trade handler. Guarded separately so a listener
            // error or thread violation logs distinctly and never looks like the
            // sale itself failed.
            try {
                Bukkit.getPluginManager().callEvent(new ChestShopSaleEvent(s));
            } catch (RuntimeException ev) {
                log.debug("ChestShopSaleEvent dispatch failed (ignored): {}", ev.toString());
            }
        } catch (RuntimeException e) {
            log.warn("recordSale failed (ignored): {}", e.toString());
        }
    }

    @Override
    @Transactional
    public void upsertShop(ChestShopShopRecord sh) {
        try {
            Map<String, Object> p = new HashMap<>();
            p.put("world", sh.world());
            p.put("signX", sh.signX());
            p.put("signY", sh.signY());
            p.put("signZ", sh.signZ());
            p.put("adminShop", sh.adminShop());
            p.put("shopAccountId", sh.shopAccountId());
            p.put("shopAccountType", sh.shopAccountType());
            p.put("shopFirmId", sh.shopFirmId());
            p.put("shopOwnerUuid", sh.shopOwnerUuid());
            p.put("material", sh.material());
            p.put("itemKey", sh.itemKey());
            p.put("itemName", sh.itemName());
            p.put("itemCustom", sh.itemCustom());
            p.put("itemData", sh.itemData());
            p.put("buyPrice", sh.buyPrice());
            p.put("sellPrice", sh.sellPrice());
            p.put("batchQty", sh.batchQty());
            p.put("currentStock", sh.currentStock());
            p.put("estimatedCapacity", sh.estimatedCapacity());
            p.put("worldUuid", sh.worldUuid());
            mapper.upsertShop(p);
        } catch (RuntimeException e) {
            log.warn("upsertShop failed (ignored): {}", e.toString());
        }
    }

    @Override
    @Transactional
    public void deactivateShop(String world, int x, int y, int z) {
        try {
            mapper.deactivateShop(world, x, y, z);
        } catch (RuntimeException e) {
            log.warn("deactivateShop failed (ignored): {}", e.toString());
        }
    }

    @Override
    @Transactional
    public void updateShopStock(String world, int x, int y, int z, Integer stock, Integer estimatedCapacity) {
        try {
            mapper.updateShopStock(world, x, y, z, stock, estimatedCapacity);
        } catch (RuntimeException e) {
            log.warn("updateShopStock failed (ignored): {}", e.toString());
        }
    }

    @Override
    @Transactional
    public void setShopVisibility(String world, int x, int y, int z, boolean visible) {
        try {
            mapper.setShopVisibility(world, x, y, z, visible);
        } catch (RuntimeException e) {
            log.warn("setShopVisibility failed (ignored): {}", e.toString());
        }
    }

    @Override
    @Transactional
    public void setShopHologram(String world, int x, int y, int z, boolean hologram) {
        try {
            mapper.setShopHologram(world, x, y, z, hologram);
        } catch (RuntimeException e) {
            log.warn("setShopHologram failed (ignored): {}", e.toString());
        }
    }

    @Override
    @Transactional
    public void setPreviewPreference(java.util.UUID playerUuid, boolean visible) {
        try {
            mapper.upsertPreviewPreference(playerUuid, visible);
        } catch (RuntimeException e) {
            log.warn("setPreviewPreference failed (ignored): {}", e.toString());
        }
    }
}
