package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.mappers.FirmMapper;
import io.paradaux.business.mappers.FirmRequestMapper;
import io.paradaux.business.mappers.FirmStaffMapper;
import io.paradaux.business.model.internal.Firm;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.utils.StringUtils;
import java.util.Objects;
import java.util.UUID;
import org.mybatis.guice.transactional.Transactional;

/** Separate Guice transaction boundary: no Treasury or chat side effects before commit. */
@Singleton
public class FirmOwnershipService {
    private final FirmMapper firms;
    private final FirmStaffMapper staff;
    private final FirmRequestMapper requests;

    @Inject
    public FirmOwnershipService(FirmMapper firms, FirmStaffMapper staff, FirmRequestMapper requests) {
        this.firms = firms;
        this.staff = staff;
        this.requests = requests;
    }

    @Transactional
    public void transfer(int firmId, UUID newOwner, UUID actor) {
        Objects.requireNonNull(newOwner, "newOwner");
        Objects.requireNonNull(actor, "actor");
        Firm current = firms.lockFirm(firmId);
        if (current == null || Boolean.TRUE.equals(current.getArchived())) {
            throw new BadCommandException("Cannot transfer ownership of an archived or missing firm.");
        }
        Firm update = new Firm();
        update.setFirmId(firmId);
        update.setProprietorUuid(newOwner.toString());
        if (firms.updateFirm(update) == 0) {
            throw new BadCommandException("Cannot transfer ownership of an archived or missing firm.");
        }
        // End employee roles for both proprietors, including stale outgoing-owner
        // employment. Preserve their history and attribute the change to the admin.
        staff.endCurrentEmployment(firmId, newOwner.toString(), actor.toString());
        staff.endCurrentEmployment(firmId, current.getProprietorUuid(), actor.toString());
        requests.cancelActiveTransfers(firmId);
        requests.recordAdminOverride(
                firmId, current.getProprietorUuid(), newOwner.toString(), actor.toString(), StringUtils.random32());
    }
}
