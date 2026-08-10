package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.FirmDisbandResponse;
import io.paradaux.treasuryrestapi.mapper.FirmMapper;
import io.paradaux.treasuryrestapi.model.FirmAccountSummary;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * Regression for treasury-rest-api/behaviour/0002: disband must sweep the
 * <em>freshly locked</em> firm-account balance, not the {@code listFirmAccounts}
 * snapshot. If a concurrent transfer credits (or debits) a firm account after the
 * snapshot but before the sweep locks it, sweeping the stale snapshot amount would
 * strand a residual (or short the sweep), breaking conservation.
 *
 * <p>The race is modelled deterministically — no threads/sleeps — by spying
 * {@link FirmMapper} so {@code listFirmAccounts} reports a STALE balance while the
 * authoritative {@code account_balances_mat} row (which the FOR UPDATE sweep reads)
 * holds the true, larger balance. The fixed engine sweeps the locked value, draining
 * the account to exactly zero and crediting the destination the full amount; the old
 * snapshot-based sweep left a residual behind.
 */
class AdminFirmDisbandSweepIT extends EmbeddedDbIT {

    @Autowired
    private AdminFirmService adminFirmService;

    @MockitoSpyBean
    private FirmMapper firmMapper;

    private static final UUID PROPRIETOR = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private VerifiedToken serviceToken() {
        return new VerifiedToken(1L, UUID.fromString("99999999-9999-9999-9999-999999999999"), "SERVICE", null, null);
    }

    @Test
    void disband_sweepsLockedBalance_notStaleSnapshot() {
        // Proprietor's personal account (sweep destination), starts at 0.
        insertAccount(1, "PERSONAL", PROPRIETOR, "Prop");
        seedBalance(1, "0.00");

        insertFirm(10, "Acme", PROPRIETOR, null);
        insertAccount(2, "BUSINESS", null, "Acme Main");
        linkFirmAccount(10, 2);
        // Authoritative balance the FOR UPDATE sweep will read.
        seedBalance(2, "750.00");

        // Model a concurrent credit that landed AFTER the disband's initial snapshot:
        // listFirmAccounts reports the stale, pre-credit balance (500), but the locked
        // account_balances_mat row above holds the true post-credit balance (750).
        doReturn(List.of(FirmAccountSummary.builder()
                .accountId(2)
                .displayName("Acme Main")
                .accountType("BUSINESS")
                .archived(false)
                .balance(new BigDecimal("500.00")) // STALE
                .build()))
                .when(firmMapper).listFirmAccounts(10);

        FirmDisbandResponse resp = adminFirmService.disband(serviceToken(), 10);

        // Conservation, proven at the ledger (the source of truth): the whole LOCKED 750
        // is swept, not the stale snapshot 500. The debit posting drains the firm account
        // in full and the proprietor is credited the identical amount — no residual left
        // behind, no over-sweep. (Postings are asserted rather than the materialised
        // balance because that view is trigger-maintained; the ledger is authoritative.)
        assertThat(resp.accounts()).hasSize(1);
        assertThat(resp.accounts().get(0).sweptAmount()).isEqualTo("750.00");
        assertThat(sumPostings(2)).isEqualByComparingTo("-750.00"); // firm account fully drained
        assertThat(sumPostings(1)).isEqualByComparingTo("750.00");  // proprietor received all of it
        assertThat(isAccountArchived(2)).isTrue();
        assertThat(isFirmArchived(10)).isTrue();
    }
}
