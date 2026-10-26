-- Derived daily rollups for the Economy Explorer (PAR-532). The explorer's own
-- Node process rebuilds them in the background (economy-explorer/lib/rollup),
-- so pages read small pre-aggregated rows instead of scanning the ledger per
-- request. Nothing else writes these tables, and they hold no source of truth:
-- every row can be deleted and recomputed from ledger_txns, ledger_postings,
-- chestshop_sale, economy_players and account_balances_mat.
--
-- No triggers. A trigger keeping a shared per-day row would serialise every
-- transfer and, taken after LedgerServiceImpl's ascending FOR UPDATE balance
-- locks, could deadlock against them (see V36). The worker recomputes whole
-- days with INSERT ... SELECT under READ COMMITTED, which reads the ledger as a
-- consistent snapshot and takes no locks on it.
--
-- Days are UTC calendar days. Money is DECIMAL(19,2) like the ledger; unit
-- prices keep chestshop_sale's DECIMAL(19,4).

-- Per account, day and source. plugin_key is COALESCE(plugin_system, '') as
-- stored; tax_kind is V38's kind, 'fine' for a government_fines debit or
-- revocation, otherwise ''. counterparty_type is the type of the largest
-- opposite-signed leg in the same transaction ('' when there is none), and
-- is_internal marks transfers between the account's own accounts: the same
-- account, PERSONAL or GOVERNMENT accounts with the same owner, or BUSINESS
-- accounts of the same firm. Both extend the (account_id, day, plugin_key,
-- tax_kind) grain so categories (lib/categories.ts) can be mapped at read time.
CREATE TABLE explorer_rollup_account_day (
    account_id        INT UNSIGNED  NOT NULL,
    day               DATE          NOT NULL,
    plugin_key        VARCHAR(64)   NOT NULL,
    tax_kind          VARCHAR(9)    NOT NULL DEFAULT '',
    counterparty_type VARCHAR(10)   NOT NULL DEFAULT '',
    is_internal       TINYINT(1)    NOT NULL DEFAULT 0,
    inflow            DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    outflow           DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    txn_count         INT UNSIGNED  NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, day, plugin_key, tax_kind, counterparty_type, is_internal),
    KEY idx_erad_day (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Counterparty totals per account and calendar month (month = its first day).
-- Amounts are matched pairwise: LEAST(|own leg|, |other leg|) for each
-- opposite-signed leg, which is exact for two-leg transfers.
CREATE TABLE explorer_rollup_pair_month (
    account_id      INT UNSIGNED  NOT NULL,
    counterparty_id INT UNSIGNED  NOT NULL,
    month           DATE          NOT NULL,
    inflow          DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    outflow         DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    txn_count       INT UNSIGNED  NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, counterparty_id, month),
    KEY idx_erpm_month_account (month, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Worker staging for explorer_rollup_pair_month: the same pairs per day, so a
-- recomputed day can rebuild its month without rescanning the month's ledger.
-- Days of months that can no longer change are pruned.
CREATE TABLE explorer_rollup_pair_day (
    account_id      INT UNSIGNED  NOT NULL,
    day             DATE          NOT NULL,
    counterparty_id INT UNSIGNED  NOT NULL,
    inflow          DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    outflow         DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    txn_count       INT UNSIGNED  NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, day, counterparty_id),
    KEY idx_erpd_day (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Economy-wide totals per day. txn_count / volume are the bridge-adjusted
-- figures that getDailyVolume computes (an adjacent SYSTEM bridge pair counts
-- once, on the outgoing leg's day); raw_* count every transaction.
-- active_players counts distinct owners of PERSONAL accounts with a posting
-- that day; new_players counts economy_players first seen that day.
CREATE TABLE explorer_rollup_economy_day (
    day            DATE          NOT NULL,
    txn_count      INT UNSIGNED  NOT NULL DEFAULT 0,
    volume         DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    raw_txn_count  INT UNSIGNED  NOT NULL DEFAULT 0,
    raw_volume     DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    bridge_pairs   INT UNSIGNED  NOT NULL DEFAULT 0,
    active_players INT UNSIGNED  NOT NULL DEFAULT 0,
    new_players    INT UNSIGNED  NOT NULL DEFAULT 0,
    PRIMARY KEY (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Gross flows per day, account type and source. inflow - outflow is the day's
-- change in that type's money supply.
CREATE TABLE explorer_rollup_type_day (
    day          DATE          NOT NULL,
    account_type ENUM('PERSONAL','BUSINESS','GOVERNMENT','SYSTEM') NOT NULL,
    plugin_key   VARCHAR(64)   NOT NULL,
    inflow       DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    outflow      DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    PRIMARY KEY (day, account_type, plugin_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Transactions per settlement hour. weekday is 0 = Monday .. 6 = Sunday.
CREATE TABLE explorer_rollup_hour_heatmap (
    day       DATE             NOT NULL,
    weekday   TINYINT UNSIGNED NOT NULL,
    hour      TINYINT UNSIGNED NOT NULL,
    txn_count INT UNSIGNED     NOT NULL DEFAULT 0,
    PRIMARY KEY (day, weekday, hour)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ChestShop trades per item, day and direction. open/close are the first and
-- last trade's unit price by (occurred_at, sale_id).
CREATE TABLE explorer_rollup_item_day (
    item_key         VARCHAR(190)        NOT NULL,
    day              DATE                NOT NULL,
    direction        ENUM('BUY','SELL')  NOT NULL,
    qty              BIGINT UNSIGNED     NOT NULL DEFAULT 0,
    volume           DECIMAL(19,2)       NOT NULL DEFAULT 0.00,
    trades           INT UNSIGNED        NOT NULL DEFAULT 0,
    min_unit_price   DECIMAL(19,4)       NOT NULL,
    max_unit_price   DECIMAL(19,4)       NOT NULL,
    open_unit_price  DECIMAL(19,4)       NOT NULL,
    close_unit_price DECIMAL(19,4)       NOT NULL,
    PRIMARY KEY (item_key, day, direction),
    KEY idx_erid_day (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Wealth as last observed on each day, from account_balances_mat (active
-- accounts). Starts on the worker's first run; earlier days are not
-- reconstructed. gini and the top shares are over positive PERSONAL balances,
-- matching lib/derived.ts. holder_buckets maps getBalanceDistribution's bucket
-- keys to account counts.
CREATE TABLE explorer_snapshot_wealth_day (
    day               DATE          NOT NULL,
    taken_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    supply_personal   DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    supply_business   DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    supply_government DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    supply_system     DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    personal_holders  INT UNSIGNED  NOT NULL DEFAULT 0,
    gini              DECIMAL(9,6)  NOT NULL DEFAULT 0,
    top1_share        DECIMAL(9,6)  NOT NULL DEFAULT 0,
    top10_share       DECIMAL(9,6)  NOT NULL DEFAULT 0,
    holder_buckets    LONGTEXT      NOT NULL CHECK (JSON_VALID(holder_buckets)),
    PRIMARY KEY (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One row per job step (job = table, day = the day it rebuilt). error is NULL
-- on success. The worker prunes rows older than a week.
CREATE TABLE explorer_rollup_run (
    run_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    job          VARCHAR(32)     NOT NULL,
    day          DATE            NULL,
    started_at   TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at  TIMESTAMP(3)    NULL,
    rows_written INT UNSIGNED    NOT NULL DEFAULT 0,
    error        VARCHAR(1000)   NULL,
    PRIMARY KEY (run_id),
    KEY idx_errun_job_started (job, started_at),
    KEY idx_errun_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The contiguous range of days each job has computed. Backfill extends
-- from_day backwards one day at a time; readers check it before trusting a
-- range, so a restart resumes where it stopped. history_complete is set once
-- from_day reaches the first day of source data: earlier days are then
-- covered too (they are empty).
CREATE TABLE explorer_rollup_coverage (
    job              VARCHAR(32)  NOT NULL,
    from_day         DATE         NOT NULL,
    to_day           DATE         NOT NULL,
    history_complete TINYINT(1)   NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (job)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
