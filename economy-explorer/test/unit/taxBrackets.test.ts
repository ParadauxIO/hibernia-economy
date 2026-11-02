import { describe, it, expect } from 'vitest';
import { bracketDetails } from '@/lib/taxBrackets';

describe('collection-time bracket labels', () => {
  it('keeps exact bounds and non-integer percentage rates', () => {
    expect(bracketDetails('100000:200000:0.012')).toEqual({ floor: '$100,000', ceiling: '$200,000', percent: '1.2%' });
    expect(bracketDetails('200000:*:0.012')).toEqual({ floor: '$200,000', ceiling: null, percent: '1.2%' });
    expect(bracketDetails('0.12345:9007199254740993:0.00033')).toEqual({ floor: '$0.12345', ceiling: '$9,007,199,254,740,993', percent: '0.033%' });
  });
  it('does not infer a bracket for legacy or malformed data', () => {
    for (const key of ['', '0.01', '100000:200000', '100000:*:NaN']) expect(bracketDetails(key)).toBeNull();
  });
});
