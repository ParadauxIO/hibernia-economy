package io.paradaux.treasury.api.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.paradaux.treasury.api.market.SalesQuery;
import io.paradaux.treasury.mappers.ChestShopSalesReadMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** The windowDays lower bound is derived from the injected {@link Clock}. */
class SalesQueryApiImplClockTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneId.of("America/Los_Angeles"));

    private final ChestShopSalesReadMapper mapper = mock(ChestShopSalesReadMapper.class);
    private final SalesQueryApiImpl api = new SalesQueryApiImpl(mapper, CLOCK);

    @Test
    void windowDays_isResolvedAgainstTheClock() {
        SalesQuery query = SalesQuery.builder().firmId(7).windowDays(30).build();

        api.countSales(query);

        verify(mapper).countSales(any(), eq(LocalDateTime.now(CLOCK).minusDays(30)));
    }

    @Test
    void noWindow_meansAllTime() {
        SalesQuery query = SalesQuery.builder().firmId(7).build();

        api.listSales(query);

        verify(mapper).listSales(any(), isNull());
    }
}
