import { Button, Input } from '@democracycraft/design-system';

/** Item search for the market landing: a plain GET form into the catalogue's search box. */
export function MarketSearch() {
  return <form className="market-search" action="/chestshop" role="search" aria-label="Items">
    <label htmlFor="market-item-search" className="field-label">Find an item</label>
    <div className="market-search-controls">
      <Input id="market-item-search" name="q" type="search" placeholder="Diamond, oak log, netherite…" />
      <Button type="submit">Search items</Button>
    </div>
  </form>;
}
