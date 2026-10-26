import { EntityLink } from '@democracycraft/design-system/next';
import { Player } from '@/components/Player';
import { firmHref, previewHref } from '@/lib/entityLinks';
import type { MarketShopRow } from '@/lib/sql/market';

/** Who runs a shop: an admin shop, a firm (linked), or a player (linked, with head). */
export function ShopOwner({ shop }: { shop: Pick<MarketShopRow, 'admin_shop' | 'shop_firm_id' | 'owner_name' | 'shop_owner_uuid'> }) {
  if (shop.admin_shop) return <span className="muted">Admin shop</span>;
  if (shop.shop_firm_id) {
    return shop.owner_name
      ? <EntityLink kind="firm" href={firmHref(shop.owner_name)} name={shop.owner_name} preview={previewHref('firm', shop.shop_firm_id)} />
      : <span>Firm #{shop.shop_firm_id}</span>;
  }
  return <Player name={shop.owner_name} uuid={shop.shop_owner_uuid} />;
}
