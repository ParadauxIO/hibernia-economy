package io.paradaux.treasuryapi.services;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.paradaux.business.api.*;
import io.paradaux.business.model.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BusinessGroupMembersTest {
    @Test
    void includesOwnerAndFinancialOrAdminStaffAndRevokesArchivedFirm() {
        BusinessApi api = mock(BusinessApi.class);
        FirmApi firms = mock(FirmApi.class);
        StaffApi staff = mock(StaffApi.class);
        when(api.firms()).thenReturn(firms);
        when(api.staff()).thenReturn(staff);
        UUID owner = UUID.randomUUID(),
                finance = UUID.randomUUID(),
                admin = UUID.randomUUID(),
                ordinary = UUID.randomUUID();
        Firm firm = new Firm(null, null, owner, null, null, null, null, null, null);
        when(firms.getFirmById(7)).thenReturn(firm);
        when(staff.getEmployees(7)).thenReturn(List.of(employee(finance), employee(admin), employee(ordinary)));
        when(staff.hasPermission(7, finance, RolePermission.FINANCIAL)).thenReturn(true);
        when(staff.hasPermission(7, admin, RolePermission.ADMIN)).thenReturn(true);
        var resolver = new BusinessGroupMembers(api);
        assertThat(resolver.resolve("7")).containsExactlyInAnyOrder(owner, finance, admin);
        when(staff.getEmployees(7)).thenReturn(List.of(employee(ordinary)));
        assertThat(resolver.resolve("7")).containsExactly(owner);
        when(firms.getFirmById(7)).thenReturn(new Firm(7, null, owner, null, null, null, true, null, null));
        assertThat(resolver.resolve("7")).isEmpty();
        assertThat(resolver.resolve("8")).isEmpty();
        assertThatThrownBy(() -> resolver.resolve("0")).isInstanceOf(IllegalArgumentException.class);
    }

    private FirmEmployee employee(UUID id) {
        return new FirmEmployee(7, id, null, null, null, null, null, true);
    }
}
