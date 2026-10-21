package io.paradaux.treasuryapi.services;

import static org.mockito.Mockito.*;

import io.paradaux.treasuryapi.mappers.DerivedGroupMapper;
import io.paradaux.treasuryapi.model.GroupSource;
import java.util.*;
import org.junit.jupiter.api.Test;

class DerivedGroupReconciliationTest {
    @Test
    void failureDoesNotPublishPartialMembershipOrBlockAnotherSourceAndRetryConverges() throws Exception {
        var mapper = mock(DerivedGroupMapper.class);
        var business = mock(BusinessGroupMembers.class);
        var realty = mock(RealtyGroupMembers.class);
        var firm = new GroupSource(1, "business", "7");
        var property = new GroupSource(1, "realty", UUID.randomUUID() + "/plot");
        UUID owner = UUID.randomUUID(), stale = UUID.randomUUID();
        when(mapper.listSources()).thenReturn(List.of(firm, property));
        when(business.resolve("7"))
                .thenThrow(new IllegalStateException("offline"))
                .thenReturn(Set.of(owner));
        when(realty.resolve(property.selector())).thenReturn(Set.of(owner));
        when(mapper.listMembers(property)).thenReturn(List.of(stale));
        when(mapper.listMembers(firm)).thenReturn(List.of(stale));
        var service = new DerivedGroupReconciliation(mapper, business, realty);
        service.reconcile();
        verify(mapper, never()).refresh(eq(firm), any());
        verify(mapper, never()).remove(eq(firm), any());
        verify(mapper).refresh(property, owner);
        verify(mapper).remove(property, stale);
        service.reconcile();
        verify(mapper).refresh(firm, owner);
        verify(mapper).remove(firm, stale);
        verify(mapper, times(2)).removeUnconfigured();
    }
}
