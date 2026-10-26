import { describe, it, expect, vi } from 'vitest';
import { CATEGORIES, CATEGORY_LABELS, PLUGIN_CATEGORIES, categorize, countsAsIncomeOrSpending, type CategoryInput } from '@/lib/categories';

const flow = (over: Partial<CategoryInput>): CategoryInput => ({
  pluginKey: '', taxKind: null, counterpartyType: 'PERSONAL', direction: 'in', internal: false, ...over,
});

describe('categorize', () => {
  it.each([
    // [pluginKey, direction, counterparty, expected]
    ['treasury-salary', 'in', 'GOVERNMENT', 'wages'],
    ['ChestShop', 'in', 'SYSTEM', 'shop_sales'],
    ['ChestShop', 'out', 'SYSTEM', 'shop_purchases'],
    ['realty', 'out', 'GOVERNMENT', 'property'],
    ['GriefPreventionUI', 'out', 'SYSTEM', 'property'],
    ['BusinessPlugin', 'in', 'BUSINESS', 'business'],
    ['business', 'out', 'GOVERNMENT', 'business'],
    ['DemocracyRobbery', 'out', 'PERSONAL', 'crime'],
    ['BountyHunters', 'in', 'SYSTEM', 'crime'],
    ['homelandsecurity', 'out', 'GOVERNMENT', 'fines'],
    ['HomelandSecurity', 'out', 'GOVERNMENT', 'fines'],
    ['Exams', 'in', 'SYSTEM', 'jobs'],
    ['nightcore', 'in', 'SYSTEM', 'jobs'],
    ['CustomFishing', 'in', 'SYSTEM', 'jobs'],
    ['MobsToEggs', 'out', 'SYSTEM', 'jobs'],
    ['Treasury-Core', 'out', 'PERSONAL', 'transfers'],
    ['Treasury-Core', 'in', 'BUSINESS', 'transfers'],
    ['Treasury-Core', 'in', 'GOVERNMENT', 'other'],
    ['treasury', 'in', 'SYSTEM', 'other'],
    ['treasury', 'out', 'PERSONAL', 'transfers'],
    ['', 'in', 'PERSONAL', 'transfers'],
    ['Essentials', 'in', 'SYSTEM', 'transfers'],
    ['rest-api', 'in', 'BUSINESS', 'api'],
    ['DemocracyPost', 'out', 'SYSTEM', 'other'],
    ['TreasuryIngest', 'in', 'SYSTEM', 'other'],
  ] as const)('%s %s from %s → %s', (pluginKey, direction, counterpartyType, expected) => {
    expect(categorize(flow({ pluginKey, direction, counterpartyType }))).toBe(expected);
  });

  it('puts tax kinds before the plugin, and fines before taxes', () => {
    expect(categorize(flow({ pluginKey: 'ChestShop', taxKind: 'sales', direction: 'out' }))).toBe('taxes');
    expect(categorize(flow({ pluginKey: 'treasury', taxKind: 'personal', counterpartyType: 'GOVERNMENT' }))).toBe('taxes');
    expect(categorize(flow({ pluginKey: 'treasury', taxKind: 'fine', direction: 'out' }))).toBe('fines');
    expect(categorize(flow({ pluginKey: 'ChestShop', taxKind: '' }))).toBe('shop_sales');
  });

  it('classifies internal transfers first and excludes them from income and spending', () => {
    expect(categorize(flow({ pluginKey: 'treasury-salary', taxKind: 'income', internal: true }))).toBe('internal');
    expect(countsAsIncomeOrSpending('internal')).toBe(false);
    expect(countsAsIncomeOrSpending('transfers')).toBe(true);
  });

  it('a counterparty-limited rule without a counterparty falls back', () => {
    expect(categorize(flow({ pluginKey: 'Treasury-Core', counterpartyType: null }))).toBe('other');
  });

  it('maps unknown plugins to Other and logs each one once', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      expect(categorize(flow({ pluginKey: 'BrandNewPlugin' }))).toBe('other');
      expect(categorize(flow({ pluginKey: 'brandnewplugin', direction: 'out' }))).toBe('other');
      expect(categorize(flow({ pluginKey: 'toString' }))).toBe('other');
      expect(warn).toHaveBeenCalledTimes(2);
      expect(warn.mock.calls[0]![0]).toContain('brandnewplugin');
    } finally { warn.mockRestore(); }
  });

  it('covers every production plugin seen in the ledger without logging', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      // SELECT DISTINCT plugin_system FROM ledger_txns on production, 2026-10-26.
      for (const pluginKey of ['', 'BountyHunters', 'business', 'BusinessPlugin', 'ChestShop', 'CustomFishing', 'DeluxeMenus',
        'DemocracyPost', 'DemocracyRobbery', 'DiscordSRV', 'Eco', 'Essentials', 'Exams', 'GriefPrevention', 'GriefPreventionUI',
        'GriefPrevGUI', 'HomelandSecurity', 'MobsToEggs', 'nightcore', 'PostmanPat', 'realty', 'rest-api', 'treasury',
        'Treasury-Core', 'treasury-salary', 'TreasuryIngest']) {
        for (const direction of ['in', 'out'] as const) categorize(flow({ pluginKey, direction }));
      }
      expect(warn).not.toHaveBeenCalled();
    } finally { warn.mockRestore(); }
  });

  it('labels every category and keeps map keys lower case', () => {
    for (const category of CATEGORIES) expect(CATEGORY_LABELS[category]).toBeTruthy();
    for (const key of Object.keys(PLUGIN_CATEGORIES)) expect(key).toBe(key.toLowerCase());
  });
});
