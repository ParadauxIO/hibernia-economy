import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';
import { setViewer, type TestRole } from './viewer';

interface Download {
  filename: string;
  href: string;
  blob: Blob;
}

let downloads: Download[] = [];
const blobs = new Map<string, Blob>();

beforeEach(() => {
  downloads = [];
  blobs.clear();
  let n = 0;
  // jsdom implements neither object URLs nor anchor-click downloads; record both.
  URL.createObjectURL = vi.fn((obj: Blob | MediaSource) => {
    const url = `blob:test/${++n}`;
    if (obj instanceof Blob) blobs.set(url, obj);
    return url;
  });
  URL.revokeObjectURL = vi.fn();
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    const blob = blobs.get(this.href);
    if (!blob) throw new Error(`clicked a non-blob link: ${this.href}`);
    // The temporary anchor must be in the document when clicked (Firefox requires it).
    expect(document.body.contains(this)).toBe(true);
    downloads.push({ filename: this.download, href: this.href, blob });
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

async function onlyDownload(): Promise<{ filename: string; type: string; text: string }> {
  expect(downloads).toHaveLength(1);
  const [d] = downloads;
  if (!d) throw new Error('no download');
  // Blob.text() would strip a leading BOM; read raw bytes so we can assert it.
  const bytes = new Uint8Array(await d.blob.arrayBuffer());
  return { filename: d.filename, type: d.blob.type, text: new TextDecoder('utf-8', { ignoreBOM: true }).decode(bytes) };
}

describe.each<[TestRole, boolean]>([
  ['anon', false],
  ['player', true],
  ['moderator', true],
  ['admin', true],
])('export buttons for %s', (role, visible) => {
  it(`${visible ? 'are' : 'are not'} rendered`, () => {
    setViewer(role);
    render(
      <>
        <CsvButton filename="x.csv" headers={['a']} rows={[['1']]} />
        <JsonButton filename="x.json" data={{ a: 1 }} />
      </>,
    );
    expect(screen.queryByRole('button', { name: 'CSV' }) !== null).toBe(visible);
    expect(screen.queryByRole('button', { name: 'JSON' }) !== null).toBe(visible);
  });
});

describe('CsvButton', () => {
  beforeEach(() => setViewer('player'));

  it('is disabled when there are no rows', () => {
    render(<CsvButton filename="empty.csv" headers={['a']} rows={[]} />);
    expect(screen.getByRole('button', { name: 'CSV' })).toBeDisabled();
  });

  it('uses a custom label', () => {
    render(<CsvButton filename="x.csv" headers={['a']} rows={[['1']]} label="Export accounts" />);
    expect(screen.getByRole('button', { name: 'Export accounts' })).toBeEnabled();
  });

  it('downloads a BOM-prefixed UTF-8 CSV with quoting and empty nulls', async () => {
    render(
      <CsvButton
        filename="my-accounts.csv"
        headers={['Account ID', 'Name', 'Balance', 'Note']}
        rows={[
          [10, 'Steve', '1234.56', null],
          [11, 'Acme, Inc.', '0.00', undefined],
          [12, 'He said "hi"', '-3.50', 'line1\nline2'],
        ]}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'CSV' }));
    const d = await onlyDownload();
    expect(d.filename).toBe('my-accounts.csv');
    expect(d.type).toBe('text/csv;charset=utf-8');
    expect(d.text).toBe(
      '﻿Account ID,Name,Balance,Note\n' +
        '10,Steve,1234.56,\n' +
        '11,"Acme, Inc.",0.00,\n' +
        // A plain negative number is not a formula, so it stays numeric.
        '12,"He said ""hi""",-3.50,"line1\nline2"',
    );
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(downloads[0]?.href);
  });

  it.each([
    ['=HYPERLINK("http://evil")', '"\'=HYPERLINK(""http://evil"")"'],
    ['+cmd', "'+cmd"],
    ['@SUM(A1)', "'@SUM(A1)"],
    ['\tTab', "'\tTab"],
    ['safe=value', 'safe=value'],
  ])('neutralises formula injection in %j', async (cell, expected) => {
    render(<CsvButton filename="x.csv" headers={['v']} rows={[[cell]]} />);
    await userEvent.click(screen.getByRole('button', { name: 'CSV' }));
    expect((await onlyDownload()).text).toBe(`﻿v\n${expected}`);
  });

  it('also neutralises injection in headers', async () => {
    render(<CsvButton filename="x.csv" headers={['=1+1']} rows={[[1]]} />);
    await userEvent.click(screen.getByRole('button', { name: 'CSV' }));
    expect((await onlyDownload()).text).toBe("﻿'=1+1\n1");
  });
});

describe('JsonButton', () => {
  beforeEach(() => setViewer('player'));

  it('downloads pretty-printed JSON of the given data', async () => {
    const data = [{ account_id: 10, balance: '1234.56' }];
    render(<JsonButton filename="my-accounts.json" data={data} />);
    await userEvent.click(screen.getByRole('button', { name: 'JSON' }));
    const d = await onlyDownload();
    expect(d.filename).toBe('my-accounts.json');
    expect(d.type).toBe('application/json;charset=utf-8');
    expect(d.text).toBe(JSON.stringify(data, null, 2));
    expect(JSON.parse(d.text)).toEqual(data);
  });
});
