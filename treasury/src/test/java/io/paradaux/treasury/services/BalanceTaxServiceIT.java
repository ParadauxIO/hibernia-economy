package io.paradaux.treasury.services;

import io.paradaux.treasury.mappers.EconomyPlayerMapper;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BalanceTaxServiceIT extends IntegrationTestBase {

    private static final long ONE_WEEK_SECS = 7L * 24 * 3600;

    private LedgerService ledgerService;
    private AccountService accountService;
    private BalanceTaxService balanceTaxService;
    private PlayerDirectoryService directory;
    private EconomyPlayerMapper economyPlayers;

    @BeforeEach
    void setUp() {
        ledgerService     = injector.getInstance(LedgerService.class);
        accountService    = injector.getInstance(AccountService.class);
        balanceTaxService = injector.getInstance(BalanceTaxService.class);
        directory         = injector.getInstance(PlayerDirectoryService.class);
        economyPlayers    = injector.getInstance(EconomyPlayerMapper.class);
        ledgerService.bootstrapGovernmentAccounts();
    }

    /**
     * Mirrors {@code PlayerLoginListener}: the directory atomically records the new
     * login and returns the previous epoch, then balance tax collects over that
     * window. This is the only path that advances the login clock.
     */
    private void login(UUID player, long epochSeconds) {
        Long previous = directory.recordLogin(player, nameFor(player), epochSeconds);
        balanceTaxService.collect(player, previous, epochSeconds);
    }

    private static String nameFor(UUID player) {
        return "P" + player.toString().substring(0, 8);
    }

    @Test
    void firstLogin_recordsTimestamp_doesNotChargeTax() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        BigDecimal startingBalance = accountService.getBalanceByOwnerUuid(player);

        long now = 1_700_000_000L;
        login(player, now);

        assertThat(economyPlayers.findLastLogin(player)).isEqualTo(now);
        // Player still has full starting balance — no charge on first login.
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo(startingBalance);
    }

    @Test
    void secondLogin_chargesProratedTaxAcrossElapsedSeconds() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        // Top up to 100 000 so we hit the 1 % bracket
        ledgerService.adminSet(player, new BigDecimal("100000.00"), "test setup", UUID.randomUUID());

        long t0 = 1_700_000_000L;
        login(player, t0);

        // Half a week later
        long t1 = t0 + ONE_WEEK_SECS / 2;
        login(player, t1);

        // Expected charge: 100_000 * 0.01 * 0.5 = 500.00
        assertThat(accountService.getBalanceByOwnerUuid(player))
                .isEqualByComparingTo("99500.00");
    }

    @Test
    void clockSkew_doesNotDoubleCharge() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        ledgerService.adminSet(player, new BigDecimal("100000.00"), "test setup", UUID.randomUUID());

        long now = 1_700_000_000L;
        login(player, now);
        // Same epoch second again — should be a no-op (elapsed = 0)
        login(player, now);

        // No tax was charged on either call (first = no prior login, second = elapsed=0)
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("100000.00");
    }

    @Test
    void belowFirstBracket_isNotTaxed() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        // Default starting balance is 10 000 — well below the 100 000 first taxable bracket.

        long t0 = 1_700_000_000L;
        login(player, t0);
        login(player, t0 + ONE_WEEK_SECS);

        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("10000.00");
    }

    @Test
    void zeroBalance_shortCircuits() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        ledgerService.adminSet(player, BigDecimal.ZERO, "drain", UUID.randomUUID());

        long t0 = 1_700_000_000L;
        login(player, t0);
        login(player, t0 + ONE_WEEK_SECS);

        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void replayWithSameLoginTime_isIdempotent() {
        // Re-collecting the same login window must charge at most once because the
        // dedup key is sha256("balance-tax:<uuid>:<loginEpoch>").
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        ledgerService.adminSet(player, new BigDecimal("100000.00"), "setup", UUID.randomUUID());

        long t0 = 1_700_000_000L;
        login(player, t0); // first ever — records timestamp, no charge
        long t1 = t0 + ONE_WEEK_SECS / 4;
        login(player, t1); // records t1, charges for [t0, t1]
        // Replay the exact same window directly — same dedup key, so no second charge.
        balanceTaxService.collect(player, t0, t1);

        // Charge: 100 000 * 0.01 * 0.25 = 250.00 — only once.
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("99750.00");
    }

    @Test
    void noPersonalAccount_butLoginRecordExists_shortCircuits() {
        // Player has a recorded prior login but no PERSONAL account
        // (the FirstPlayerJoinEvent listener would normally create one).
        UUID ghost = UUID.randomUUID();
        long t0 = 1_700_000_000L;
        login(ghost, t0);

        // Should return cleanly without throwing.
        login(ghost, t0 + ONE_WEEK_SECS);

        // Login timestamp got updated; no charge could happen because no account exists.
        assertThat(economyPlayers.findLastLogin(ghost)).isEqualTo(t0 + ONE_WEEK_SECS);
    }

    @Test
    void shortPeriod_subDayElapsed_chargesProrated() {
        // Period < 1 day exercises formatPeriod's hours/minutes branch.
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        ledgerService.adminSet(player, new BigDecimal("100000.00"), "setup", UUID.randomUUID());

        long t0 = 1_700_000_000L;
        login(player, t0);
        // Two hours later — 7200s, well below 1 day
        login(player, t0 + 7200);

        // 100 000 * 0.01 * (7200 / 604800) ≈ 11.90, rounded to 2dp = 11.90
        assertThat(accountService.getBalanceByOwnerUuid(player))
                .isEqualByComparingTo("99988.10");
    }

    @Test
    void taxRoundsToZero_isSkipped() {
        // Tiny period × low balance produces sub-cent tax that rounds to 0 → skipped.
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        // Just above the 100k bracket so a rate applies, but not by much.
        ledgerService.adminSet(player, new BigDecimal("100000.00"), "setup", UUID.randomUUID());

        long t0 = 1_700_000_000L;
        login(player, t0);
        // 1 second elapsed — 100000 * 0.01 * (1/604800) ≈ 0.00165 → rounds to 0.00
        login(player, t0 + 1);

        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("100000.00");
    }
}
