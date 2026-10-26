package io.paradaux.business.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;

import io.paradaux.business.model.Firm;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.model.config.FirmConfiguration;
import io.paradaux.business.services.FirmSalesNotificationService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.business.commands.resolvers.FirmName;
import io.paradaux.treasury.api.SalesQueryApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.api.market.SaleRow;
import io.paradaux.treasury.api.market.SalesQuery;
import io.paradaux.treasury.api.market.SalesSummary;
import io.paradaux.treasury.api.market.TopCustomer;
import io.paradaux.treasury.api.market.TopItem;
import org.bukkit.entity.Player;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * In-game firm sales over Treasury's {@code chestshop_sale} tracker (PAR-177 /
 * PAR-178): {@code /firm sales <firm> [page]} (paginated list) and
 * {@code /firm sales summary <firm> [days]} (aggregate report). Reads only,
 * gated to the firm's proprietor or staff with FINANCIAL/ADMIN — sales figures
 * are the firm's books, not public. Every route is @Async (DB/IPC).
 */
@Singleton
@Command({"db", "democracybusiness", "business", "firm", "company"})
public class SalesCommands implements CommandHandler {

    private static final int SALES_PAGE_SIZE = 10;
    private static final int DEFAULT_SUMMARY_DAYS = 30;
    private static final int TOP_N = 5;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MMM d HH:mm");

    private final SalesQueryApi sales;
    private final FirmSalesNotificationService salesNotifications;
    private final FirmService firms;
    private final FirmStaffService staff;
    private final TreasuryApi treasury;
    private final FirmConfiguration config;
    private final Message message;

    @Inject
    public SalesCommands(SalesQueryApi sales, FirmSalesNotificationService salesNotifications,
                         FirmService firms, FirmStaffService staff,
                         TreasuryApi treasury, FirmConfiguration config, Message message) {
        this.sales = sales;
        this.salesNotifications = salesNotifications;
        this.firms = firms;
        this.staff = staff;
        this.treasury = treasury;
        this.config = config;
        this.message = message;
    }

    // ---- /firm sales <firm> [page] (PAR-177) ----

    @Route("sales <firm>")
    @Permission("business.sales")
    @Async
    @Description("List a firm's ChestShop sales")
    public void sales(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        listSales(sender, firmRef, 1);
    }

    @Route("sales <firm> <page>")
    @Permission("business.sales")
    @Async
    @Description("List a firm's ChestShop sales (page)")
    public void salesPage(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("page") Integer page) {
        listSales(sender, firmRef, page);
    }

    // ---- /firm sales summary <firm> [days] (PAR-178) ----

    @Route("sales summary <firm>")
    @Permission("business.sales")
    @Async
    @Description("Aggregate sales report for a firm (default window)")
    public void salesSummary(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        summary(sender, firmRef, DEFAULT_SUMMARY_DAYS);
    }

    @Route("sales summary <firm> <days>")
    @Permission("business.sales")
    @Async
    @Description("Aggregate sales report for a firm over N days")
    public void salesSummaryDays(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("days") Integer days) {
        summary(sender, firmRef, days);
    }

    // ---- /firm sales export <firm> <days> (PAR-180) ----

    @Route("sales export <firm> <days>")
    @Permission("business.sales.export")
    @Async
    @Description("Get an economy-explorer link to a firm's sales report")
    public void salesExport(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("days") Integer days) {
        Firm firm = resolveAndGate(sender, firmRef);
        if (firm == null) return;

        int cap = config.getMaxSalesExportDays();
        if (days == null || days < 1) days = cap > 0 ? cap : DEFAULT_SUMMARY_DAYS;
        if (cap > 0 && days > cap) {
            message.send(sender, "business.sales.export.limit", "max", cap);
            return;
        }
        if (!config.hasSalesExplorerUrl()) {
            message.send(sender, "business.sales.export.unavailable");
            return;
        }

        // Deep-link to the explorer's per-firm market drilldown; its own
        // canSeeFinancials gate handles auth there, so no token/CSV to mint here.
        String base = config.getSalesExplorerUrl().replaceAll("/+$", "");
        String url = base + "/chestshop/firms/" + firm.getFirmId() + "?days=" + days;
        message.send(sender, "business.sales.export.link",
                "firm", firm.getDisplayName(), "days", days, "url", url);
    }

    // ---- /firm sales toggle <firm> (PAR-179) ----

    @Route("sales toggle <firm>")
    @Permission("business.sales.toggle")
    @Async
    @Description("Toggle real-time sale notifications for a firm")
    public void salesToggle(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        Firm firm = firms.getFirmByNameOrId(firmRef.value());
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }
        // Toggling notifications is an owner/admin setting, not just a read.
        if (!canManageSales(firm, sender)) {
            message.send(sender, "business.general.no-permission");
            return;
        }
        boolean enabled = salesNotifications.toggle(firm.getFirmId());
        message.send(sender, enabled ? "business.sales.toggle.on" : "business.sales.toggle.off",
                "firm", firm.getDisplayName());
    }

    // ---- internals ----

    private void listSales(Player sender, FirmName firmRef, int page) {
        Firm firm = resolveAndGate(sender, firmRef);
        if (firm == null) return;

        if (page < 1) page = 1;
        int offset = (page - 1) * SALES_PAGE_SIZE;
        SalesQuery query = SalesQuery.builder()
                .firmId(firm.getFirmId())
                .limit(SALES_PAGE_SIZE)
                .offset(offset)
                .build();

        long total = sales.countSales(query);
        List<SaleRow> rows = sales.listSales(query);
        if (rows.isEmpty()) {
            message.send(sender, "business.sales.empty", "firm", firm.getDisplayName());
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) total / SALES_PAGE_SIZE));
        message.send(sender, "business.sales.header",
                "firm", firm.getDisplayName(), "page", page, "totalPages", totalPages);

        for (SaleRow r : rows) {
            // Row direction is the customer's action; show it from the firm's side:
            // the customer BUYing means the firm SOLD, and vice versa.
            String action = "BUY".equals(r.getDirection()) ? "Sold" : "Bought";
            message.send(sender, "business.sales.line",
                    "time", TIME_FMT.format(r.getOccurredAt()),
                    "action", action,
                    "qty", r.getQuantity(),
                    "item", r.getItemName(),
                    "customer", r.getCustomerName() != null ? r.getCustomerName() : "Unknown",
                    "total", treasury.formatAmount(r.getTotalPrice()),
                    "tax", treasury.formatAmount(r.getTaxAmount()));
        }

        if (offset + rows.size() < total) {
            message.send(sender, "business.sales.next-page",
                    "firm", firm.getDisplayName(), "nextPage", page + 1);
        }
    }

    private void summary(Player sender, FirmName firmRef, int days) {
        Firm firm = resolveAndGate(sender, firmRef);
        if (firm == null) return;
        if (days < 1) days = DEFAULT_SUMMARY_DAYS;

        SalesQuery query = SalesQuery.builder().firmId(firm.getFirmId()).windowDays(days).build();
        SalesSummary s = sales.summarize(query, TOP_N);

        message.send(sender, "business.sales.summary.header", "firm", firm.getDisplayName(), "days", days);
        message.send(sender, "business.sales.summary.totals",
                "sales", s.getSaleCount(),
                "units", s.getTotalUnits(),
                "volume", treasury.formatAmount(s.getTotalVolume()),
                "tax", treasury.formatAmount(s.getTotalTax()));
        message.send(sender, "business.sales.summary.split",
                "sold", s.getBuyCount(), "soldVolume", treasury.formatAmount(s.getBuyVolume()),
                "bought", s.getSellCount(), "boughtVolume", treasury.formatAmount(s.getSellVolume()));

        List<TopItem> topItems = s.getTopItems();
        if (topItems != null && !topItems.isEmpty()) {
            message.send(sender, "business.sales.summary.top-items-header");
            for (TopItem it : topItems) {
                message.send(sender, "business.sales.summary.top-item",
                        "item", it.getItemName() != null ? it.getItemName() : it.getItemKey(),
                        "units", it.getUnits(),
                        "volume", treasury.formatAmount(it.getVolume()));
            }
        }

        List<TopCustomer> topCustomers = s.getTopCustomers();
        if (topCustomers != null && !topCustomers.isEmpty()) {
            message.send(sender, "business.sales.summary.top-customers-header");
            for (TopCustomer c : topCustomers) {
                message.send(sender, "business.sales.summary.top-customer",
                        "customer", c.getCustomerName() != null ? c.getCustomerName() : "Unknown",
                        "sales", c.getSaleCount(),
                        "volume", treasury.formatAmount(c.getVolume()));
            }
        }
    }

    /** Resolve an active firm and gate on proprietor / FINANCIAL / ADMIN; null on failure (message already sent). */
    private Firm resolveAndGate(Player sender, FirmName firmRef) {
        Firm firm = firms.getFirmByNameOrId(firmRef.value());
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return null;
        }
        if (!canViewSales(firm, sender)) {
            message.send(sender, "business.general.no-permission");
            return null;
        }
        return firm;
    }

    private boolean canViewSales(Firm firm, Player player) {
        return CommandSupport.canAccessFirmFinances(firms, staff, firm.getFirmId(), player.getUniqueId());
    }

    /** Managing the notification toggle is an owner/admin action, not just viewing. */
    private boolean canManageSales(Firm firm, Player player) {
        UUID uuid = player.getUniqueId();
        return firms.isProprietor(firm.getFirmId(), uuid)
                || staff.hasPermission(firm.getFirmId(), uuid, RolePermission.ADMIN);
    }
}
