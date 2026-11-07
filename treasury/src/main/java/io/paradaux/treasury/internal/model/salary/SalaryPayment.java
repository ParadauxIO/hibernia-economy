package io.paradaux.treasury.internal.model.salary;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single resolved salary payment for one player: the recipient, the
 * highest-paying group that matched, and the amount to pay.
 */
public record SalaryPayment(UUID player, String group, BigDecimal amount) {}
