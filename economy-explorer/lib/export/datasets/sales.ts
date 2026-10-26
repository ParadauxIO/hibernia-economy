import 'server-only';
import { hasPermission } from '@/lib/auth/policy';
import type { KeysetCursor } from '@/lib/pagination/cursor';
import { exportSalesBatch } from '@/lib/services/sales';
import type { MarketSaleRow } from '@/lib/sql/market';
import { salesTable } from '@/lib/tables/sales';
import { defineExport } from '../registry';

// The ChestShop sales feed with customer attribution, gated by
// market.sales.read exactly like /chestshop/sales.

defineExport<typeof salesTable, MarketSaleRow, KeysetCursor>({
  id: 'sales',
  title: 'Sales',
  table: salesTable,
  authorize: (viewer) => hasPermission(viewer, 'market.sales.read'),
  rows: (state, cursor, { limit }) => exportSalesBatch(state, cursor, limit),
  columns: [
    { key: 'sale_id', header: 'Sale', value: (s) => s.sale_id },
    { key: 'occurred_at', header: 'When (UTC)', value: (s) => s.occurred_at?.toISOString() ?? null },
    { key: 'direction', header: 'Direction', value: (s) => s.direction },
    { key: 'quantity', header: 'Quantity', value: (s) => s.quantity },
    { key: 'unit_price', header: 'Unit price', value: (s) => s.unit_price },
    { key: 'total_price', header: 'Total', value: (s) => s.total_price },
    { key: 'tax_amount', header: 'Tax', value: (s) => s.tax_amount },
    { key: 'item_key', header: 'Item key', value: (s) => s.item_key },
    { key: 'item_name', header: 'Item name', value: (s) => s.item_name },
    { key: 'seller', header: 'Seller', value: (s) => s.owner_name ?? (s.admin_shop ? 'Admin shop' : null) },
    { key: 'shop_firm_id', header: 'Firm ID', value: (s) => s.shop_firm_id },
    { key: 'customer_name', header: 'Customer', value: (s) => s.customer_name ?? null },
    { key: 'customer_uuid', header: 'Customer UUID', value: (s) => s.customer_uuid ?? null },
    { key: 'world', header: 'World', value: (s) => s.world },
    { key: 'x', header: 'X', value: (s) => s.sign_x },
    { key: 'y', header: 'Y', value: (s) => s.sign_y },
    { key: 'z', header: 'Z', value: (s) => s.sign_z },
    { key: 'txn_id', header: 'Transaction', value: (s) => s.txn_id },
  ],
});
