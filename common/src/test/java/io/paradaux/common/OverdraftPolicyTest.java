package io.paradaux.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverdraftPolicyTest {

    private static BigDecimal bd(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    // ---- isUnlimited: SYSTEM (always) or the (allow_overdraft=true, credit_limit<0) sentinel ----

    @ParameterizedTest
    @CsvSource({
            // allowOverdraft, creditLimit, systemAccount, expected
            "true,  -1,   false, true",   // the faucet/sink sentinel
            "true,  -0.01,false, true",   // any negative limit is the sentinel
            "true,  0,    false, false",  // finite zero floor, not unlimited
            "true,  100,  false, false",  // finite limit, not unlimited
            "false, -1,   false, false",  // overdraft off — the negative limit is inert
            "false, 0,    false, false",
            // SYSTEM accounts are always unlimited, regardless of the other columns
            "false, 0,    true,  true",
            "true,  0,    true,  true",   // account #4's config, but SYSTEM → unlimited
            "false, 500,  true,  true",
    })
    void isUnlimited(boolean allowOverdraft, String creditLimit, boolean systemAccount, boolean expected) {
        assertEqualsBool(expected, OverdraftPolicy.isUnlimited(allowOverdraft, bd(creditLimit), systemAccount));
    }

    @Test
    void isUnlimitedNullLimitIsUnlimitedOnlyForSystem() {
        assertFalse(OverdraftPolicy.isUnlimited(true, null, false));
        assertFalse(OverdraftPolicy.isUnlimited(false, null, false));
        assertTrue(OverdraftPolicy.isUnlimited(false, null, true));
    }

    // ---- isWithinFloor: the canonical floor both engines share ----

    @ParameterizedTest
    @CsvSource({
            // balance, amount, allowOverdraft, creditLimit, systemAccount, expected
            // allow_overdraft = false → floor 0, credit_limit ignored
            "100, 100, false, 0,   false, true",   // lands exactly on 0
            "100, 101, false, 0,   false, false",  // one over → insufficient
            "100, 101, false, 500, false, false",  // credit_limit ignored when overdraft is off
            // allow_overdraft = true, credit_limit < 0 → unlimited
            "0,   999999, true, -1, false, true",
            "-5000000, 1, true, -1, false, true",
            // allow_overdraft = true, credit_limit >= 0 → floor -credit_limit
            "0,   100, true, 100, false, true",    // 0-100 = -100, exactly the floor
            "0,   101, true, 100, false, false",   // -101 < -100 → insufficient
            "50,  100, true, 0,   false, false",   // floor 0: 50-100 = -50 < 0 → insufficient
            "100, 100, true, 0,   false, true",    // floor 0: lands on 0
            // SYSTEM → always within floor, however negative it goes
            "0,   999999, false, 0, true, true",
            "70.95, 100000, true, 0, true, true",  // account #4: SYSTEM, unlimited by type
    })
    void isWithinFloor(String balance, String amount, boolean allowOverdraft, String creditLimit,
                       boolean systemAccount, boolean expected) {
        assertEqualsBool(expected,
                OverdraftPolicy.isWithinFloor(bd(balance), bd(amount), allowOverdraft, bd(creditLimit), systemAccount));
    }

    @Test
    void nullCreditLimitTreatedAsZeroFloorForNonSystem() {
        // allow_overdraft on but no limit set → floor 0 (never NPEs)
        assertTrue(OverdraftPolicy.isWithinFloor(bd("100"), bd("100"), true, null, false));
        assertFalse(OverdraftPolicy.isWithinFloor(bd("100"), bd("101"), true, null, false));
        assertFalse(OverdraftPolicy.isWithinFloor(bd("0"), bd("1"), false, null, false));
    }

    @Test
    void systemAccountIgnoresCreditLimitsEntirely() {
        // Regression (PAR-319): account_id=4 "ChestShop System" is SYSTEM, (allow_overdraft=true,
        // credit_limit=0). As a SYSTEM faucet/sink it must go arbitrarily negative regardless of
        // its columns — both engines must agree it's unlimited, not floor it at 0.
        assertTrue(OverdraftPolicy.isWithinFloor(bd("70.95"), bd("70.96"), true, bd("0"), true));
        assertTrue(OverdraftPolicy.isWithinFloor(bd("0"), bd("1000000"), false, bd("0"), true));
    }

    private static void assertEqualsBool(boolean expected, boolean actual) {
        if (expected) {
            assertTrue(actual);
        } else {
            assertFalse(actual);
        }
    }
}
