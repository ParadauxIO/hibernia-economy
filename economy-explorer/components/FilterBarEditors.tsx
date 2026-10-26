'use client';
// The per-kind popover bodies behind a filter chip. Each editor gets the
// current filter (if any) and reports a new filter, or null to remove it.

import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode, type RefObject } from 'react';
import {
  DATE_PRESETS,
  normaliseFilter,
  sameFilter,
  type AnyTable,
  type EntityOption,
  type FieldDef,
  type FilterState,
  type Operator,
} from '@/lib/tables/core';

export interface EditorProps {
  table: AnyTable;
  fieldKey: string;
  field: FieldDef;
  current: FilterState | undefined;
  onApply: (filter: FilterState | null) => void;
  onCancel: () => void;
  counts?: Readonly<Record<string, number>> | undefined;
  searchEntity?: ((field: string, text: string) => Promise<EntityOption[]>) | undefined;
  onEntityLabel?: (value: string, label: string) => void;
  entityLabels?: Readonly<Record<string, string>> | undefined;
}

/**
 * An editor that replaces the + Filter menu inside a popover that is already
 * open takes focus itself: the clicked menu item has just unmounted. When the
 * editor opens with its popover, this call lands while the panel is still
 * hidden and is ignored; the Popover base then focuses `[data-autofocus]`.
 */
function useFocusWhenSwappedIn(ref: RefObject<HTMLElement | null>): void {
  useEffect(() => {
    const root = ref.current;
    const target = root?.querySelector<HTMLElement>('[data-autofocus]') ?? root?.querySelector<HTMLElement>('input, select, button');
    target?.focus({ preventScroll: true });
  }, [ref]);
}

const NUMBER_OPS: { op: Operator; label: string }[] = [
  { op: 'gt', label: 'More than' },
  { op: 'gte', label: 'At least' },
  { op: 'lt', label: 'Less than' },
  { op: 'lte', label: 'At most' },
  { op: 'between', label: 'Between' },
  { op: 'eq', label: 'Exactly' },
];

function Frame({ title, hint, error, onSubmit, onCancel, children, applyLabel = 'Apply', canRemove, onRemove }: {
  title: string;
  hint?: string | undefined;
  error?: string | null;
  onSubmit: (e: FormEvent) => void;
  onCancel: () => void;
  children: ReactNode;
  applyLabel?: string;
  canRemove?: boolean;
  onRemove?: () => void;
}) {
  const titleId = useId();
  const formRef = useRef<HTMLFormElement>(null);
  useFocusWhenSwappedIn(formRef);
  return (
    <form ref={formRef} className="fb-pop" onSubmit={onSubmit} aria-labelledby={titleId} noValidate>
      <p className="fb-pop-title" id={titleId}>{title}</p>
      {hint && <p className="fb-pop-hint">{hint}</p>}
      {children}
      {error && <p className="fb-pop-error" role="alert">{error}</p>}
      <div className="fb-pop-actions">
        {canRemove && onRemove && <button type="button" className="fb-btn fb-btn-quiet fb-btn-sm fb-remove" onClick={onRemove}>Remove</button>}
        <button type="button" className="fb-btn fb-btn-quiet fb-btn-sm" onClick={onCancel}>Cancel</button>
        <button type="submit" className="fb-btn fb-btn-primary fb-btn-sm">{applyLabel}</button>
      </div>
    </form>
  );
}

function EnumEditor({ fieldKey, field, current, onApply, onCancel, counts, table }: EditorProps & { field: Extract<FieldDef, { kind: 'enum' }> }) {
  const [selected, setSelected] = useState<string[]>(current?.op === 'in' ? [...current.values] : []);
  const toggle = (v: string) => setSelected((s) => (s.includes(v) ? s.filter((x) => x !== v) : [...s, v]));
  return (
    <Frame
      title={field.label}
      hint={field.hint}
      onCancel={onCancel}
      canRemove={current !== undefined}
      onRemove={() => onApply(null)}
      onSubmit={(e) => {
        e.preventDefault();
        onApply(selected.length ? normaliseFilter(table, fieldKey, 'in', selected) : null);
      }}
    >
      <fieldset className="fb-options">
        <legend className="sr-only">{field.label}</legend>
        {field.options.map((o, i) => (
          <label key={o.value} className="fb-option">
            <input
              type="checkbox"
              checked={selected.includes(o.value)}
              onChange={() => toggle(o.value)}
              {...(i === 0 ? { 'data-autofocus': true } : {})}
            />
            <span>{o.label}</span>
            {counts?.[o.value] !== undefined && <span className="fb-option-count">{counts[o.value]?.toLocaleString('en-US')}</span>}
          </label>
        ))}
      </fieldset>
    </Frame>
  );
}

function NumberEditor({ fieldKey, field, current, onApply, onCancel, table }: EditorProps & { field: Extract<FieldDef, { kind: 'money' | 'number' }> }) {
  const [op, setOp] = useState<Operator>(current?.op ?? 'gt');
  const [a, setA] = useState(current?.values[0] ?? '');
  const [b, setB] = useState(current?.values[1] ?? '');
  const [error, setError] = useState<string | null>(null);
  const money = field.kind === 'money';
  const example = money ? 'an amount like 10k, 2,500 or 99.50' : 'a number';
  return (
    <Frame
      title={field.label}
      hint={field.hint}
      error={error}
      onCancel={onCancel}
      canRemove={current !== undefined}
      onRemove={() => onApply(null)}
      onSubmit={(e) => {
        e.preventDefault();
        const f = normaliseFilter(table, fieldKey, op, op === 'between' ? [a, b] : [a]);
        if (!f) setError(`Enter ${example}${op === 'between' ? ' in both boxes' : ''}.`);
        else onApply(f);
      }}
    >
      <label className="fb-field">
        Condition
        <select className="fb-input" value={op} onChange={(e) => setOp(e.target.value as Operator)}>
          {NUMBER_OPS.map((o) => <option key={o.op} value={o.op}>{o.label}</option>)}
        </select>
      </label>
      <div className={op === 'between' ? 'fb-pair' : undefined}>
        <label className="fb-field">
          {op === 'between' ? 'From' : 'Value'}
          <input className="fb-input" inputMode="decimal" autoComplete="off" value={a} placeholder={money ? '10k' : '0'} onChange={(e) => { setA(e.target.value); setError(null); }} data-autofocus />
        </label>
        {op === 'between' && (
          <label className="fb-field">
            To
            <input className="fb-input" inputMode="decimal" autoComplete="off" value={b} placeholder={money ? '50k' : '0'} onChange={(e) => { setB(e.target.value); setError(null); }} />
          </label>
        )}
      </div>
    </Frame>
  );
}

function DateEditor({ fieldKey, field, current, onApply, onCancel, table }: EditorProps) {
  const isAbsDay = (v: string | undefined) => (v && /^\d{4}-\d{2}-\d{2}$/.test(v) ? v : '');
  const [from, setFrom] = useState(current && (current.op === 'between' || current.op === 'gte') ? isAbsDay(current.values[0]) : '');
  const [to, setTo] = useState(current?.op === 'between' ? isAbsDay(current.values[1]) : current?.op === 'lte' ? isAbsDay(current.values[0]) : '');
  const [error, setError] = useState<string | null>(null);
  const now = new Date();
  return (
    <Frame
      title={field.label}
      hint={field.hint}
      error={error}
      onCancel={onCancel}
      canRemove={current !== undefined}
      onRemove={() => onApply(null)}
      onSubmit={(e) => {
        e.preventDefault();
        if (from && to && from > to) { setError('End date must be on or after start date.'); return; }
        const f = from && to ? normaliseFilter(table, fieldKey, 'between', [from, to])
          : from ? normaliseFilter(table, fieldKey, 'gte', [from])
            : to ? normaliseFilter(table, fieldKey, 'lte', [to]) : null;
        if (!f) setError('Pick a start date, an end date, or both.');
        else onApply(f);
      }}
    >
      <p className="fb-pop-hint">Choose either date or both. Dates include the full day in UTC.</p>
      <div className="fb-presets" role="group" aria-label="Presets">
        {DATE_PRESETS.map((p, i) => {
          const f = normaliseFilter(table, fieldKey, p.op, p.values(now));
          const pressed = !!(f && current && sameFilter(f, current));
          return (
            <button
              key={p.label}
              type="button"
              className="fb-btn fb-btn-sm"
              aria-pressed={pressed}
              onClick={() => { if (f) onApply(f); }}
              {...(i === 0 ? { 'data-autofocus': true } : {})}
            >
              {p.label}
            </button>
          );
        })}
      </div>
      <div className="fb-pair">
        <label className="fb-field">
          From
          <input className="fb-input" type="date" value={from} min="2000-01-01" max={to || "2100-12-31"} onChange={(e) => { setFrom(e.target.value); setError(null); }} />
        </label>
        <label className="fb-field">
          To
          <input className="fb-input" type="date" value={to} min={from || "2000-01-01"} max="2100-12-31" onChange={(e) => { setTo(e.target.value); setError(null); }} />
        </label>
      </div>
    </Frame>
  );
}

function TextEditor({ fieldKey, field, current, onApply, onCancel, table }: EditorProps) {
  const [op, setOp] = useState<Operator>(current?.op ?? 'contains');
  const [value, setValue] = useState(current?.values[0] ?? '');
  const [error, setError] = useState<string | null>(null);
  return (
    <Frame
      title={field.label}
      hint={field.hint}
      error={error}
      onCancel={onCancel}
      canRemove={current !== undefined}
      onRemove={() => onApply(null)}
      onSubmit={(e) => {
        e.preventDefault();
        const f = normaliseFilter(table, fieldKey, op, [value]);
        if (!f) setError('Enter some text.');
        else onApply(f);
      }}
    >
      <label className="fb-field">
        Match
        <select className="fb-input" value={op} onChange={(e) => setOp(e.target.value as Operator)}>
          <option value="contains">Contains</option>
          <option value="eq">Is exactly</option>
        </select>
      </label>
      <label className="fb-field">
        Text
        <input className="fb-input" autoComplete="off" value={value} maxLength={200} onChange={(e) => { setValue(e.target.value); setError(null); }} data-autofocus />
      </label>
    </Frame>
  );
}

function BooleanEditor({ fieldKey, field, current, onApply, onCancel, table }: EditorProps) {
  const [value, setValue] = useState(current?.values[0] ?? 'true');
  const name = useId();
  return (
    <Frame
      title={field.label}
      hint={field.hint}
      onCancel={onCancel}
      canRemove={current !== undefined}
      onRemove={() => onApply(null)}
      onSubmit={(e) => {
        e.preventDefault();
        onApply(normaliseFilter(table, fieldKey, 'eq', [value]));
      }}
    >
      <fieldset className="fb-options">
        <legend className="sr-only">{field.label}</legend>
        {[['true', 'Yes'], ['false', 'No']].map(([v = '', label], i) => (
          <label key={v} className="fb-option">
            <input type="radio" name={name} value={v} checked={value === v} onChange={() => setValue(v)} {...(i === 0 ? { 'data-autofocus': true } : {})} />
            <span>{label}</span>
          </label>
        ))}
      </fieldset>
    </Frame>
  );
}

function EntityEditor({ fieldKey, field, current, onApply, onCancel, table, searchEntity, onEntityLabel, entityLabels }: EditorProps & { field: Extract<FieldDef, { kind: 'entity' }> }) {
  const [text, setText] = useState('');
  const [results, setResults] = useState<{ for: string; options: EntityOption[] }>({ for: '', options: [] });
  const [active, setActive] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const seq = useRef(0);
  const listId = useId();
  const term = text.trim();

  useEffect(() => {
    if (!searchEntity || term === '') return;
    const mine = ++seq.current;
    const timer = setTimeout(() => {
      searchEntity(fieldKey, term)
        .then((options) => { if (mine === seq.current) setResults({ for: term, options }); })
        .catch(() => { if (mine === seq.current) setResults({ for: term, options: [] }); });
    }, 180);
    return () => clearTimeout(timer);
  }, [term, fieldKey, searchEntity]);

  const options = term !== '' && results.for === term ? results.options : [];
  const existing = current?.op === 'in' ? current.values : [];
  const choose = (o: EntityOption) => {
    onEntityLabel?.(o.value, o.label);
    onApply(normaliseFilter(table, fieldKey, 'in', [...existing, o.value]));
  };
  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (options.length === 0) return;
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive((i) => (i + 1) % options.length); }
    if (e.key === 'ArrowUp') { e.preventDefault(); setActive((i) => (i - 1 + options.length) % options.length); }
  };

  return (
    <Frame
      title={field.label}
      hint={field.hint ?? `Type the start of a ${field.entity} name`}
      error={error}
      onCancel={onCancel}
      canRemove={current !== undefined}
      onRemove={() => onApply(null)}
      applyLabel="Add"
      onSubmit={(e) => {
        e.preventDefault();
        const pick = options[Math.min(active, options.length - 1)];
        if (pick) return choose(pick);
        // No suggestion picked: accept a raw id or name the field understands.
        const f = term ? normaliseFilter(table, fieldKey, 'in', [...existing, term.replace(/^#/, '')]) : null;
        if (f) onApply(f);
        else setError(searchEntity ? 'Pick a match from the list.' : `Enter a ${field.entity} id.`);
      }}
    >
      {existing.length > 0 && (
        <p className="fb-pop-hint">
          Already filtering on {existing.map((v) => entityLabels?.[`${fieldKey}:${v}`] ?? `#${v}`).join(', ')}.
        </p>
      )}
      <label className="fb-field">
        Search
        <input
          className="fb-input"
          role="combobox"
          aria-expanded={options.length > 0}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={options.length > 0 ? `${listId}-${Math.min(active, options.length - 1)}` : undefined}
          autoComplete="off"
          value={text}
          onChange={(e) => { setText(e.target.value); setActive(0); setError(null); }}
          onKeyDown={onKeyDown}
          data-autofocus
        />
      </label>
      <ul className="fb-results" id={listId} role="listbox" aria-label={`${field.label} matches`}>
        {options.map((o, i) => (
          <li
            key={o.value}
            id={`${listId}-${i}`}
            role="option"
            aria-selected={i === Math.min(active, options.length - 1)}
            className="fb-result"
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => choose(o)}
          >
            <span>{o.label}</span>
            {o.detail && <small>{o.detail}</small>}
          </li>
        ))}
      </ul>
      {term !== '' && results.for === term && options.length === 0 && <p className="fb-results-empty">No matches.</p>}
    </Frame>
  );
}

/** The editor for one field, by kind. */
export function FilterEditor(props: EditorProps) {
  const { field } = props;
  switch (field.kind) {
    case 'enum': return <EnumEditor {...props} field={field} />;
    case 'money':
    case 'number': return <NumberEditor {...props} field={field} />;
    case 'date': return <DateEditor {...props} />;
    case 'text': return <TextEditor {...props} />;
    case 'boolean': return <BooleanEditor {...props} />;
    case 'entity': return <EntityEditor {...props} field={field} />;
  }
}
