package io.paradaux.business.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.business.threading.BusinessMessages;
import java.util.Optional;
import org.bukkit.entity.Player;

/** Command-layer finance authorization; failures send their response before returning. */
@Singleton
public final class FirmGate {
    private final FirmService firms;
    private final FirmStaffService staff;
    private final BusinessMessages message;

    @Inject
    public FirmGate(FirmService firms, FirmStaffService staff, BusinessMessages message) {
        this.firms = firms;
        this.staff = staff;
        this.message = message;
    }

    public Optional<Firm> resolveForFinances(Player sender, String reference) {
        return resolveForFinances(sender, reference, false);
    }

    /** The aggregate finance commands include the requested name in their missing-firm response. */
    public Optional<Firm> resolveNamedForFinances(Player sender, String reference) {
        return resolveForFinances(sender, reference, true);
    }

    private Optional<Firm> resolveForFinances(Player sender, String reference, boolean namedResponse) {
        Firm firm = firms.getFirmByNameOrId(reference);
        if (firm == null) {
            if (namedResponse) {
                message.send(sender, "business.firm.not-found", "firm", reference);
            } else {
                message.send(sender, "business.general.firm-not-found");
            }
            return Optional.empty();
        }
        return requireFinanceAccess(sender, firm) ? Optional.of(firm) : Optional.empty();
    }

    /** For transfers that validate the destination before checking source authorization. */
    public boolean requireFinanceAccess(Player sender, Firm firm) {
        if (CommandSupport.canAccessFirmFinances(firms, staff, firm.getFirmId(), sender.getUniqueId())) {
            return true;
        }
        message.send(sender, "business.general.no-permission");
        return false;
    }
}
