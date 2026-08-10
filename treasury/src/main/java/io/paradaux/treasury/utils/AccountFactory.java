package io.paradaux.treasury.utils;

import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;

import java.math.BigDecimal;

/** Factory helpers for the primitive system accounts Treasury bootstraps. */
public final class AccountFactory {

    /** The {@code credit_limit} sentinel marking an unlimited faucet/sink (see LedgerServiceImpl). */
    private static final BigDecimal UNLIMITED_CREDIT = BigDecimal.valueOf(-1);

    private AccountFactory() {
    }

    /**
     * Builds a primitive GOVERNMENT account — owned by the virtual treasury owner,
     * overdraft-enabled with the unlimited-credit sentinel ({@code credit_limit = -1})
     * so it can act as a faucet/sink (starting-balances, tax-income, fines, …).
     *
     * <p>The returned {@link Account} is not yet persisted; the caller inserts it and
     * seeds its balance row.
     */
    public static Account primitiveGovernment(String displayName) {
        Account account = new Account();
        account.setAccountType(AccountType.GOVERNMENT);
        account.setOwnerUuid(TreasuryConstants.VIRTUAL_TREASURY_OWNER);
        account.setDisplayName(displayName);
        account.setRequiresAuthorization(false);
        account.setArchived(false);
        account.setAllowOverdraft(true);
        account.setCreditLimit(UNLIMITED_CREDIT);
        return account;
    }
}
