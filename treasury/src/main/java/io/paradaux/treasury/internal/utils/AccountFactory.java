package io.paradaux.treasury.internal.utils;

import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import java.math.BigDecimal;

/** Factory helpers for the primitive system accounts Treasury bootstraps. */
public final class AccountFactory {

    /** The {@code credit_limit} sentinel marking an unlimited faucet/sink (see LedgerServiceImpl). */
    private static final BigDecimal UNLIMITED_CREDIT = BigDecimal.valueOf(-1);

    private AccountFactory() {}

    /**
     * Builds a primitive GOVERNMENT account — owned by the virtual treasury owner,
     * overdraft-enabled with the unlimited-credit sentinel ({@code credit_limit = -1})
     * so it can act as a faucet/sink (starting-balances, tax-income, fines, …).
     *
     * <p>The returned {@link Account} is not yet persisted; the caller inserts it and
     * seeds its balance row.
     */
    public static Account primitiveGovernment(String displayName) {
        Account account = Account.builder()
                .accountType(AccountType.GOVERNMENT)
                .ownerUuid(TreasuryConstants.VIRTUAL_TREASURY_OWNER)
                .displayName(displayName)
                .requiresAuthorization(false)
                .isArchived(false)
                .allowOverdraft(true)
                .creditLimit(UNLIMITED_CREDIT)
                .build();
        return account;
    }
}
