-- Progressive income tax: each player's taxable income, and the tax taken on it,
-- for one accounting period. The tax is collected on every payment from the
-- difference between the marginal tax on the running total before and after it,
-- so the running total must be read and advanced under a row lock in the same
-- transaction as the payment.
--
-- period_start is the period's start as epoch milliseconds (UTC), computed by the
-- plugin from the configured period length and anchor; storing it as a number
-- keeps it independent of the connection time zone. A new period starts a new
-- row, so old rows are history rather than state and are safe to prune.
CREATE TABLE income_tax_period (
    owner_uuid_bin BINARY(16)    NOT NULL,
    period_start   BIGINT        NOT NULL,
    income         DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    tax_paid       DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_uuid_bin, period_start)
) ENGINE=InnoDB;
