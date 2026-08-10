package io.paradaux.common;

import java.math.BigDecimal;

/**
 * The single, canonical rule for whether a debit is permitted against a source
 * account, shared by <em>every</em> ledger writer so the interpretation cannot drift.
 *
 * <p>Money moves through two independent engines: the in-process Treasury plugin
 * ({@code LedgerServiceImpl.transferInternal}) and the out-of-process
 * {@code treasury-rest-api} ({@code TransferService.executeTransfer}). They live in
 * different JVMs (different hosts/datacenters) and cannot share a call path, so before
 * this class they each re-implemented the overdraft check and had drifted. Both engines
 * now defer to the pure functions here so they decide identically.
 *
 * <p><b>SYSTEM accounts ignore credit limits.</b> A {@code SYSTEM}-type account is a
 * faucet/sink that mints and burns freely, so it is always unlimited regardless of its
 * {@code allow_overdraft}/{@code credit_limit} columns. This is enforced two ways that
 * agree: by type here, and by the {@code credit_limit = -1} sentinel that SYSTEM accounts
 * are created with — the type check makes the invariant hold even for a SYSTEM account
 * whose columns were left at a non-sentinel value.
 *
 * <p><b>Floor semantics</b> for a non-SYSTEM account — the resulting balance
 * ({@code balance - amount}) must not fall below the account's floor:
 * <pre>
 *   allow_overdraft = false                    → floor 0        (credit_limit ignored)
 *   allow_overdraft = true,  credit_limit &lt; 0  → unlimited      (the -1 faucet/sink sentinel)
 *   allow_overdraft = true,  credit_limit &ge; 0  → floor -credit_limit
 * </pre>
 * A {@code null} credit_limit is treated as {@code 0} (so an {@code allow_overdraft}
 * account with no limit set floors at 0 rather than throwing).
 */
public final class OverdraftPolicy {

    private OverdraftPolicy() {}

    /**
     * True when the source may go arbitrarily negative — a {@code SYSTEM}-type account
     * (always), or {@code allow_overdraft = true} with the {@code credit_limit < 0}
     * sentinel. Callers can use this to skip taking the balance lock at all.
     */
    public static boolean isUnlimited(boolean allowOverdraft, BigDecimal creditLimit, boolean systemAccount) {
        return systemAccount || (allowOverdraft && creditLimit != null && creditLimit.signum() < 0);
    }

    /**
     * Whether debiting {@code amount} from {@code balance} keeps the account at or above
     * its floor. {@code amount} is the (positive) magnitude of the debit; {@code systemAccount}
     * is whether the source is a {@code SYSTEM}-type account (always unlimited).
     *
     * @return {@code true} if the transfer is permitted; {@code false} means insufficient funds
     */
    public static boolean isWithinFloor(BigDecimal balance, BigDecimal amount,
                                        boolean allowOverdraft, BigDecimal creditLimit,
                                        boolean systemAccount) {
        if (isUnlimited(allowOverdraft, creditLimit, systemAccount)) {
            return true;
        }
        BigDecimal floor = (allowOverdraft && creditLimit != null)
                ? creditLimit.negate()   // allow_overdraft = true, credit_limit >= 0
                : BigDecimal.ZERO;        // allow_overdraft = false, or no limit set
        return balance.subtract(amount).compareTo(floor) >= 0;
    }
}
