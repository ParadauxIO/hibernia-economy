-- The all-time market firm ranking reads quantity and total_price for every
-- business sale. idx_cs_firm only covers (shop_firm_id, occurred_at), causing
-- millions of base-row lookups and 15-second Explorer statement timeouts.
-- Keep the time index for firm drilldowns; this index covers the grouped totals.
CREATE INDEX idx_cs_firm_totals ON chestshop_sale (shop_firm_id, quantity, total_price);
