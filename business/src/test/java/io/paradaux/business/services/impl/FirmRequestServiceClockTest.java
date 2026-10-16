package io.paradaux.business.services.impl;

import io.paradaux.business.mappers.FirmRequestMapper;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Offer and transfer expiry are computed from the injected {@link Clock}, not the
 * ambient system time, so the 5-minute window is asserted exactly rather than with
 * {@code any(LocalDateTime.class)}.
 */
class FirmRequestServiceClockTest {

    // A zone with a non-zero offset, so a stray LocalDateTime.now() (system zone) or a
    // UTC conversion would both produce a different wall time than the one asserted.
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-03-29T00:30:00Z"), ZoneId.of("Pacific/Auckland"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    private final FirmRequestMapper requests = mock(FirmRequestMapper.class);
    private final FirmService firms = mock(FirmService.class);
    private final FirmStaffService staff = mock(FirmStaffService.class);
    private final FirmRequestServiceImpl svc = new FirmRequestServiceImpl(
            requests, firms, staff, mock(FirmAccountService.class), CLOCK);

    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @Test
    void offerEmployment_expiresFiveMinutesAfterTheClock() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(UUID.randomUUID()));
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(requests.createInvite(1, target.toString(), actor.toString(), NOW.plusMinutes(5))).thenReturn(1);

        svc.offerEmployment("Acme", target, actor);

        verify(requests).createInvite(1, target.toString(), actor.toString(), NOW.plusMinutes(5));
    }

    @Test
    void beginTransferProprietorship_expiresFiveMinutesAfterTheClock() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(actor));

        svc.beginTransferProprietorship("Acme", target, actor);

        verify(requests).createTransferRequest(eq(1), eq(target.toString()), anyString(), eq(NOW.plusMinutes(5)));
    }

    private static Firm firm(UUID proprietor) {
        Firm f = new Firm();
        f.setFirmId(1);
        f.setProprietorUuid(proprietor.toString());
        return f;
    }
}
