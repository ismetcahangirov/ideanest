import Decimal from 'decimal.js';

/**
 * Money on the client.
 *
 * Floating point is forbidden for money (CLAUDE.md §3): `0.1 + 0.2 !== 0.3`,
 * and on a funding platform that is somebody's pledge. Every amount that comes
 * near arithmetic or a comparison is a `Decimal`, and it crosses the API as a
 * string — never as a JSON number, which is an IEEE 754 double and cannot hold
 * `599.00` exactly (docs/architecture.md §10.3).
 *
 * `parseFloat` and `Number()` are absent from this module on purpose. Both
 * silently accept `'1e5'`, `'0x10'`, `'12abc'`, and `Infinity`, and both lose
 * precision on the way in — so a string that has already been through them can
 * never be trusted again.
 *
 * No global `Decimal.set()` here. This module only parses, compares, and
 * formats to a fixed scale, none of which needs a precision or rounding mode;
 * configuring the constructor globally from a leaf module would change the
 * behaviour of every other consumer that imports it.
 */

/** Money as the API carries it. The amount is a string, always. */
export interface Money {
  /** Fixed-scale decimal string, e.g. `"5000.00"`. */
  amount: string;
  /** ISO 4217 code. */
  currency: string;
}

/**
 * The currencies a creator may choose from.
 *
 * One entry, because phase 1 collects in AZN only (docs/architecture.md §21.2).
 * It is still a list and still a `<select>`: the project currency is a real
 * choice that is immutable after launch, and phase 2 adds USD, EUR, TRY, and
 * RUB. Offering a currency the platform cannot yet collect in would be a
 * promise the payment side does not keep.
 */
export const SUPPORTED_CURRENCIES = ['AZN'] as const;

export type Currency = (typeof SUPPORTED_CURRENCIES)[number];

export const DEFAULT_CURRENCY: Currency = 'AZN';

export function isSupportedCurrency(value: string): value is Currency {
  return (SUPPORTED_CURRENCIES as readonly string[]).includes(value);
}

/** Digits after the point. `numeric(14,2)` on the server side. */
export const MONEY_SCALE = 2;

/** Digits before the point, from the same `numeric(14,2)`. */
export const MONEY_MAX_INTEGER_DIGITS = 14 - MONEY_SCALE;

/**
 * Exactly what this accepts: digits, optionally one full stop and one or two
 * more digits. Spaces are stripped first, because a creator typing a large
 * figure often groups it.
 *
 * A comma is NOT accepted, and that is deliberate rather than lazy. The
 * interface ships in Azerbaijani and English (§21.1), and `1,50` is one and a
 * half in one of them and a malformed thousand in the other. Guessing which
 * would mean occasionally reading a goal as a hundredth of what was meant, so
 * the field refuses it and says to use a full stop.
 */
const AMOUNT = /^\d+(\.\d{1,2})?$/;

export type AmountRejection =
  | 'empty'
  | 'not-a-number'
  | 'comma'
  | 'too-many-decimals'
  | 'too-large'
  | 'not-positive';

export type AmountParse =
  | { readonly ok: true; readonly value: Decimal }
  | { readonly ok: false; readonly reason: AmountRejection };

export interface AmountOptions {
  /**
   * Whether zero is an amount rather than a mistake.
   *
   * Off by default, because the two amounts that existed before this option —
   * a funding goal and a reward price — are both meaningless at zero.
   *
   * On for a shipping rate. "Free to Azerbaijan" is an offer a creator makes on
   * purpose, and the server stores it as `0.00` rather than as an absent rule
   * (see `ShippingRule.additionalItemAmount`). Refusing it here would leave the
   * creator no way to say the thing the data model is built to express.
   */
  readonly allowZero?: boolean;
}

/**
 * Reads a typed amount without ever converting it to a number.
 *
 * The result carries a reason rather than a message: the wording belongs to the
 * field that renders it, and the same rejection reads differently on a goal
 * than it does on a reward price or a shipping rate.
 */
export function parseAmount(input: string, options: AmountOptions = {}): AmountParse {
  // Ordinary spaces, non-breaking spaces, and the narrow no-break space that
  // `Intl.NumberFormat` itself emits as a group separator.
  const cleaned = input.replace(/[\s  ]/g, '');

  if (cleaned === '') return { ok: false, reason: 'empty' };
  if (cleaned.includes(',')) return { ok: false, reason: 'comma' };

  if (!AMOUNT.test(cleaned)) {
    // A well-formed number with too much scale is a different mistake from
    // gibberish, and the creator can fix it without retyping the figure.
    const tooPrecise = /^\d+\.\d{3,}$/.test(cleaned);
    return { ok: false, reason: tooPrecise ? 'too-many-decimals' : 'not-a-number' };
  }

  const [whole = ''] = cleaned.split('.');
  if (whole.replace(/^0+(?=\d)/, '').length > MONEY_MAX_INTEGER_DIGITS) {
    return { ok: false, reason: 'too-large' };
  }

  const value = new Decimal(cleaned);
  // A negative cannot reach this line — the pattern above carries no sign — so
  // with `allowZero` there is nothing left for this check to refuse.
  if (options.allowZero !== true && value.lessThanOrEqualTo(0)) {
    return { ok: false, reason: 'not-positive' };
  }

  return { ok: true, value };
}

/**
 * The string the API receives.
 *
 * Always two decimal places, so `5000` and `5000.00` are the same request and
 * the server never sees a scale it has to guess at.
 */
export function toWireAmount(value: Decimal): string {
  return value.toFixed(MONEY_SCALE);
}

/** A `Money` body from a parsed amount. */
export function toMoney(value: Decimal, currency: string): Money {
  return { amount: toWireAmount(value), currency };
}

/**
 * The amount as an editable string.
 *
 * The wire value is returned verbatim rather than reformatted: it is already a
 * decimal string of the right scale, and putting it through a formatter on the
 * way into a text field is how a trailing zero or a group separator ends up in
 * the next request body.
 */
export function amountFieldValue(money: Money | null | undefined): string {
  return money?.amount ?? '';
}

/**
 * An amount as a reader sees it: grouped, at the scale the column holds, with
 * its currency after it.
 *
 * FORMATTED FROM THE DIGITS, NOT FROM A NUMBER. `Intl.NumberFormat` takes a
 * `number`, and putting `999999999999.99` through one loses the last digit
 * before any formatting happens — which is the entire reason this module
 * refuses to parse with `Number()` in the first place. So the grouping is done
 * on the integer digits as a string and the fraction is copied across
 * untouched.
 *
 * Grouping in threes with a comma, and the code after the amount rather than a
 * symbol before it. There is no agreed symbol for the manat in either of the
 * two languages the product ships in (docs/architecture.md §21.1), and
 * `Intl`'s own answer differs by locale — so the ISO code, which is the same
 * string the API sent, is what is shown. Consumers that render this into a
 * table cell get a pre-formatted string, which is what docs/ui-kit.md §7.15
 * asks for: a table that formats is a table that rounds.
 */
export function formatMoney(money: Money | null | undefined): string {
  if (money == null) return '';

  const [whole = '', fraction] = money.amount.split('.');
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const scaled = (fraction ?? '').padEnd(MONEY_SCALE, '0').slice(0, MONEY_SCALE);

  return `${grouped}.${scaled} ${money.currency}`.trim();
}
