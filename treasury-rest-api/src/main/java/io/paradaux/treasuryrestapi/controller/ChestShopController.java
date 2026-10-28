package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.dto.ChestShopItemDetailResponse;
import io.paradaux.treasuryrestapi.dto.ChestShopItemsResponse;
import io.paradaux.treasuryrestapi.dto.ChestShopMarketStatsResponse;
import io.paradaux.treasuryrestapi.dto.ChestShopShopsResponse;
import io.paradaux.treasuryrestapi.ratelimit.RateLimit;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.service.ChestShopService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only ChestShop market data for authenticated API consumers (price trackers,
 * shop finders and dashboards). Every route requires an active API-key Bearer JWT.
 * All key types may read these market views; the rate limiter accounts for usage
 * per key owner, using the personal or business request budget.
 *
 * <p>Aggregate endpoints are cached in the service; the shop directory is a single
 * index-served table with a hard page cap.
 */
@RestController
@RequestMapping("/api/v1/chestshop")
public class ChestShopController {

    private static final Logger log = LoggerFactory.getLogger(ChestShopController.class);

    private final ChestShopService chestShopService;

    public ChestShopController(ChestShopService chestShopService) {
        this.chestShopService = chestShopService;
    }

    /**
     * GET /api/v1/chestshop/shops
     * Live shop directory. All filters optional: {@code itemKey}, {@code material},
     * {@code firmId}, {@code buyable} (offers a buy price), {@code inStock}
     * (admin or stock &gt; 0), {@code search} (item name/material). Paged.
     */
    @GetMapping("/shops")
    @RateLimit(personalPerMinute = 120, businessPerMinute = 600)
    public ResponseEntity<ChestShopShopsResponse> listShops(
            @AuthenticationPrincipal VerifiedToken token,
            @RequestParam(required = false) String itemKey,
            @RequestParam(required = false) String material,
            @RequestParam(required = false) Integer firmId,
            @RequestParam(defaultValue = "false") boolean buyable,
            @RequestParam(defaultValue = "false") boolean inStock,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit) {

        ChestShopShopsResponse response =
                chestShopService.listShops(itemKey, material, firmId, buyable, inStock, search, page, limit);
        log.info(
                "GET /chestshop/shops keyId={} itemKey={} material={} firmId={} buyable={} inStock={} → {} of {} (page {}/{})",
                token.keyId(),
                itemKey,
                material,
                firmId,
                buyable,
                inStock,
                response.items().size(),
                response.totalItems(),
                response.page(),
                response.totalPages());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/chestshop/items
     * Item directory with all-time aggregate trade stats. Optional {@code search}
     * over item name / material / key. Paged. Cached.
     */
    @GetMapping("/items")
    @RateLimit(personalPerMinute = 120, businessPerMinute = 600)
    public ResponseEntity<ChestShopItemsResponse> listItems(
            @AuthenticationPrincipal VerifiedToken token,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit) {

        ChestShopItemsResponse response = chestShopService.listItems(search, page, limit);
        log.info(
                "GET /chestshop/items keyId={} search={} → {} of {} (page {}/{})",
                token.keyId(),
                search,
                response.items().size(),
                response.totalItems(),
                response.page(),
                response.totalPages());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/chestshop/items/{itemKey}
     * Per-item detail: windowed stats (default 30d), cheapest live shops, and a
     * daily price/volume series. Cached per (itemKey, days).
     */
    @GetMapping("/items/{itemKey}")
    @RateLimit(personalPerMinute = 120, businessPerMinute = 600)
    public ResponseEntity<ChestShopItemDetailResponse> getItem(
            @AuthenticationPrincipal VerifiedToken token,
            @PathVariable String itemKey,
            @RequestParam(defaultValue = "30") int days) {

        ChestShopItemDetailResponse response = chestShopService.getItem(itemKey, days);
        log.info(
                "GET /chestshop/items/{} keyId={} days={} → trades={} activeShops={}",
                itemKey,
                token.keyId(),
                days,
                response.tradeCount(),
                response.activeShopCount());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/chestshop/stats
     * Global market totals (sales, volume, distinct items, active shops). Cached.
     */
    @GetMapping("/stats")
    @RateLimit(personalPerMinute = 120, businessPerMinute = 600)
    public ResponseEntity<ChestShopMarketStatsResponse> stats(@AuthenticationPrincipal VerifiedToken token) {
        ChestShopMarketStatsResponse response = chestShopService.marketStats();
        log.info(
                "GET /chestshop/stats keyId={} → sales={} volume={} items={} shops={}",
                token.keyId(),
                response.totalSales(),
                response.totalVolume(),
                response.distinctItems(),
                response.activeShops());
        return ResponseEntity.ok(response);
    }
}
