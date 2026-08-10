package io.paradaux.business.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmBalanceTaxService;
import io.paradaux.business.services.FirmPropertyService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.commands.resolvers.FirmName;
import io.paradaux.treasury.api.TreasuryApi;
import org.bukkit.entity.Player;

import java.math.BigDecimal;

@Singleton
@Command({"db", "democracybusiness", "business", "firm", "company"})
public class TaxCommands implements CommandHandler {

    private static final String EXEMPT_KEY = "balance-tax.exempt";

    private final FirmService firmService;
    private final FirmPropertyService firmPropertyService;
    private final FirmBalanceTaxService balanceTaxService;
    private final TreasuryApi treasuryApi;
    private final Message message;

    @Inject
    public TaxCommands(FirmService firmService,
                       FirmPropertyService firmPropertyService,
                       FirmBalanceTaxService balanceTaxService,
                       TreasuryApi treasuryApi,
                       Message message) {
        this.firmService = firmService;
        this.firmPropertyService = firmPropertyService;
        this.balanceTaxService = balanceTaxService;
        this.treasuryApi = treasuryApi;
        this.message = message;
    }

    @Route("tax exempt <firm> <exempt>")
    @Permission("business.tax.exempt")
    @Async
    @Description("Set or clear the balance-tax exemption for a firm")
    public void setExempt(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("exempt") String exempt) {
        String firm = firmRef.value();
        Firm f = firmService.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }

        Boolean parsed = parseBoolean(exempt);
        if (parsed == null) {
            message.send(sender, "business.tax.exempt.invalid", "value", exempt);
            return;
        }

        firmPropertyService.setBoolean(f.getFirmId(), EXEMPT_KEY, parsed);
        message.send(sender, "business.tax.exempt.success",
            "firm", f.getDisplayName(),
            "exempt", parsed ? "exempt" : "not exempt");
    }

    @Route("tax info <firm>")
    @Permission("business.tax.info")
    @Async
    @Description("Show tax status and estimated weekly tax for a firm")
    public void taxInfo(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        String firm = firmRef.value();
        Firm f = firmService.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }

        boolean exempt = firmPropertyService.getBoolean(f.getFirmId(), EXEMPT_KEY).orElse(false);
        // Economy arithmetic (balance × rate, 2dp rounding) lives in the service; the
        // handler only formats for display (plugin-architecture/0006).
        FirmBalanceTaxService.WeeklyTaxEstimate estimate = balanceTaxService.estimateWeeklyTax(f.getFirmId());

        String balanceFmt = treasuryApi.formatAmount(estimate.totalBalance());
        String taxFmt = treasuryApi.formatAmount(estimate.estimatedTax());
        String ratePct = estimate.rate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
        String exemptStatus = exempt ? "<green>Exempt</green>" : "<red>Not Exempt</red>";

        message.send(sender, "business.tax.info",
            "firm", f.getDisplayName(),
            "exempt", exemptStatus,
            "balance", balanceFmt,
            "rate", ratePct,
            "estimatedTax", taxFmt);
    }

    private static Boolean parseBoolean(String token) {
        if (token == null) return null;
        String t = token.trim().toLowerCase();
        return switch (t) {
            case "true", "t", "yes", "y", "1", "on", "enable", "enabled" -> Boolean.TRUE;
            case "false", "f", "no", "n", "0", "off", "disable", "disabled" -> Boolean.FALSE;
            default -> null;
        };
    }
}
