import { localDate, money, needsAttention, paymentRows, toMinor } from './format';
import { charge, posting } from './testing';
import type { Charge, Posting } from './types';

describe('money', () => {
  it('uses each currency’s own decimals: none for yen, three for dinars', () => {
    expect(money(80000, 'USD')).toBe('USD 800.00');
    expect(money(1000, 'JPY')).toBe('JPY 1,000');
    expect(money(1500, 'KWD')).toBe('KWD 1.500');
  });

  it('parses an amount into exact minor units, never through floating point', () => {
    expect(toMinor('0.29', 'USD')).toBe(29); // 0.29 * 100 is 28.999999999999996 in floating point
    expect(toMinor('450', 'USD')).toBe(45000);
    expect(toMinor('1.5', 'KWD')).toBe(1500);
    expect(toMinor('1000', 'JPY')).toBe(1000);
  });

  it('refuses what is not an amount in that currency', () => {
    for (const bad of ['', '-5', '0', '1.234', '1e3', 'abc', '99999999999999999999']) expect(toMinor(bad, 'USD')).toBeNull();
    expect(toMinor('10.5', 'JPY')).toBeNull();
  });
});

describe('payment rows', () => {
  it('join each charge to its posting, newest first, and flag what needs a person', () => {
    const charges = [charge(), charge({ id: 'ch-2', createdAt: '2026-11-18T09:00:00Z', status: 'PENDING', pspRef: null })] as Charge[];
    const postings = [posting({ status: 'DEAD_LETTER', failureCategory: 'OPERA_UNAVAILABLE', operaRef: null })] as Posting[];

    const rows = paymentRows(charges, postings);

    expect(rows.map((r) => r.charge.id)).toEqual(['ch-2', 'ch-1']);
    expect(rows[1].posting?.status).toBe('DEAD_LETTER');
    expect(rows.every(needsAttention)).toBe(true);
    expect(needsAttention({ charge: charge() as Charge, posting: posting() as Posting })).toBe(false);
  });
});

describe('local date', () => {
  it('is the day on the operator’s clock, not UTC’s: at 01:30 in Dubai it is already the 20th', () => {
    // 01:30 on 20 November in Dubai is 21:30 on the 19th in UTC: the two calendars disagree, as they do every night after midnight there.
    const lateNightInDubai = { getFullYear: () => 2026, getMonth: () => 10, getDate: () => 20, toISOString: () => '2026-11-19T21:30:00.000Z' };
    expect(localDate(lateNightInDubai as unknown as Date)).toBe('2026-11-20');
  });
});
