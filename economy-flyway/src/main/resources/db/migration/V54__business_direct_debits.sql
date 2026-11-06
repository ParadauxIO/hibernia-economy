-- PAR-557: business direct debits. A customer accepts a mandate that lets a firm
-- debit their PERSONAL account into one firm account, capped per period. Schema
-- only; the Business plugin does nothing with it until an operator enables the
-- 'business.direct_debits' release flag.
--
-- Times are UTC DATETIMEs written by the plugin from its own clock (never NOW()),
-- so period boundaries don't depend on the database session time zone.

CREATE TABLE direct_debit_mandate (
    mandate_id            BIGINT        NOT NULL AUTO_INCREMENT,
    firm_id               INT           NOT NULL,
    payee_account_id      INT UNSIGNED  NOT NULL,
    payer_account_id      INT UNSIGNED  NOT NULL,
    payer_uuid_bin        BINARY(16)    NOT NULL,
    -- RECURRING: `amount` collected automatically once per period.
    -- ON_DEMAND: the firm charges any amount while the period total stays <= cap.
    kind                  ENUM('RECURRING','ON_DEMAND') NOT NULL,
    period                ENUM('DAILY','WEEKLY','MONTHLY') NOT NULL,
    cap                   DECIMAL(19,2) NOT NULL,
    amount                DECIMAL(19,2) NULL,
    reference             VARCHAR(100)  NOT NULL,
    status                ENUM('PENDING','ACTIVE','SUSPENDED','CANCELLED','REJECTED','EXPIRED')
                                        NOT NULL DEFAULT 'PENDING',
    proposed_by_uuid_bin  BINARY(16)    NOT NULL,
    proposed_at           DATETIME      NOT NULL,
    expires_at            DATETIME      NOT NULL,
    accepted_at           DATETIME      NULL,
    ended_at              DATETIME      NULL,
    ended_by_uuid_bin     BINARY(16)    NULL,
    end_reason            VARCHAR(255)  NULL,
    -- RECURRING only: when the collection job should next try. NULL otherwise.
    next_due_at           DATETIME      NULL,
    -- RECURRING only: consecutive periods that ended without a collection.
    missed_periods        INT           NOT NULL DEFAULT 0,
    -- Only one pending proposal per firm/payer.
    pending_only TINYINT(1) GENERATED ALWAYS AS
        (CASE WHEN status = 'PENDING' THEN 1 ELSE NULL END) VIRTUAL,
    PRIMARY KEY (mandate_id),
    CONSTRAINT fk_ddm_firm FOREIGN KEY (firm_id) REFERENCES firm(firm_id) ON DELETE CASCADE,
    CONSTRAINT fk_ddm_payee FOREIGN KEY (payee_account_id) REFERENCES accounts(account_id),
    CONSTRAINT fk_ddm_payer FOREIGN KEY (payer_account_id) REFERENCES accounts(account_id),
    CONSTRAINT uq_ddm_one_pending UNIQUE (firm_id, payer_uuid_bin, pending_only),
    CONSTRAINT ck_ddm_cap CHECK (cap > 0),
    CONSTRAINT ck_ddm_amount CHECK (
        (kind = 'RECURRING' AND amount IS NOT NULL AND amount > 0 AND amount <= cap)
        OR (kind = 'ON_DEMAND' AND amount IS NULL)),
    KEY idx_ddm_payer (payer_uuid_bin, status),
    KEY idx_ddm_firm (firm_id, status),
    KEY idx_ddm_due (status, kind, next_due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One attempted debit. A row is RESERVED (counted against the cap) before the
-- ledger transfer and settled to COLLECTED or FAILED afterwards; the transfer's
-- dedup key is derived from collection_id, so re-driving a RESERVED row after a
-- crash collapses onto the transfer that already happened.
CREATE TABLE direct_debit_collection (
    collection_id         BIGINT        NOT NULL AUTO_INCREMENT,
    mandate_id            BIGINT        NOT NULL,
    period_start          DATETIME      NOT NULL,
    -- RECURRING collections set this to period_start so a period is collected at
    -- most once; ON_DEMAND collections leave it NULL.
    recurring_period      DATETIME      NULL,
    amount                DECIMAL(19,2) NOT NULL,
    status                ENUM('RESERVED','COLLECTED','FAILED') NOT NULL DEFAULT 'RESERVED',
    attempts              INT           NOT NULL DEFAULT 1,
    txn_id                BIGINT UNSIGNED NULL,
    initiator_uuid_bin    BINARY(16)    NOT NULL,
    memo                  VARCHAR(100)  NULL,
    failure_reason        VARCHAR(255)  NULL,
    created_at            DATETIME      NOT NULL,
    updated_at            DATETIME      NOT NULL,
    PRIMARY KEY (collection_id),
    CONSTRAINT fk_ddc_mandate FOREIGN KEY (mandate_id)
        REFERENCES direct_debit_mandate(mandate_id) ON DELETE CASCADE,
    CONSTRAINT uq_ddc_recurring_period UNIQUE (mandate_id, recurring_period),
    CONSTRAINT ck_ddc_amount CHECK (amount > 0),
    KEY idx_ddc_mandate_period (mandate_id, period_start, status),
    KEY idx_ddc_reserved (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO feature_flags (name, description)
VALUES ('business.direct_debits', 'Enable business direct debit mandates: proposals, customer approval, on-demand and recurring collections')
ON DUPLICATE KEY UPDATE name = VALUES(name);
