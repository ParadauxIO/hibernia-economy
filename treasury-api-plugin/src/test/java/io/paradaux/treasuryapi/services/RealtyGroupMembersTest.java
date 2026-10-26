package io.paradaux.treasuryapi.services;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RealtyGroupMembersTest {
    // Public API accessor shapes verified against Realty submodule 95eba03.
    public record Info(Freehold freehold, Lease leasehold) {}

    public record Freehold(UUID titleHolderId) {}

    public record Lease(UUID tenantId, LocalDateTime endDate, LocalDateTime terminationEffectiveDate) {}

    @Test
    void resolvesOfflineHoldersAndExcludesExpiredOrTerminatedTenants() throws Exception {
        UUID owner = UUID.randomUUID(), tenant = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 12, 0);
        var freehold = new Freehold(owner);
        assertThat(RealtyGroupMembers.members(new Info(freehold, new Lease(tenant, now.plusDays(1), null)), now))
                .containsExactlyInAnyOrder(owner, tenant);
        assertThat(RealtyGroupMembers.members(new Info(freehold, new Lease(tenant, now, null)), now))
                .containsExactly(owner);
        assertThat(RealtyGroupMembers.members(new Info(null, new Lease(tenant, now.plusDays(1), now)), now))
                .isEmpty();
        assertThat(RealtyGroupMembers.members(new Info(new Freehold(null), null), now))
                .isEmpty();
        assertThat(RealtyGroupMembers.members(new Info(null, new Lease(tenant, null, null)), now))
                .isEmpty();
        assertThatThrownBy(() -> new RealtyGroupMembers().resolve("bad")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RealtyGroupMembers.members(new Object(), now))
                .isInstanceOf(ReflectiveOperationException.class);
    }
}
