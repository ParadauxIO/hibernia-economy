package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.model.ShopCreation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Free ($0) shops are rejected at creation by default, and permitted when
 * {@code ALLOW_FREE_SHOPS} is set (PAR-88). Exercises {@link ShopServiceImpl#checkPrice}
 * (price-line normalise) then {@link ShopServiceImpl#rejectFreeShop} (the free-shop gate),
 * the steps that were {@code PriceChecker}/{@code FreePriceChecker} before the creation
 * pipeline was folded into the service (PAR-282).
 */
class ShopFreePriceCheckTest {

    private final ChestShopConfiguration config = mock(ChestShopConfiguration.class);
    private final ShopServiceImpl shops = new ShopServiceImpl(null, null, null, null, null, null, null, config, null, null, null);

    {
        when(config.getPricePrecision()).thenReturn(2);
        lenient().when(config.isAllowFreeShops()).thenReturn(false);
    }

    @AfterEach
    void resetConfig() {
        when(config.isAllowFreeShops()).thenReturn(false); // restore the default for other tests
    }

    private ShopCreation run(String priceLine) {
        ShopCreation event = new ShopCreation(null, null, new String[]{null, null, priceLine, null});
        shops.checkPrice(event);      // normalise the price line first (LOWEST)
        shops.rejectFreeShop(event);  // then the free-shop gate (NORMAL)
        return event;
    }

    @Test
    public void freeBuyShop_isRejectedByDefault() {
        assertTrue(run("B 0").isCancelled());
        assertTrue(run("B free").isCancelled());
    }

    @Test
    public void freeSellShop_isRejectedByDefault() {
        assertTrue(run("S 0").isCancelled());
    }

    @Test
    public void freeShop_isAllowedWhenConfigEnabled() {
        when(config.isAllowFreeShops()).thenReturn(true);
        assertFalse(run("B 0").isCancelled());
        assertFalse(run("S 0").isCancelled());
        assertFalse(run("B 0:S 0").isCancelled());
    }

    @Test
    public void pricedShop_isUnaffected() {
        assertFalse(run("B 5").isCancelled());
        assertFalse(run("B 5:S 1").isCancelled());
    }
}
