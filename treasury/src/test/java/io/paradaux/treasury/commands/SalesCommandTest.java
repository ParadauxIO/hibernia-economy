package io.paradaux.treasury.commands;

import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.SalesQueryApi;
import io.paradaux.treasury.api.market.SalesQuery;
import io.paradaux.treasury.api.market.SalesSummary;
import io.paradaux.treasury.services.AccountService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code /sales} takes its page and day window straight from chat; both must be clamped. */
@ExtendWith(MockitoExtension.class)
class SalesCommandTest {

    @Mock SalesQueryApi sales;
    @Mock AccountService accountService;
    @Mock Message message;
    @Mock Player sender;

    private SalesCommand command;

    @BeforeEach
    void setUp() {
        command = new SalesCommand(sales, accountService, message);
        when(sender.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(accountService.formatAmount(any())).thenReturn("$0.00");
    }

    private SalesQuery listedQuery() {
        ArgumentCaptor<SalesQuery> query = ArgumentCaptor.forClass(SalesQuery.class);
        verify(sales).listSales(query.capture());
        return query.getValue();
    }

    @Test
    void salesPage_hugePage_isClampedSoTheOffsetCannotOverflowNegative() {
        when(sales.listSales(any())).thenReturn(List.of());

        // (Integer.MAX_VALUE - 1) * 10 overflows int to a negative OFFSET — a SQL error.
        command.salesPage(sender, Integer.MAX_VALUE);

        assertThat(listedQuery().getOffset()).isEqualTo(999_990);
        verify(message).send(sender, "treasury.sales.empty");
    }

    @Test
    void salesPage_nonPositivePage_fallsBackToTheFirstPage() {
        when(sales.listSales(any())).thenReturn(List.of());

        command.salesPage(sender, -3);

        assertThat(listedQuery().getOffset()).isZero();
    }

    @Test
    void salesPage_ordinaryPage_isUnchanged() {
        when(sales.listSales(any())).thenReturn(List.of());

        command.salesPage(sender, 3);

        assertThat(listedQuery().getOffset()).isEqualTo(20);
    }

    private static SalesSummary emptySummary() {
        return new SalesSummary(0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                0, BigDecimal.ZERO, 0, BigDecimal.ZERO, null, null);
    }

    private SalesQuery summarizedQuery() {
        ArgumentCaptor<SalesQuery> query = ArgumentCaptor.forClass(SalesQuery.class);
        verify(sales).summarize(query.capture(), eq(5));
        return query.getValue();
    }

    @Test
    void summaryDays_hugeWindow_isClamped() {
        when(sales.summarize(any(), eq(5))).thenReturn(emptySummary());

        command.summaryDays(sender, Integer.MAX_VALUE);

        assertThat(summarizedQuery().getWindowDays()).isEqualTo(3650);
        verify(message).send(sender, "treasury.sales.summary.header", "days", 3650);
    }

    @Test
    void summaryDays_nonPositiveWindow_usesTheDefault() {
        when(sales.summarize(any(), eq(5))).thenReturn(emptySummary());

        command.summaryDays(sender, 0);

        assertThat(summarizedQuery().getWindowDays()).isEqualTo(30);
    }
}
