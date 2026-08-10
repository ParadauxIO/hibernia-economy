package io.paradaux.treasury.commands;

import com.google.inject.Inject;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.SalesQueryApi;
import io.paradaux.treasury.api.market.SaleRow;
import io.paradaux.treasury.api.market.SalesQuery;
import io.paradaux.treasury.api.market.SalesSummary;
import io.paradaux.treasury.api.market.TopItem;
import io.paradaux.treasury.services.AccountService;
import org.bukkit.entity.Player;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * {@code /sales} — a player's own ChestShop sales (PAR-181), over Treasury's
 * chestshop_sale tracker scoped to {@code shop_owner_uuid_bin = self}. The
 * personal counterpart to {@code /firm sales}. A player only ever sees their own
 * sales, so no extra gating is needed beyond being the sender.
 */
@Command({"sales"})
@Permission("treasury.sales")
public class SalesCommand implements CommandHandler {

    private static final int PAGE_SIZE = 10;
    private static final int DEFAULT_SUMMARY_DAYS = 30;
    private static final int TOP_N = 5;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MMM d HH:mm");

    private final SalesQueryApi sales;
    private final AccountService accountService;
    private final Message message;

    @Inject
    public SalesCommand(SalesQueryApi sales, AccountService accountService, Message message) {
        this.sales = sales;
        this.accountService = accountService;
        this.message = message;
    }

    @Route("help")
    @Description("Show /sales help")
    public void help(@Sender Player sender) {
        message.send(sender, "treasury.help.sales");
    }

    @Route("")
    @Async
    @Description("List your own ChestShop sales")
    public void sales(@Sender Player sender) {
        list(sender, 1);
    }

    @Route("<page>")
    @Async
    @Description("List your own ChestShop sales (page)")
    public void salesPage(@Sender Player sender, @Arg("page") int page) {
        list(sender, page);
    }

    @Route("summary")
    @Async
    @Description("Aggregate report of your own sales (default window)")
    public void summary(@Sender Player sender) {
        summary(sender, DEFAULT_SUMMARY_DAYS);
    }

    @Route("summary <days>")
    @Async
    @Description("Aggregate report of your own sales over N days")
    public void summaryDays(@Sender Player sender, @Arg("days") int days) {
        summary(sender, days);
    }

    private void list(Player sender, int page) {
        if (page < 1) page = 1;
        int offset = (page - 1) * PAGE_SIZE;
        SalesQuery query = SalesQuery.builder()
                .ownerUuid(sender.getUniqueId())
                .limit(PAGE_SIZE)
                .offset(offset)
                .build();

        long total = sales.countSales(query);
        List<SaleRow> rows = sales.listSales(query);
        if (rows.isEmpty()) {
            message.send(sender, "treasury.sales.empty");
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
        message.send(sender, "treasury.sales.header", "page", page, "totalPages", totalPages);

        for (SaleRow r : rows) {
            // Row direction is the customer's action; the shop owner's side is the
            // inverse — a customer BUY means the owner Sold.
            String action = "BUY".equals(r.getDirection()) ? "Sold" : "Bought";
            message.send(sender, "treasury.sales.line",
                    "time", TIME_FMT.format(r.getOccurredAt()),
                    "action", action,
                    "qty", r.getQuantity(),
                    "item", r.getItemName(),
                    "customer", r.getCustomerName() != null ? r.getCustomerName() : "Unknown",
                    "total", accountService.formatAmount(r.getTotalPrice()),
                    "tax", accountService.formatAmount(r.getTaxAmount()));
        }

        if (offset + rows.size() < total) {
            message.send(sender, "treasury.sales.next-page", "nextPage", page + 1);
        }
    }

    private void summary(Player sender, int days) {
        if (days < 1) days = DEFAULT_SUMMARY_DAYS;
        SalesQuery query = SalesQuery.builder()
                .ownerUuid(sender.getUniqueId())
                .windowDays(days)
                .build();
        SalesSummary s = sales.summarize(query, TOP_N);

        message.send(sender, "treasury.sales.summary.header", "days", days);
        message.send(sender, "treasury.sales.summary.totals",
                "sales", s.getSaleCount(),
                "units", s.getTotalUnits(),
                "volume", accountService.formatAmount(s.getTotalVolume()),
                "tax", accountService.formatAmount(s.getTotalTax()));
        message.send(sender, "treasury.sales.summary.split",
                "sold", s.getBuyCount(), "soldVolume", accountService.formatAmount(s.getBuyVolume()),
                "bought", s.getSellCount(), "boughtVolume", accountService.formatAmount(s.getSellVolume()));

        List<TopItem> topItems = s.getTopItems();
        if (topItems != null && !topItems.isEmpty()) {
            message.send(sender, "treasury.sales.summary.top-items-header");
            for (TopItem it : topItems) {
                message.send(sender, "treasury.sales.summary.top-item",
                        "item", it.getItemName() != null ? it.getItemName() : it.getItemKey(),
                        "units", it.getUnits(),
                        "volume", accountService.formatAmount(it.getVolume()));
            }
        }
    }
}
