package io.paradaux.treasury.mappers;

import io.paradaux.treasury.model.economy.ExplorerAuditEntry;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Treasury plugin can append in-game audit records to the shared
 * {@code explorer_audit} table (economy-flyway V3) and read them back.
 */
class ExplorerAuditMapperIT extends IntegrationTestBase {

    private ExplorerAuditMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = injector.getInstance(ExplorerAuditMapper.class);
    }

    @Test
    void insert_thenFindByTarget_roundTrips() {
        UUID actor = UUID.randomUUID();
        ExplorerAuditEntry e = new ExplorerAuditEntry();
        e.setActorSub("minecraft:" + actor);
        e.setActorUuid(actor);
        e.setActorName("Staff");
        e.setActorRole("treasury.transactions.audit");
        e.setMethod("CMD");
        e.setPath("/transactions auditaccount 42");
        e.setTargetType("account");
        e.setTargetId("42");
        e.setOutcome(200);
        e.setSourceIp(null);

        int rows = mapper.insert(e);
        assertThat(rows).isEqualTo(1);
        assertThat(e.getAuditId()).isPositive();

        List<ExplorerAuditEntry> found = mapper.findByTarget("account", "42");
        assertThat(found).hasSize(1);
        ExplorerAuditEntry got = found.get(0);
        assertThat(got.getActorUuid()).isEqualTo(actor);
        assertThat(got.getActorSub()).isEqualTo("minecraft:" + actor);
        assertThat(got.getActorName()).isEqualTo("Staff");
        assertThat(got.getActorRole()).isEqualTo("treasury.transactions.audit");
        assertThat(got.getMethod()).isEqualTo("CMD");
        assertThat(got.getPath()).isEqualTo("/transactions auditaccount 42");
        assertThat(got.getTargetType()).isEqualTo("account");
        assertThat(got.getTargetId()).isEqualTo("42");
        assertThat(got.getOutcome()).isEqualTo(200);
        assertThat(got.getAt()).isNotNull();
    }
}
