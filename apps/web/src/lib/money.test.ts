import Decimal from 'decimal.js';
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_CURRENCY,
  isSupportedCurrency,
  parseAmount,
  toMoney,
  toWireAmount,
} from './money';

/**
 * Money arithmetic is not optional to test (CLAUDE.md §3): it fails silently
 * and expensively. These cover the two ways it fails — a value that was never
 * a valid amount getting through, and a valid amount losing precision on the
 * way to the API.
 */

function amount(input: string): Decimal {
  const parsed = parseAmount(input);
  if (!parsed.ok) throw new Error(`Expected ${input} to parse, got ${parsed.reason}.`);
  return parsed.value;
}

describe('parseAmount', () => {
  it('reads a plain figure, with or without a scale', () => {
    expect(amount('5000').toFixed(2)).toBe('5000.00');
    expect(amount('5000.5').toFixed(2)).toBe('5000.50');
    expect(amount('5000.50').toFixed(2)).toBe('5000.50');
  });

  it('ignores the spaces somebody types while grouping a large figure', () => {
    expect(amount('120 000.00').toFixed(2)).toBe('120000.00');
    // The narrow no-break space Intl.NumberFormat itself emits.
    expect(amount('120 000').toFixed(2)).toBe('120000.00');
  });

  /*
   * The interface ships in Azerbaijani and English, where "1,50" is one and a
   * half in one language and a malformed thousand in the other. Guessing would
   * occasionally read a goal as a hundredth of what was meant.
   */
  it('refuses a comma rather than guessing what it separates', () => {
    expect(parseAmount('5,000')).toEqual({ ok: false, reason: 'comma' });
    expect(parseAmount('1,50')).toEqual({ ok: false, reason: 'comma' });
  });

  // Everything parseFloat and Number would have accepted, quietly.
  it.each([
    ['1e5', 'not-a-number'],
    ['0x10', 'not-a-number'],
    ['12abc', 'not-a-number'],
    ['-500', 'not-a-number'],
    ['Infinity', 'not-a-number'],
    ['5.005', 'too-many-decimals'],
    ['', 'empty'],
    ['   ', 'empty'],
    ['0', 'not-positive'],
    ['0.00', 'not-positive'],
  ])('refuses %s', (input, reason) => {
    expect(parseAmount(input)).toEqual({ ok: false, reason });
  });

  it('refuses more integer digits than numeric(14,2) can hold', () => {
    expect(amount('999999999999.99').toFixed(2)).toBe('999999999999.99');
    expect(parseAmount('1000000000000')).toEqual({ ok: false, reason: 'too-large' });
    // Leading zeros are padding, not magnitude.
    expect(amount('000000000000500').toFixed(2)).toBe('500.00');
  });
});

describe('the wire value', () => {
  it('is always at the scale the column holds', () => {
    expect(toWireAmount(amount('5000'))).toBe('5000.00');
    expect(toWireAmount(amount('5000.5'))).toBe('5000.50');
  });

  it('keeps every digit of a large goal', () => {
    // A double loses this one: 999999999999.99 is not representable exactly.
    expect(toWireAmount(amount('999999999999.99'))).toBe('999999999999.99');
  });

  /*
   * The reason this module exists. 0.1 + 0.2 is 0.30000000000000004 in IEEE
   * 754, and on a funding platform that difference is somebody's pledge.
   */
  it('adds without the floating-point error', () => {
    expect(toWireAmount(amount('0.1').plus(amount('0.2')))).toBe('0.30');
    expect(0.1 + 0.2).not.toBe(0.3);
  });

  it('builds a Money body with the amount as a string', () => {
    const money = toMoney(amount('5000'), 'AZN');
    expect(money).toEqual({ amount: '5000.00', currency: 'AZN' });
    expect(typeof money.amount).toBe('string');
  });
});

describe('currency', () => {
  it('accepts only what the platform can collect in', () => {
    // Phase 1 is AZN (docs/architecture.md §21.2).
    expect(isSupportedCurrency(DEFAULT_CURRENCY)).toBe(true);
    expect(isSupportedCurrency('USD')).toBe(false);
    expect(isSupportedCurrency('')).toBe(false);
  });
});
