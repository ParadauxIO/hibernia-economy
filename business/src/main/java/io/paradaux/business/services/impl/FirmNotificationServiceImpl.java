package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.i18n.Message;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.business.services.FirmNotificationService;
import io.paradaux.business.services.FirmStaffService;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Default {@link FirmNotificationService}: fans a message out to the firm's online
 * members via {@link FirmStaffService#getOnlineEmployees} (which includes the
 * proprietor). Delivery is best-effort — any failure is logged and swallowed so a
 * notification can never break the operation that triggered it (PAR-94).
 */
@Slf4j
@Singleton
public class FirmNotificationServiceImpl implements FirmNotificationService {

    private final FirmStaffService staff;
    private final Message message;

    @Inject
    public FirmNotificationServiceImpl(FirmStaffService staff, Message message) {
        this.staff = staff;
        this.message = message;
    }

    @Override
    public void notifyFirm(int firmId, String messageKey, Object... placeholders) {
        notifyFirmExcept(firmId, null, messageKey, placeholders);
    }

    @Override
    public void notifyFirmExcept(int firmId, UUID excluded, String messageKey, Object... placeholders) {
        try {
            List<Player> recipients = staff.getOnlineEmployees(firmId).stream()
                    .filter(p -> excluded == null || !p.getUniqueId().equals(excluded))
                    .toList();
            if (recipients.isEmpty()) {
                return;
            }
            message.send(recipients, messageKey, placeholders);
        } catch (RuntimeException e) {
            // Notifications are best-effort; never let one break the triggering action.
            log.debug("Failed to notify firm {} ({}): {}", firmId, messageKey, e.toString());
        }
    }
}
