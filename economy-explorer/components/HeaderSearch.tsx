'use client';
// Compact global search with a live typeahead popover. Submits to /search
// on Enter; Cmd/Ctrl+K opens it from anywhere; results dropdown shows the
// top accounts/firms/players as you type.

import { useEffect, useRef, useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import type { Route } from 'next';
import { searchPreview } from '@/lib/actions/search';
import type { SearchResult } from '@/lib/sql/search';

export function HeaderSearch() {
  const router = useRouter();
  const ref = useRef<HTMLInputElement>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const [mobileOpen, setMobileOpen] = useState(false);
  useEffect(() => { if (mobileOpen) ref.current?.focus(); }, [mobileOpen]);
  const wrapRef = useRef<HTMLDivElement>(null);
  const [value, setValue] = useState('');
  const [open, setOpen] = useState(false);
  const [fetched, setResults] = useState<SearchResult[]>([]);
  const [, startTransition] = useTransition();

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const isMod = e.metaKey || e.ctrlKey;
      if (isMod && (e.key === 'k' || e.key === 'K')) {
        e.preventDefault();
        setMobileOpen(true);
        ref.current?.focus();
        ref.current?.select();
      }
      if (e.key === 'Escape') {
        setOpen(false);
        ref.current?.blur();
        setMobileOpen(false);
        if (mobileOpen) toggleRef.current?.focus();
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [mobileOpen]);

  useEffect(() => {
    function onDocClick(e: MouseEvent) {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    }
    if (open) {
      document.addEventListener('mousedown', onDocClick);
      return () => document.removeEventListener('mousedown', onDocClick);
    }
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const term = value.trim();
    if (term.length < 2) return; // no preview below two characters (see `results`)
    let active = true;
    const id = setTimeout(() => {
      startTransition(async () => {
        try {
          const r = await searchPreview(term, 4);
          if (active) setResults(r);
        } catch {
          if (active) setResults([]);
        }
      });
    }, 150);
    return () => { active = false; clearTimeout(id); };
  }, [value, open]);

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    const q = value.trim();
    if (!q) return;
    setOpen(false);
    setMobileOpen(false);
    router.push(`/search?q=${encodeURIComponent(q)}`);
  }

  function gotoResult(r: SearchResult) {
    setOpen(false);
    setMobileOpen(false);
    if (r.kind === 'account' && r.account_id != null) router.push(`/accounts/${r.account_id}` as Route);
    else if (r.kind === 'firm' && r.firm_name) router.push(`/firms/${encodeURIComponent(r.firm_name)}` as Route);
    else router.push(`/search?q=${encodeURIComponent(value)}` as Route);
  }

  const results = value.trim().length < 2 ? [] : fetched;
  const grouped = {
    account: results.filter((r) => r.kind === 'account'),
    firm: results.filter((r) => r.kind === 'firm'),
    player: results.filter((r) => r.kind === 'player'),
  };

  return (
    <div ref={wrapRef} className={`header-search-wrap ${mobileOpen ? 'mobile-search-open' : ''}`}>
      <button ref={toggleRef} type="button" className="btn mobile-search-toggle" aria-expanded={mobileOpen} aria-controls="global-search-form" onClick={() => setMobileOpen(v => !v)}>{mobileOpen ? 'Close search' : 'Search'}</button>
      <form id="global-search-form" onSubmit={onSubmit} className="header-search" role="search" aria-label="Site">
        <input
          type="search"
          ref={ref}
          value={value}
          onChange={(e) => { setValue(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
          placeholder="Search accounts, firms, players…"
          aria-label="Search"
          autoComplete="off"
          spellCheck={false}
        />
        <span className="header-search-kbd" aria-hidden="true">⌘K</span>
      </form>
      {open && results.length > 0 && (
        <div className="header-search-popover" role="group" aria-label="Search suggestions">
          {grouped.account.length > 0 && (
            <ResultGroup label="Accounts">
              {grouped.account.map((r) => (
                <ResultItem key={`a-${r.account_id}`} onClick={() => gotoResult(r)}>
                  <span style={{ fontWeight: 500 }}>{r.label}</span>
                  {r.account_type && <span className={`badge badge-${r.account_type}`}>{r.account_type}</span>}
                  {r.balance && <span className="amount neutral" style={{ marginLeft: 'auto' }}>{r.balance}</span>}
                </ResultItem>
              ))}
            </ResultGroup>
          )}
          {grouped.firm.length > 0 && (
            <ResultGroup label="Firms">
              {grouped.firm.map((r) => (
                <ResultItem key={`f-${r.firm_id}`} onClick={() => gotoResult(r)}>
                  <span style={{ fontWeight: 500 }}>{r.label}</span>
                  {r.secondary && <span className="muted small">{r.secondary}</span>}
                </ResultItem>
              ))}
            </ResultGroup>
          )}
          {grouped.player.length > 0 && (
            <ResultGroup label="Players">
              {grouped.player.map((r, i) => (
                <ResultItem key={`p-${r.player_uuid ?? i}`} onClick={() => gotoResult(r)}>
                  <span style={{ fontWeight: 500 }}>{r.player_name ?? r.label}</span>
                  {r.secondary && <span className="muted small">seen {r.secondary}</span>}
                </ResultItem>
              ))}
            </ResultGroup>
          )}
          <div className="header-search-footer">
            <button type="button" className="rowlink" onClick={onSubmit}>
              See all results for &ldquo;{value}&rdquo; →
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function ResultGroup({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="header-search-group">
      <div className="header-search-group-label">{label}</div>
      {children}
    </div>
  );
}
function ResultItem({ children, onClick }: { children: React.ReactNode; onClick: () => void }) {
  return (
    <button type="button" className="header-search-item" onClick={onClick}>
      {children}
    </button>
  );
}
