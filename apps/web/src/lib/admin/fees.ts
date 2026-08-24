import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';

/**
 * §4.11's AD-11: what the platform charges — §9, issue #311.
 *
 * <h2>There is no edit, only a replacement</h2>
 *
 * A rate is a term, not a setting. Editing one in place would silently rewrite what every
 * past payout should have been, and §22.1 asks that question with a seven-year retention
 * rule attached — so a change closes the schedule in force and opens a new one beginning
 * now. The screen says so, because it looks like a create where an operator expects an
 * update.
 *
 * <h2>The rates are strings and are fractions</h2>
 *
 * `"0.05000"` is five percent. Two decisions, both about the same failure: a JSON number is
 * an IEEE 754 double in every mainstream parser, and a percentage would be divided by a
 * hundred somewhere — the call site that forgets charges a fee a hundred times too large.
 * The wire carries the number that gets multiplied, as text.
 */

export type FeeScope = 'PLATFORM' | 'CATEGORY' | 'PROJECT';

export interface FeeSchedule {
  id: string;
  scope: FeeScope;
  /** Which category or which campaign. Absent exactly when the scope is `PLATFORM`. */
  scopeRef?: string | null;
  /** A fraction, as text. `"0.05000"` is five percent. */
  platformRate: string;
  processingRate: string;
  /** The processor's fixed amount per transaction, as text. */
  processingFixed: string;
  currency: string;
  effectiveFrom: string;
  /** Absent while this schedule is the one in force. */
  effectiveTo?: string | null;
  /** Derivable from `effectiveTo` and sent anyway — the screen sorts and badges on it. */
  open: boolean;
  note: string;
  createdAt: string;
  createdBy: string;
}

export interface FeeHistory {
  schedules: FeeSchedule[];
}

/** Every window ever written, newest first — closed ones included. */
export async function readFeeHistory(signal?: AbortSignal): Promise<FeeHistory> {
  const response = await authorizedFetch('/v1/admin/fees', { signal });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as FeeHistory;
}

export interface ReplaceFeeRequest {
  readonly scope: FeeScope;
  readonly scopeRef: string | null;
  readonly platformRate: string;
  readonly processingRate: string;
  readonly processingFixed: string;
  readonly currency: string;
  readonly note: string;
  readonly signal?: AbortSignal;
}

/** Closes what is in force for this scope and opens the terms in the body. */
export async function replaceFeeSchedule(request: ReplaceFeeRequest): Promise<FeeSchedule> {
  const response = await authorizedFetch('/v1/admin/fees', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      scope: request.scope,
      scopeRef: request.scopeRef,
      platformRate: request.platformRate,
      processingRate: request.processingRate,
      processingFixed: request.processingFixed,
      currency: request.currency,
      note: request.note,
    }),
    signal: request.signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as FeeSchedule;
}

/**
 * A fraction rendered as a percentage, for reading.
 *
 * <strong>Display only.</strong> Nothing on this screen multiplies a rate by money — that
 * is the service's, on `BigDecimal` — so this is allowed to be a float. The moment
 * something here computes a fee, it uses `decimal.js` instead, which is what CLAUDE.md
 * requires of the frontend.
 */
export function asPercentage(rate: string): string {
  const parsed = Number(rate);
  if (!Number.isFinite(parsed)) return rate;

  // Three decimal places, because the service stores five and a rate of 0.00125 is a real
  // eighth of a percent somebody may have negotiated. Trailing zeroes are trimmed so the
  // ordinary five percent reads as "5%" rather than "5.000%".
  return `${Number((parsed * 100).toFixed(3))}%`;
}
