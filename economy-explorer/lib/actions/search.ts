'use server';
import { requireLinkedViewer } from '@/lib/auth/linked';
// Server actions behind every search box: the header typeahead and ⌘K palette
// (searchPreview, searchSuggest) and the entity filter chips on /transactions
// and /chestshop/sales. All return public names only (what /search shows) and
// are prefix lookups cached for 60 s per (type, prefix) in lib/services/search.ts.

import { suggest, suggestEntities } from '@/lib/services/search';
import type { ItemSearchResult, SearchHit, SearchHitKind, SearchResult } from '@/lib/sql/search';
import type { EntityOption } from '@/lib/tables/core';

/**
 * The header typeahead and the ⌘K palette. Unchanged contract: at most `limit`
 * accounts, firms and players (in that order) for 2+ characters, else []. Each
 * result may now also carry `href` and `match`.
 */
export async function searchPreview(q: string, limit = 4): Promise<SearchResult[]> {
  await requireLinkedViewer();
  return suggestEntities(q, typeof limit === 'number' ? limit : 4);
}

/**
 * The typeahead with a choice of kinds, including items (`kind: 'item'`), for
 * the ⌘K palette. Same rules and cache as searchPreview.
 */
export async function searchSuggest(q: string, options: { limit?: number; kinds?: SearchHitKind[] } = {}): Promise<SearchHit[]> {
  await requireLinkedViewer();
  const kinds = Array.isArray(options.kinds) ? options.kinds : undefined;
  return suggest(q, { ...(typeof options.limit === 'number' ? { limit: options.limit } : {}), ...(kinds ? { kinds } : {}) });
}

function playerOption(r: SearchResult): EntityOption {
  return { value: r.player_uuid ?? r.label, label: r.label };
}

function accountOption(r: SearchResult): EntityOption {
  return { value: String(r.account_id), label: `${r.label} #${r.account_id}`, detail: r.account_type ? r.account_type.charAt(0) + r.account_type.slice(1).toLowerCase() : '' };
}

function itemOption(r: ItemSearchResult): EntityOption {
  return { value: r.item_key, label: r.label, ...(r.secondary ? { detail: r.secondary } : {}) };
}

/** Entity chips on /transactions: `account` (accounts, and players' personal accounts) and `player`. */
export async function searchTransactionEntity(field: string, text: string): Promise<EntityOption[]> {
  await requireLinkedViewer();
  if (typeof text !== 'string') return [];
  if (field === 'player') {
    return (await suggest(text, { limit: 8, kinds: ['player'] })).filter((h): h is SearchResult => h.kind === 'player').map(playerOption);
  }
  if (field === 'account') {
    const hits = await suggest(text, { limit: 6, kinds: ['account', 'player'] });
    const out: EntityOption[] = [];
    for (const h of hits) {
      if (h.kind === 'account' && h.account_id !== null) out.push(accountOption(h));
      const personal = h.kind === 'player' && h.href ? /^\/accounts\/(\d+)$/.exec(h.href) : null;
      if (personal) out.push({ value: personal[1] as string, label: `${h.label} #${personal[1]}`, detail: 'Personal' });
    }
    return out.filter((o, i) => out.findIndex((x) => x.value === o.value) === i);
  }
  return [];
}

/** Entity chips on /chestshop/sales: item, customer, shop owner and firm. */
export async function searchSalesEntity(field: string, text: string): Promise<EntityOption[]> {
  await requireLinkedViewer();
  if (typeof text !== 'string') return [];
  if (field === 'item') {
    return (await suggest(text, { limit: 8, kinds: ['item'] })).filter((h): h is ItemSearchResult => h.kind === 'item').map(itemOption);
  }
  if (field === 'customer' || field === 'owner') {
    return (await suggest(text, { limit: 8, kinds: ['player'] })).filter((h): h is SearchResult => h.kind === 'player').map(playerOption);
  }
  if (field === 'firm') {
    return (await suggest(text, { limit: 8, kinds: ['firm'] }))
      .filter((h): h is SearchResult => h.kind === 'firm' && h.firm_id !== null)
      .map((h) => ({ value: String(h.firm_id), label: h.label }));
  }
  return [];
}
