package io.paradaux.business.model;

import java.math.BigDecimal;

/**
 * One row of the firm balance leaderboard (/firm baltop): a firm and the
 * collective balance summed across every treasury account it owns.
 *
 * @param firmId      the firm's id
 * @param displayName the firm's display name
 * @param balance     the sum of all the firm's account balances
 */
public record FirmBalanceEntry(Integer firmId, String displayName, BigDecimal balance) {
}
