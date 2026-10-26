// Fictional, deterministic fixtures for the /design-system gallery and the chart
// regression tests. Nothing here comes from the database.

const DAY_MS = 86_400_000;
const START = Date.UTC(2026, 8, 26); // 2026-09-26

export function isoDay(offset: number): string {
  return new Date(START + offset * DAY_MS).toISOString().slice(0, 10);
}

/** Small deterministic wobble so fixtures look organic without Math.random(). */
function wave(i: number, period: number, amp: number): number {
  return Math.sin((i / period) * Math.PI * 2) * amp;
}

/** 30 days of activity. Day 17 carries a single outlier (a mis-keyed $999B transfer). */
export const volumeWithOutlier = Array.from({ length: 30 }, (_, i) => ({
  date: isoDay(i),
  txn_count: Math.round(1100 + wave(i, 7, 220) + i * 6),
  total_volume: i === 17 ? '999000000000.00' : (52_000 + wave(i, 9, 14_000) + i * 650).toFixed(2),
}));

/** The same 30 days without the outlier. */
export const volumeNormal = volumeWithOutlier.map((d, i) => (i === 17 ? { ...d, total_volume: '61250.00' } : d));

/** A minting account's balance: deep negative, the longest axis labels in the app. */
export const treasuryBalance = Array.from({ length: 30 }, (_, i) => ({
  date: isoDay(i),
  balance: -120_400_000 - i * 1_230_000 + wave(i, 6, 900_000),
}));

/** A player's balance around $40,000 (the label production showed as "0,000"). */
export const playerBalance = Array.from({ length: 30 }, (_, i) => ({
  date: isoDay(i),
  balance: 38_200 + i * 240 + wave(i, 5, 1_800),
}));

/** Daily in/out with idle days: spiky data next to zeros is where spline curves overshoot. */
export const inOut = Array.from({ length: 30 }, (_, i) => ({
  day: isoDay(i),
  cr: i % 6 === 2 ? 0 : Math.max(0, Math.round(900 + wave(i, 8, 700))),
  dr: i % 5 === 0 ? 0 : Math.max(0, Math.round(650 + wave(i + 3, 6, 520))),
}));

/** Item price history: sparse spikes over zero-ish days. */
export const priceHistory = Array.from({ length: 30 }, (_, i) => ({
  day: isoDay(i),
  avg_unit_price: (i % 9 === 4 ? 48 : 4 + wave(i, 10, 1.5)).toFixed(2),
  sales: 20 + (i % 4) * 7,
}));

export const wealthByType = [
  { account_type: 'PERSONAL', account_count: 13_840, total_balance: '48210334.10' },
  { account_type: 'BUSINESS', account_count: 1_212, total_balance: '61022781.54' },
  { account_type: 'GOVERNMENT', account_count: 64, total_balance: '-157004112.80' },
  { account_type: 'SYSTEM', account_count: 9, total_balance: '3190220.00' },
];

export const balanceBuckets = [
  { bucket: 'neg', bucket_label: '< $0', account_count: 41 },
  { bucket: '0', bucket_label: '$0', account_count: 2_210 },
  { bucket: '1', bucket_label: '$1–100', account_count: 3_904 },
  { bucket: '2', bucket_label: '$100–1K', account_count: 4_120 },
  { bucket: '3', bucket_label: '$1K–10K', account_count: 2_610 },
  { bucket: '4', bucket_label: '$10K–100K', account_count: 1_022 },
  { bucket: '5', bucket_label: '$100K–1M', account_count: 214 },
  { bucket: '6', bucket_label: '> $1M', account_count: 23 },
];

/** Tax revenue by kind; fines include a refund day (negative). */
export const taxRevenue = Array.from({ length: 30 }, (_, i) => ({
  date: isoDay(i),
  personal: i % 7 === 0 ? 18_400 : 0,
  corporate: i % 7 === 0 ? 11_250 : 0,
  income: Math.round(9_800 + wave(i, 10, 2_400)),
  sales: Math.round(4_200 + wave(i, 7, 1_100)),
  fines: i === 12 ? -2_500 : Math.round(600 + wave(i, 4, 450)),
}));

/** Weekday × hour activity: evenings busiest, so hours 21–23 carry real weight. */
export const weekHours = Array.from({ length: 7 * 24 }, (_, k) => {
  const dow0 = Math.floor(k / 24);
  const hr = k % 24;
  const evening = Math.exp(-((hr - 21) ** 2) / 10);
  const weekend = dow0 >= 5 ? 1.35 : 1;
  return { dow0, hr, txn_count: Math.round((40 + 420 * evening) * weekend + wave(k, 13, 18)) };
});

export const candles = Array.from({ length: 24 }, (_, i) => {
  const open = 4 + wave(i, 8, 0.9) + i * 0.04;
  const close = open + wave(i + 2, 5, 0.5);
  return {
    bucketStart: new Date(START + i * DAY_MS),
    open, close,
    high: Math.max(open, close) + 0.3 + (i % 3) * 0.1,
    low: Math.min(open, close) - 0.25 - (i % 4) * 0.08,
    volume: 120 + (i % 6) * 40,
  };
});

export const sparkValues = [12, 18, 16, 24, 21, 32, 36, 33, 41];

/* ───────────── mock pages ───────────── */

/** Public Minecraft developer accounts, so the heads render; names in the mocks are fictional. */
export const people = {
  ada: { name: 'Ada_Lovelace', uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5' },
  grace: { name: 'GraceHopper', uuid: '853c80ef-3c37-49fd-aa49-938b674adae6' },
  alan: { name: 'Turing42', uuid: '61699b2e-d327-4a01-9f1e-0ea8c3f06bc6' },
};

type Counterparty =
  | { kind: 'player'; name: string; uuid: string }
  | { kind: 'account'; name: string; id: number; type: string }
  | { kind: 'firm'; name: string }
  | { kind: 'item'; name: string };

export const recentTransactions: { id: number; at: string; counterparty: Counterparty; memo: string; amount: string }[] = [
  { id: 918_204, at: '2026-10-25T09:12:00Z', counterparty: { kind: 'player', ...people.grace }, memo: 'Invoice 114, scaffolding', amount: '-1250.00' },
  { id: 918_166, at: '2026-10-25T07:40:00Z', counterparty: { kind: 'account', name: 'Treasury · Payroll', id: 12, type: 'GOVERNMENT' }, memo: 'Contract payment, bridge repair', amount: '18400.00' },
  { id: 917_902, at: '2026-10-24T21:05:00Z', counterparty: { kind: 'item', name: 'Spruce Planks ×512' }, memo: 'ChestShop purchase', amount: '-96.40' },
  { id: 917_455, at: '2026-10-24T14:31:00Z', counterparty: { kind: 'player', ...people.alan }, memo: 'Wages, week 43', amount: '-2100.00' },
  { id: 916_870, at: '2026-10-23T18:02:00Z', counterparty: { kind: 'account', name: 'Department of Revenue', id: 7, type: 'GOVERNMENT' }, memo: 'Sales tax', amount: '-312.75' },
  { id: 916_311, at: '2026-10-22T11:47:00Z', counterparty: { kind: 'firm', name: 'Harbour Bank' }, memo: 'Deposit transfer', amount: '5000.00' },
];

export const counterparties = [
  { name: 'Treasury · Payroll', id: 12, type: 'GOVERNMENT', total: '74100.00', count: 9 },
  { name: 'Harbour Bank · Deposits', id: 431, type: 'BUSINESS', total: '-22500.00', count: 6 },
  { name: 'Turing42', id: 3381, type: 'PERSONAL', total: '-8400.00', count: 4 },
  { name: 'Department of Revenue', id: 7, type: 'GOVERNMENT', total: '-3911.30', count: 31 },
];

export const myAccounts = [
  { id: 3301, name: 'Ada_Lovelace', type: 'PERSONAL', relation: 'Owner', balance: '12840.22' },
  { id: 3302, name: 'Savings', type: 'PERSONAL', relation: 'Owner', balance: '40150.00' },
];

export const firmAccounts = [
  { firm: 'Riverside Builders', role: 'Director', subtotal: '93330.55', accounts: [
    { id: 1204, name: 'Operating', relation: 'Firm finance (Director)', balance: '84210.55' },
    { id: 1205, name: 'Payroll', relation: 'Firm finance (Director)', balance: '9120.00' },
  ] },
];

export const monthCategories = [
  { key: 'wages', label: 'Wages', value: '8400.00' },
  { key: 'sales', label: 'Shop sales', value: '2310.50' },
  { key: 'purchases', label: 'Shop purchases', value: '-1845.20' },
  { key: 'tax', label: 'Tax', value: '-912.40' },
  { key: 'fines', label: 'Fines', value: '-250.00' },
  { key: 'transfers', label: 'Transfers out', value: '-3000.00' },
];

/** 90 days of a personal balance for the /me mock. */
export const personalBalance = Array.from({ length: 90 }, (_, i) => ({
  date: isoDay(i - 60),
  balance: (41_000 + i * 130 + wave(i, 11, 2_400)).toFixed(2),
}));
