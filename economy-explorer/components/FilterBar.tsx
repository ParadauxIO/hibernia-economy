'use client';
// Search, filter chips, quick filters, the Columns menu and the summary line
// for a table declared with defineTable (spec §5.5). All state lives in the URL:
// this component reads it with parseTableState and writes it back with
// tableHref, and the server page re-renders the DataTable.

import '@democracycraft/design-system/table/styles.css';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useMemo, useOptimistic, useRef, useState, useSyncExternalStore, useTransition, type KeyboardEvent, type ReactNode } from 'react';
import { Popover } from '@democracycraft/design-system/popover';
import type { ColumnOption } from '@democracycraft/design-system/table';
import { FilterEditor } from '@/components/FilterBarEditors';
import {
  cleared,
  describeFilter,
  describeSort,
  filterText,
  foreignParams,
  isQuickActive,
  parseSearchInput,
  parseTableState,
  quickFilters,
  tableHref,
  withFilter,
  withFilters,
  withoutFilter,
  withQuery,
  type AnyTable,
  type EntityOption,
  type FieldDef,
  type FilterState,
  type TableState,
} from '@/lib/tables/core';

export interface FilterBarProps {
  table: AnyTable;
  /** Result count for the summary line; omit when unknown. */
  total?: number | null;
  /** Show the count as a floor ("1,000+") on unbounded feeds. */
  totalIsFloor?: boolean;
  /** Enum facet counts: field → option value → count. */
  counts?: Readonly<Record<string, Readonly<Record<string, number>>>>;
  /** Chip names for entity ids already on the URL, keyed `field:value`. */
  entityLabels?: Readonly<Record<string, string>>;
  /** Server action for entity typeaheads. */
  searchEntity?: (field: string, text: string) => Promise<EntityOption[]>;
  /** Enables the Columns menu for the DataTable with this id. */
  columns?: readonly ColumnOption[];
  tableId?: string;
  /** Currency symbol for money chips. */
  currency?: string;
  /** Export controls, shown at the end of the toolbar. */
  exportSlot?: ReactNode;
}

type Open =
  | { kind: 'add'; field: string | null }
  | { kind: 'chip'; field: string }
  | { kind: 'date'; field: string }
  | { kind: 'columns' }
  | null;

const SAFE_ID = /^[A-Za-z][\w-]{0,63}$/;
const COLUMNS_EVENT = 'explorer:table-columns';

function subscribeColumns(cb: () => void): () => void {
  window.addEventListener('storage', cb);
  window.addEventListener(COLUMNS_EVENT, cb);
  return () => {
    window.removeEventListener('storage', cb);
    window.removeEventListener(COLUMNS_EVENT, cb);
  };
}

function readStorage(key: string): string {
  try {
    return window.localStorage.getItem(key) ?? '';
  } catch {
    return '';
  }
}

/** Hidden optional columns for a table, persisted per table in localStorage. */
function useHiddenColumns(tableKey: string, columns: readonly ColumnOption[]): [string[], (key: string) => void] {
  const storageKey = `explorer:table-columns:${tableKey}`;
  const raw = useSyncExternalStore(subscribeColumns, () => readStorage(storageKey), () => '');
  const hidden = useMemo(() => {
    let parsed: unknown = [];
    try { parsed = raw ? JSON.parse(raw) : []; } catch { parsed = []; }
    const optional = new Set(columns.filter((c) => c.optional).map((c) => c.key));
    return Array.isArray(parsed) ? parsed.filter((k): k is string => typeof k === 'string' && optional.has(k)) : [];
  }, [raw, columns]);
  const toggle = (key: string) => {
    const next = hidden.includes(key) ? hidden.filter((k) => k !== key) : [...hidden, key];
    try { window.localStorage.setItem(storageKey, JSON.stringify(next)); } catch { /* private mode: session only */ }
    window.dispatchEvent(new Event(COLUMNS_EVENT));
  };
  return [hidden, toggle];
}

function SearchIcon() {
  return (
    <svg className="fb-search-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
      <circle cx="7" cy="7" r="4.75" />
      <path d="m10.5 10.5 3.25 3.25" strokeLinecap="round" />
    </svg>
  );
}

function PlusIcon() {
  return (
    <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
      <path d="M8 3.5v9M3.5 8h9" strokeLinecap="round" />
    </svg>
  );
}

export function FilterBar({
  table, total, totalIsFloor = false, counts, entityLabels, searchEntity, columns, tableId, currency = '$', exportSlot,
}: FilterBarProps) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const urlState = useMemo(() => parseTableState(params, table), [params, table]);
  const [pending, startTransition] = useTransition();
  const [state, setOptimistic] = useOptimistic<TableState>(urlState);

  const [text, setText] = useState(urlState.q);
  const [syncedQ, setSyncedQ] = useState(urlState.q);
  if (syncedQ !== urlState.q) {
    // The URL's q changed underneath us (back/forward, a link): follow it.
    setSyncedQ(urlState.q);
    setText(urlState.q);
  }

  const [open, setOpen] = useState<Open>(null);
  const [localLabels, setLocalLabels] = useState<Record<string, string>>({});
  const labels = useMemo(() => ({ ...entityLabels, ...localLabels }), [entityLabels, localLabels]);
  const searchRef = useRef<HTMLInputElement>(null);
  const [hiddenColumns, toggleColumn] = useHiddenColumns(table.id, columns ?? []);

  const fields = table.filters as Record<string, FieldDef>;
  const quick = useMemo(() => quickFilters(table), [table]);
  const describe = (f: FilterState) => describeFilter(f, table, { currency, entityLabels: labels });
  const textOf = (f: FilterState) => filterText(f, table, { currency, entityLabels: labels });

  function go(next: TableState) {
    const href = tableHref(pathname, next, table, foreignParams(params, new Set(params.keys())));
    startTransition(() => {
      setOptimistic(next);
      router.push(href, { scroll: false });
    });
  }

  /** Apply an editor's result. `close` returns focus to the popover's trigger. */
  function apply(field: string, f: FilterState | null, close: () => void) {
    close();
    go(f ? withFilter(state, f) : withoutFilter(state, field));
    // A removed chip takes its trigger with it; keep focus somewhere useful.
    if (!f) searchRef.current?.focus();
  }

  function onSearchChange(value: string) {
    const parsed = parseSearchInput(value, table, { keepTrailing: true });
    if (parsed.filters.length > 0) {
      setText(parsed.text);
      go(withFilters(state, parsed.filters));
    } else {
      setText(value);
    }
  }

  function commitSearch() {
    const parsed = parseSearchInput(text, table);
    setText(parsed.text);
    if (parsed.filters.length === 0 && parsed.text === state.q) return;
    go(withQuery(withFilters(state, parsed.filters), parsed.text));
  }

  function onSearchKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter') {
      e.preventDefault();
      commitSearch();
    } else if (e.key === 'Backspace' && e.currentTarget.value === '' && state.filters.length > 0) {
      e.preventDefault();
      const last = state.filters[state.filters.length - 1] as FilterState;
      go(withoutFilter(state, last.field));
    } else if (e.key === 'Escape' && text !== '') {
      e.preventDefault();
      setText(state.q);
    }
  }

  const editor = (key: string, close: () => void) => {
    const field = fields[key];
    if (!field) return null;
    return (
      <FilterEditor
        table={table}
        fieldKey={key}
        field={field}
        current={state.filters.find((f) => f.field === key)}
        counts={counts?.[key]}
        searchEntity={searchEntity}
        entityLabels={labels}
        onEntityLabel={(value, label) => setLocalLabels((l) => ({ ...l, [`${key}:${value}`]: label }))}
        onApply={(f) => apply(key, f, close)}
        onCancel={close}
      />
    );
  };

  const addField = open?.kind === 'add' ? open.field : null;
  const sort = describeSort(state, table);
  const optionalColumns = (columns ?? []).filter((c) => c.optional);
  const showColumns = optionalColumns.length > 0 && tableId !== undefined && SAFE_ID.test(tableId);
  const hasAny = state.filters.length > 0 || state.q !== '';
  const countText = total === null || total === undefined
    ? null
    : `${total.toLocaleString('en-US')}${totalIsFloor ? '+' : ''} ${total === 1 && !totalIsFloor ? table.noun.one : table.noun.many}`;

  return (
    <div className="fb" aria-busy={pending || undefined}>
      {showColumns && hiddenColumns.length > 0 && (
        <style>{hiddenColumns.filter((k) => SAFE_ID.test(k)).map((k) => `#${tableId} [data-col="${k}"]`).join(',\n') + '{display:none}'}</style>
      )}
      <div className="fb-row">
        <form className="fb-search" role="search" aria-label={`Search and filter ${table.noun.many}`} onSubmit={(event) => { event.preventDefault(); commitSearch(); }}>
          <div className="fb-search-field">
            <SearchIcon />
            <input
              ref={searchRef}
              type="search"
              aria-label={table.searchPlaceholder}
              placeholder={table.searchPlaceholder}
              value={text}
              autoComplete="off"
              spellCheck={false}
              enterKeyHint="search"
              onChange={(e) => onSearchChange(e.target.value)}
              onKeyDown={onSearchKeyDown}
            />
            {(text || state.q) && <button type="button" className="fb-search-clear" aria-label="Clear search text" onClick={() => { setText(''); go(withQuery(state, '')); searchRef.current?.focus(); }}>×</button>}
          </div>
          <button type="submit" className="fb-btn fb-search-submit">Search</button>
        </form>
        <div className="fb-actions">
          {Object.entries(fields).filter(([, field]) => field.kind === 'date').map(([key, field]) => (
            <Popover key={key} sheet className="fb-popover" label={`Filter by ${field.label}`}
              open={open?.kind === 'date' && open.field === key}
              onOpenChange={(o) => setOpen(o ? { kind: 'date', field: key } : null)}
              trigger={<button type="button" className="fb-btn" aria-pressed={state.filters.some((f) => f.field === key)}>{field.label}</button>}>
              {({ close }) => editor(key, close)}
            </Popover>
          ))}
          <Popover
            sheet
            className="fb-popover"
            label={addField ? `Filter by ${fields[addField]?.label ?? addField}` : 'Add a filter'}
            open={open?.kind === 'add'}
            onOpenChange={(o) => setOpen(o ? { kind: 'add', field: null } : null)}
            trigger={<button type="button" className="fb-btn"><PlusIcon />Filter</button>}
          >
            {({ close }) => (addField ? editor(addField, close) : (
              <div className="fb-pop">
                <p className="fb-pop-title">Filter by</p>
                <ul className="fb-menu">
                  {Object.entries(fields).map(([key, field]) => (
                    <li key={key}>
                      <button
                        type="button"
                        className="fb-menu-item"
                        aria-current={state.filters.some((f) => f.field === key) ? 'true' : undefined}
                        onClick={() => setOpen({ kind: 'add', field: key })}
                      >
                        <span>{field.label}</span>
                        {field.hint && <small>{field.hint}</small>}
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </Popover>
          {showColumns && (
            <Popover
              sheet
              className="fb-popover"
              label="Columns"
              open={open?.kind === 'columns'}
              onOpenChange={(o) => setOpen(o ? { kind: 'columns' } : null)}
              trigger={<button type="button" className="fb-btn">Columns</button>}
            >
              <div className="fb-pop">
                <p className="fb-pop-title">Columns</p>
                <div className="fb-columns" role="group" aria-label="Visible columns">
                  {optionalColumns.map((c) => (
                    <label key={c.key} className="fb-option">
                      <input type="checkbox" checked={!hiddenColumns.includes(c.key)} onChange={() => toggleColumn(c.key)} />
                      <span>{c.label}</span>
                    </label>
                  ))}
                </div>
              </div>
            </Popover>
          )}
          {exportSlot && <div className="fb-export">{exportSlot}</div>}
        </div>
      </div>

      {state.filters.length > 0 && (
        <div className="fb-chips-row">
          <ul className="fb-chips" aria-label="Active filters">
            {state.filters.map((f) => {
              const d = describe(f);
              const label = textOf(f);
              return (
                <li key={f.field}>
                  <span className="fb-chip">
                    <Popover
                      sheet
                      className="fb-popover"
                      label={`Filter by ${fields[f.field]?.label ?? f.field}`}
                      open={open?.kind === 'chip' && open.field === f.field}
                      onOpenChange={(o) => setOpen(o ? { kind: 'chip', field: f.field } : null)}
                      trigger={
                        <button type="button" className="fb-chip-edit" aria-label={`${label}. Edit filter`}>
                          {d.value === null ? (
                            <span className="fb-chip-value">{d.label}</span>
                          ) : (
                            <>
                              <span className="fb-chip-label">{d.label}{d.sep.trim()}</span>
                              <span className="fb-chip-value">{d.value}</span>
                            </>
                          )}
                        </button>
                      }
                    >
                      {({ close }) => editor(f.field, close)}
                    </Popover>
                    <button
                      type="button"
                      className="fb-chip-remove"
                      aria-label={`Remove filter ${label}`}
                      onClick={() => { go(withoutFilter(state, f.field)); searchRef.current?.focus(); }}
                    >
                      <span aria-hidden="true">×</span>
                    </button>
                  </span>
                </li>
              );
            })}
          </ul>
          <button type="button" className="fb-text-btn" onClick={() => { setText(''); go(cleared(state)); searchRef.current?.focus(); }}>
            Clear all
          </button>
        </div>
      )}

      {!hasAny && quick.length > 0 && (
        <div className="fb-suggest" role="group" aria-label="Suggested filters">
          <span>Try</span>
          {quick.filter((qf) => !isQuickActive(state, qf)).map((qf) => (
            <button key={qf.label} type="button" className="fb-suggest-chip" onClick={() => go(withFilters(state, qf.filters))}>
              {qf.label}
            </button>
          ))}
        </div>
      )}

      <div className="fb-summary">
        <span role="status">
          {pending ? <span className="fb-pending">Updating</span> : countText && <strong>{countText}</strong>}
        </span>
        <span>Sorted by {sort.label.toLowerCase()}, {sort.words}</span>
        {hasAny && state.filters.length === 0 && (
          <button type="button" className="fb-text-btn" onClick={() => { setText(''); go(cleared(state)); }}>Clear search</button>
        )}
      </div>
    </div>
  );
}
