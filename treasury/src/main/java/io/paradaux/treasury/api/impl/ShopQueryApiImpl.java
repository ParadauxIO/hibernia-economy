package io.paradaux.treasury.api.impl;

import com.google.inject.Inject;
import io.paradaux.treasury.api.ShopQueryApi;
import io.paradaux.treasury.api.market.ShopLocation;
import io.paradaux.treasury.api.market.ShopResult;
import io.paradaux.treasury.api.market.ShopSearchQuery;
import io.paradaux.treasury.mappers.ShopQueryMapper;
import org.mybatis.guice.transactional.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Reads the live shop registry ({@code chestshop_shop}) for the in-ChestShop
 * {@code /find} search + hologram loader (PAR-166 epic). Thin: it filters on the
 * indexed axes and returns the matching rows; ChestShop applies shop-type /
 * hide-empty-full filtering, distance and sorting client-side.
 */
public class ShopQueryApiImpl implements ShopQueryApi {

    private static final Logger log = LoggerFactory.getLogger(ShopQueryApiImpl.class);

    private final ShopQueryMapper mapper;

    @Inject
    public ShopQueryApiImpl(ShopQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public List<ShopResult> searchShops(ShopSearchQuery query) {
        if (query.getItemKey() == null || query.getItemKey().isBlank()) {
            throw new IllegalArgumentException("ShopSearchQuery requires an itemKey");
        }
        int limit = Math.max(1, query.getLimit());
        List<ShopResult> results = mapper.searchShops(
                query.getItemKey(), query.isFuzzy(), query.getWorld(), limit);
        if (results.size() >= limit) {
            // Don't silently truncate — a popular item hitting the cap is worth a line.
            log.warn("searchShops hit the {}-row cap for itemKey={} (fuzzy={}, world={}); "
                            + "results may be incomplete", limit, query.getItemKey(),
                    query.isFuzzy(), query.getWorld());
        }
        return results;
    }

    @Override
    @Transactional
    public List<ShopResult> shopsInChunk(String world, int chunkX, int chunkZ) {
        int minX = chunkX << 4, minZ = chunkZ << 4;
        return mapper.shopsInChunk(world, minX, minX + 15, minZ, minZ + 15);
    }

    @Override
    @Transactional
    public List<ShopLocation> activeShopLocations(String world) {
        return mapper.activeShopLocations(world);
    }

    @Override
    @Transactional
    public List<String> matchingItemKeys(String substring, int limit) {
        return mapper.matchingItemKeys(substring == null ? "" : substring, Math.max(1, limit));
    }

    @Override
    @Transactional
    public boolean previewVisible(UUID playerUuid, boolean defaultVisible) {
        Boolean stored = mapper.previewPreference(playerUuid);
        return stored == null ? defaultVisible : stored;
    }
}
