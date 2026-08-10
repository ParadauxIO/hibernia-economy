package io.paradaux.business.services.impl;

import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.business.services.FirmStaffService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirmNotificationServiceImplTest {

    @Mock FirmStaffService staff;
    @Mock Message message;

    private FirmNotificationServiceImpl svc() {
        return new FirmNotificationServiceImpl(staff, message);
    }

    private static Player player(UUID id) {
        Player p = org.mockito.Mockito.mock(Player.class);
        lenient().when(p.getUniqueId()).thenReturn(id);
        return p;
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyFirm_sendsToAllOnlineMembers() {
        Player a = player(UUID.randomUUID());
        Player b = player(UUID.randomUUID());
        when(staff.getOnlineEmployees(1)).thenReturn(List.of(a, b));

        svc().notifyFirm(1, "business.notify.transfer.incoming", "amount", "$5");

        ArgumentCaptor<List<Player>> recipients = ArgumentCaptor.forClass(List.class);
        verify(message).send(recipients.capture(), eq("business.notify.transfer.incoming"),
                eq("amount"), eq("$5"));
        assertThat(recipients.getValue()).containsExactlyInAnyOrder(a, b);
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyFirmExcept_filtersOutTheActor() {
        UUID actor = UUID.randomUUID();
        Player actorPlayer = player(actor);
        Player other = player(UUID.randomUUID());
        when(staff.getOnlineEmployees(1)).thenReturn(List.of(actorPlayer, other));

        svc().notifyFirmExcept(1, actor, "business.notify.transfer.incoming");

        ArgumentCaptor<List<Player>> recipients = ArgumentCaptor.forClass(List.class);
        verify(message).send(recipients.capture(), eq("business.notify.transfer.incoming"));
        assertThat(recipients.getValue()).containsExactly(other);
    }

    @Test
    void noOnlineRecipients_doesNotSend() {
        when(staff.getOnlineEmployees(1)).thenReturn(List.of());
        svc().notifyFirm(1, "business.notify.transfer.incoming");
        verify(message, never()).send(any(List.class), any(), any());
    }

    @Test
    void allRecipientsExcluded_doesNotSend() {
        UUID actor = UUID.randomUUID();
        Player actorPlayer = player(actor);
        when(staff.getOnlineEmployees(1)).thenReturn(List.of(actorPlayer));
        svc().notifyFirmExcept(1, actor, "business.notify.transfer.incoming");
        verify(message, never()).send(any(List.class), any(), any());
    }

    @Test
    void deliveryIsBestEffort_swallowsExceptions() {
        // A resolution failure (e.g. firm vanished) must not propagate to the caller.
        when(staff.getOnlineEmployees(1)).thenThrow(new RuntimeException("boom"));
        svc().notifyFirm(1, "business.notify.transfer.incoming");
        verify(message, never()).send(any(List.class), any(), any());
    }
}
