package io.paradaux.treasury.services;

import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.tax.TaxCycleReport;
import io.paradaux.treasury.model.tax.TaxCycleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class TaxCycleRegistryTest {

    @Mock AccountMapper accountMapper;

    private TaxCycleRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new io.paradaux.treasury.services.impl.TaxCycleRegistryImpl(accountMapper);
        lenient().when(accountMapper.findById(101)).thenReturn(account("DCGovernment"));
        lenient().when(accountMapper.findById(202)).thenReturn(account("GovernmentFines"));
    }

    @Test
    void getNextFireTime_emptyByDefault() {
        assertThat(registry.getNextFireTime(TaxCycleType.DAILY)).isEmpty();
    }

    @Test
    void notifyNextFire_isReadable() {
        Instant when = Instant.parse("2026-01-01T03:00:00Z");
        registry.notifyNextFire(TaxCycleType.DAILY, when);
        assertThat(registry.getNextFireTime(TaxCycleType.DAILY)).contains(when);
        assertThat(registry.getNextFireTime(TaxCycleType.WEEKLY)).isEmpty();
    }

    @Test
    void registerCycleParticipant_recordsAcrossMultipleTypes() {
        registry.registerCycleParticipant("Realty", TaxCycleType.DAILY, TaxCycleType.WEEKLY);
        registry.registerCycleParticipant("Business", TaxCycleType.WEEKLY);

        assertThat(registry.getCycleParticipants(TaxCycleType.DAILY))
                .containsExactly("Realty");
        assertThat(registry.getCycleParticipants(TaxCycleType.WEEKLY))
                .containsExactlyInAnyOrder("Realty", "Business");
        assertThat(registry.getCycleParticipants(TaxCycleType.MONTHLY)).isEmpty();
    }

    @Test
    void getCycleParticipants_unknownTypeReturnsEmpty() {
        assertThat(registry.getCycleParticipants(TaxCycleType.MONTHLY)).isEmpty();
    }

    @Test
    void hasActiveSession_reflectsLifecycle() {
        assertThat(registry.hasActiveSession()).isFalse();
        registry.startSession(TaxCycleType.DAILY, Instant.now(), false, null);
        assertThat(registry.hasActiveSession()).isTrue();
        registry.endSession();
        assertThat(registry.hasActiveSession()).isFalse();
    }

    @Test
    void recordingsWithoutActiveSession_areNoOps() {
        // Should not throw — guarded by null-check
        registry.recordCollected(1, "x", BigDecimal.ONE);
        registry.recordSkipped();
        registry.recordFailed();
        assertThat(registry.hasActiveSession()).isFalse();
    }

    @Test
    void endSession_aggregatesCollectionsByAccountAndTaxType() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        registry.startSession(TaxCycleType.WEEKLY, start, true, "admin");
        registry.recordCollected(101, "balance-tax",       new BigDecimal("100.00"));
        registry.recordCollected(101, "balance-tax",       new BigDecimal("50.00"));
        registry.recordCollected(202, "fine-tax",          new BigDecimal("25.00"));
        registry.recordSkipped();
        registry.recordSkipped();
        registry.recordFailed();

        TaxCycleReport report = registry.endSession();

        assertThat(report).isNotNull();
        assertThat(report.cycleType()).isEqualTo(TaxCycleType.WEEKLY);
        assertThat(report.periodStart()).isEqualTo(start);
        assertThat(report.manual()).isTrue();
        assertThat(report.triggeredBy()).isEqualTo("admin");
        assertThat(report.totalCollected()).isEqualByComparingTo("175.00");
        assertThat(report.byDestinationAccount())
                .containsEntry("DCGovernment",     new BigDecimal("150.00"))
                .containsEntry("GovernmentFines",  new BigDecimal("25.00"));
        assertThat(report.byTaxType())
                .containsEntry("balance-tax", new BigDecimal("150.00"))
                .containsEntry("fine-tax",    new BigDecimal("25.00"));
        assertThat(report.collectedCount()).isEqualTo(3);
        assertThat(report.skippedCount()).isEqualTo(2);
        assertThat(report.failedCount()).isEqualTo(1);
    }

    @Test
    void endSession_unknownAccountIdFallsBackToPlaceholderName() {
        registry.startSession(TaxCycleType.DAILY, Instant.now(), false, null);
        registry.recordCollected(9999, "x", new BigDecimal("1.00"));

        TaxCycleReport report = registry.endSession();
        assertThat(report.byDestinationAccount()).containsKey("Account #9999");
    }

    @Test
    void endSession_withoutActiveSession_returnsNull() {
        assertThat(registry.endSession()).isNull();
    }

    @Test
    void endSession_emptySession_returnsZeroedReport() {
        registry.startSession(TaxCycleType.MONTHLY, Instant.now(), false, null);
        TaxCycleReport report = registry.endSession();

        assertThat(report.totalCollected()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.collectedCount()).isZero();
        assertThat(report.byDestinationAccount()).isEmpty();
        assertThat(report.byTaxType()).isEmpty();
    }

    private static Account account(String name) {
        Account a = new Account();
        a.setDisplayName(name);
        return a;
    }
}
