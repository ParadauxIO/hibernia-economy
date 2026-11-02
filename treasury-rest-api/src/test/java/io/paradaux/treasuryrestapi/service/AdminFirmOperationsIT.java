package io.paradaux.treasuryrestapi.service;

import static org.assertj.core.api.Assertions.*;

import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AdminFirmOperationsIT extends EmbeddedDbIT {
    @Autowired
    private AdminFirmOperationsService service;

    private static final UUID OLD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID NEXT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID FINANCE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private VerifiedToken token() {
        return new VerifiedToken(1L, OLD, "SERVICE", null, null);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private void seed() {
        insertFirm(10, "Acme", OLD, null);
        insertPlayer(NEXT, "NextOwner");
        insertAccount(2, "BUSINESS", OLD, "Main");
        insertAccount(3, "BUSINESS", OLD, "Reserve");
        linkFirmAccount(10, 2);
        linkFirmAccount(10, 3);
        insertFirmRole(1, 10, "Finance", 0, false, false, false);
        insertRolePermission(1, "FINANCIAL", false);
        insertFirmRole(2, 10, "Old owner", 1, false, false, false);
        insertRolePermission(2, "ADMIN", false);
        insertEmployee(10, OLD, 2, false);
        insertEmployee(10, NEXT, 1, false);
        insertEmployee(10, FINANCE, 1, false);
        insertApiKey(
                20,
                "BUSINESS",
                OLD,
                "old-key",
                false,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(1));
        jdbc().update("UPDATE api_keys SET firm_id=10 WHERE key_id=20");

        jdbc().update(
                        "INSERT INTO account_access (account_id,subject_uuid_bin,level,added_by_uuid_bin) VALUES (2,uuid_to_bin(?),'AUTHORIZER',uuid_to_bin(?))",
                        OLD.toString(),
                        OLD.toString());
        jdbc().update(
                        "INSERT INTO firm_transfer_requests (firm_id,from_uuid_bin,to_uuid_bin,token,expires_at,status) VALUES (10,uuid_to_bin(?),uuid_to_bin(?),'pending',DATE_ADD(NOW(),INTERVAL 1 DAY),'CONFIRMED')",
                        OLD.toString(),
                        FINANCE.toString());
    }

    @Test
    void handoverReconcilesAllAccountsAndCancelsTheOldTransfer() {
        seed();
        var result = service.changeProprietor(token(), 10, "NextOwner", FINANCE);
        assertThat(result.accountIds()).containsExactly(2L, 3L);
        assertThat(result.proprietorUuid()).isEqualTo(NEXT);
        assertThat(jdbc().queryForObject("SELECT revoked FROM api_keys WHERE key_id=20", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc().queryForObject(
                                "SELECT COUNT(*) FROM accounts WHERE account_id IN (2,3) AND owner_uuid_bin = uuid_to_bin(?)",
                                Integer.class,
                                NEXT.toString()))
                .isEqualTo(2);
        assertThat(jdbc().queryForObject(
                                "SELECT COUNT(*) FROM firm_employee WHERE firm_id=10 AND player_uuid_bin=uuid_to_bin(?) AND left_at IS NULL",
                                Integer.class,
                                NEXT.toString()))
                .isZero();
        assertThat(jdbc().queryForObject(
                                "SELECT status FROM firm_transfer_requests WHERE token='pending'", String.class))
                .isEqualTo("CANCELLED");
        assertThat(jdbc().queryForObject(
                                "SELECT bin_to_uuid(actor_uuid_bin) FROM firm_transfer_requests WHERE status='ADMIN_OVERRIDE'",
                                String.class))
                .isEqualTo(FINANCE.toString());
        assertThat(jdbc().queryForList(
                                "SELECT level FROM account_access WHERE subject_uuid_bin=uuid_to_bin(?) AND removed_at IS NULL ORDER BY account_id",
                                String.class,
                                NEXT.toString()))
                .containsExactly("AUTHORIZER", "AUTHORIZER");
        assertThat(jdbc().queryForList(
                                "SELECT level FROM account_access WHERE subject_uuid_bin=uuid_to_bin(?) AND removed_at IS NULL ORDER BY account_id",
                                String.class,
                                FINANCE.toString()))
                .containsExactly("MEMBER", "MEMBER");
        assertThat(jdbc().queryForObject(
                                "SELECT COUNT(*) FROM account_access WHERE subject_uuid_bin=uuid_to_bin(?) AND removed_at IS NULL",
                                Integer.class,
                                OLD.toString()))
                .isZero();
        service.changeProprietor(token(), 10, "NextOwner", FINANCE);
        assertThat(jdbc().queryForObject(
                                "SELECT COUNT(*) FROM firm_transfer_requests WHERE status='ADMIN_OVERRIDE'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void failureOnSecondAccountRollsBackTheWholeHandover() {
        seed();
        jdbc().execute(
                        "CREATE TRIGGER reject_operator_test BEFORE UPDATE ON accounts FOR EACH ROW BEGIN IF NEW.account_id=3 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'; END IF; END");
        try {
            assertThatThrownBy(() -> service.changeProprietor(token(), 10, "NextOwner", FINANCE))
                    .isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("test failure");
            assertThat(jdbc().queryForObject(
                                    "SELECT bin_to_uuid(proprietor_uuid_bin) FROM firm WHERE firm_id=10", String.class))
                    .isEqualTo(OLD.toString());
            assertThat(jdbc().queryForObject(
                                    "SELECT bin_to_uuid(owner_uuid_bin) FROM accounts WHERE account_id=2",
                                    String.class))
                    .isEqualTo(OLD.toString());
            assertThat(jdbc().queryForObject(
                                    "SELECT status FROM firm_transfer_requests WHERE token='pending'", String.class))
                    .isEqualTo("CONFIRMED");
            assertThat(jdbc().queryForObject(
                                    "SELECT COUNT(*) FROM firm_transfer_requests WHERE status='ADMIN_OVERRIDE'",
                                    Integer.class))
                    .isZero();
            assertThat(jdbc().queryForObject("SELECT revoked FROM api_keys WHERE key_id=20", Integer.class))
                    .isZero();
        } finally {
            jdbc().execute("DROP TRIGGER reject_operator_test");
        }
    }

    @Test
    void releaseAndTaxChangesEnforceFirmStateAndServiceScope() {
        seed();
        assertThatThrownBy(() -> service.releaseName(token(), 10)).isInstanceOf(ApiException.class);
        assertThat(service.setTaxExempt(token(), 10, true).taxExempt()).isTrue();
        assertThat(service.setTaxExempt(token(), 10, false).taxExempt()).isFalse();
        var personal = new VerifiedToken(2L, OLD, "PERSONAL", 2L, null);
        assertThatThrownBy(() -> service.syncAccounts(personal, 10, OLD)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.changeProprietor(personal, 10, "NextOwner", OLD))
                .isInstanceOf(ApiException.class);
        jdbc().update("UPDATE firm SET is_archived=1 WHERE firm_id=10");
        assertThatThrownBy(() -> service.releaseName(token(), 10)).isInstanceOf(ApiException.class);
        jdbc().update("UPDATE firm_accounts SET removed_at=NOW() WHERE firm_id=10");
        assertThat(service.releaseName(token(), 10).nameReleased()).isTrue();
        assertThatThrownBy(() -> service.syncAccounts(token(), 10, OLD)).isInstanceOf(ApiException.class);
    }
}
