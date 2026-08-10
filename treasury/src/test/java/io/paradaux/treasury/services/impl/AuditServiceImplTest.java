package io.paradaux.treasury.services.impl;

import io.paradaux.treasury.mappers.ExplorerAuditMapper;
import io.paradaux.treasury.model.economy.ExplorerAuditEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceImplTest {

    @Mock ExplorerAuditMapper explorerAuditMapper;

    private AuditServiceImpl svc;

    @BeforeEach
    void setUp() {
        svc = new AuditServiceImpl(explorerAuditMapper);
    }

    @Test
    void recordTransactionAudit_writesAccountAccessRowToExplorerAudit() {
        UUID actor = UUID.randomUUID();

        svc.recordTransactionAudit(actor, "Staff", 5, "/transactions auditaccount 5", 1);

        ArgumentCaptor<ExplorerAuditEntry> cap = ArgumentCaptor.forClass(ExplorerAuditEntry.class);
        verify(explorerAuditMapper).insert(cap.capture());
        ExplorerAuditEntry e = cap.getValue();
        assertThat(e.getActorUuid()).isEqualTo(actor);
        assertThat(e.getActorSub()).isEqualTo("minecraft:" + actor);
        assertThat(e.getActorName()).isEqualTo("Staff");
        assertThat(e.getActorRole()).isEqualTo("treasury.transactions.audit");
        assertThat(e.getMethod()).isEqualTo("CMD");
        assertThat(e.getPath()).isEqualTo("/transactions auditaccount 5");
        assertThat(e.getTargetType()).isEqualTo("account");
        assertThat(e.getTargetId()).isEqualTo("5");
        assertThat(e.getOutcome()).isEqualTo(200);
        assertThat(e.getSourceIp()).isNull();
    }

    @Test
    void recordTransactionAudit_appendsPageToPathWhenPaged() {
        svc.recordTransactionAudit(UUID.randomUUID(), "Staff", 5, "/transactions auditaccount 5", 3);

        ArgumentCaptor<ExplorerAuditEntry> cap = ArgumentCaptor.forClass(ExplorerAuditEntry.class);
        verify(explorerAuditMapper).insert(cap.capture());
        assertThat(cap.getValue().getPath()).isEqualTo("/transactions auditaccount 5 (page 3)");
    }

    @Test
    void recordTransactionAudit_failsOpenWhenInsertThrows() {
        when(explorerAuditMapper.insert(any())).thenThrow(new RuntimeException("db down"));

        // A logging failure must never propagate out of the audit path.
        assertThatCode(() -> svc.recordTransactionAudit(UUID.randomUUID(), "Staff", 5, "/x", 1))
                .doesNotThrowAnyException();
        verify(explorerAuditMapper).insert(any());
    }
}
