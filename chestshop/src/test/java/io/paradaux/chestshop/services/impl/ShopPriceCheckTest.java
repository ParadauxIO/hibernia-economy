package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.model.ShopCreation;
import io.paradaux.chestshop.utils.PriceUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link ShopServiceImpl#checkPrice} — the shop-sign price-line parser/normaliser
 * that was the standalone {@code PriceChecker} before the creation pipeline was folded
 * into the service (PAR-282). Created by Andrzej Pomirski (Acrobot).
 */
class ShopPriceCheckTest {

    private final ShopServiceImpl shops = newShopService();

    private static ShopServiceImpl newShopService() {
        ChestShopConfiguration config = mock(ChestShopConfiguration.class);
        when(config.getPricePrecision()).thenReturn(2);
        return new ShopServiceImpl(null, null, null, null, null, null, null, config, null, null, null);
    }

    static String[] getPriceString(String prices) {
        return new String[]{null, null, prices, null};
    }

    @Test
    public void testLegalBuyPrice() {
        ShopCreation event = new ShopCreation(null, null, getPriceString("B 1"));
        shops.checkPrice(event);
        assertEquals(PriceUtil.getExactBuyPrice(SignService.getPrice(event.getSignLines())), BigDecimal.valueOf(1));

        event = new ShopCreation(null, null, getPriceString("B FREE"));
        shops.checkPrice(event);
        assertEquals(PriceUtil.FREE, PriceUtil.getExactBuyPrice(SignService.getPrice(event.getSignLines())));

        assertFalse(event.isCancelled());
    }

    @Test
    public void testLegalBuyPriceWithMultipliers() {
        assertEquals(BigDecimal.valueOf(1000), getExactBuyPrice(createEventFromString("B 1K")));
        assertEquals(BigDecimal.valueOf(10_000_000), getExactBuyPrice(createEventFromString("B 10M")));
        assertEquals(BigDecimal.valueOf(1000), getExactBuyPrice(createEventFromString("1K")));
    }

    @Test
    public void testLegalSellPriceWithMultipliers() {
        assertEquals(BigDecimal.valueOf(1000), getExactSellPrice(createEventFromString("S 1K")));
        assertEquals(BigDecimal.valueOf(10_000_000), getExactSellPrice(createEventFromString("S 10M")));
        assertEquals(BigDecimal.valueOf(1000), getExactSellPrice(createEventFromString("1K S")));
    }

    @Test
    public void testLegalSellPrice() {
        ShopCreation event = new ShopCreation(null, null, getPriceString("S 1"));
        shops.checkPrice(event);
        assertEquals(PriceUtil.getExactSellPrice(SignService.getPrice(event.getSignLines())), BigDecimal.valueOf(1));

        event = new ShopCreation(null, null, getPriceString("S FREE"));
        shops.checkPrice(event);
        assertEquals(PriceUtil.getExactSellPrice(SignService.getPrice(event.getSignLines())), PriceUtil.FREE);

        assertFalse(event.isCancelled());
    }

    @Test
    public void testIllegalBuyPrice() {
        assertTrue(createEventFromString("10 B 1").isCancelled());
        assertTrue(createEventFromString("1EB100000000000").isCancelled());
    }

    @Test
    public void testLegalBuyAndSellPrices() {
        ShopCreation event = createEventFromString("B 2:S 1");
        assertEquals(getExactSellPrice(event), BigDecimal.valueOf(1));
        assertEquals(getExactBuyPrice(event), BigDecimal.valueOf(2));
        assertFalse(event.isCancelled());

        event = createEventFromString("2 B:S 1");
        assertEquals(getExactSellPrice(event), BigDecimal.valueOf(1));
        assertEquals(getExactBuyPrice(event), BigDecimal.valueOf(2));
        assertFalse(event.isCancelled());

        event = createEventFromString("2 B:1 S");
        assertEquals(getExactSellPrice(event), BigDecimal.valueOf(1));
        assertEquals(getExactBuyPrice(event), BigDecimal.valueOf(2));
        assertFalse(event.isCancelled());
    }

    @Test
    public void testLegalBuyAndSellPricesWithMultipliers() {
        ShopCreation event = createEventFromString("B 2M:S 1K");
        assertEquals(BigDecimal.valueOf(1000), getExactSellPrice(event));
        assertEquals(BigDecimal.valueOf(2_000_000), getExactBuyPrice(event));
        assertFalse(event.isCancelled());

        event = createEventFromString("2K B:S 1M");
        assertEquals(BigDecimal.valueOf(1_000_000), getExactSellPrice(event));
        assertEquals(BigDecimal.valueOf(2000), getExactBuyPrice(event));
        assertFalse(event.isCancelled());

        event = createEventFromString("2K B:1 S");
        assertEquals(BigDecimal.valueOf(1), getExactSellPrice(event));
        assertEquals(BigDecimal.valueOf(2000), getExactBuyPrice(event));
        assertFalse(event.isCancelled());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1 B:S -1M",
            "S 1MK",
            "B -1K : S10K",
            "B1Z"
    })
    public void testIllegalBuyAndSellPricesWithMultipliers(String line) {
        assertTrue(createEventFromString(line).isCancelled());
    }

    @Test
    public void testIllegalPrices() {
        assertTrue(createEventFromString("BS 1").isCancelled());
        assertTrue(createEventFromString("B 1S0").isCancelled());
        assertTrue(createEventFromString("B -100").isCancelled());

        assertEquals(PriceUtil.NO_PRICE, PriceUtil.getExactBuyPrice("5 B 5"));
        assertEquals(PriceUtil.NO_PRICE, PriceUtil.getExactSellPrice("5 S 5"));
        assertEquals(PriceUtil.NO_PRICE, PriceUtil.getExactBuyPrice("5 B 5:5 S 5"));
        assertEquals(PriceUtil.NO_PRICE, PriceUtil.getExactSellPrice("5 B 5:5 S 5"));
    }

    @Test
    public void testRemovingTrailingZeroes() {
        assertEquals("S.75", normalisedPrice("S.7500000000"));
        assertEquals("S7500000000", normalisedPrice("S7500000000"));
        assertEquals("S.75:B.75", normalisedPrice("S.75000:B.75000"));
        assertEquals("S75000:B.75", normalisedPrice("S75000:B.75000"));
    }

    private String normalisedPrice(String priceLine) {
        ShopCreation event = createEventFromString(priceLine);
        return SignService.getPrice(event.getSignLines());
    }

    private static BigDecimal getExactSellPrice(ShopCreation event) {
        return PriceUtil.getExactSellPrice(SignService.getPrice(event.getSignLines()));
    }

    private static BigDecimal getExactBuyPrice(ShopCreation event) {
        return PriceUtil.getExactBuyPrice(SignService.getPrice(event.getSignLines()));
    }

    private ShopCreation createEventFromString(String priceString) {
        ShopCreation event = new ShopCreation(null, null, getPriceString(priceString));
        shops.checkPrice(event);
        return event;
    }
}
