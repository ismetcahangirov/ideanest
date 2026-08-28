import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';
import type { Money } from '../money';

/**
 * §4.11's AD-05: every charge, its provider reference, and why the refused ones were
 * refused — issue #304.
 *
 * <p><strong>A charge's "state history" is a list of rows and not a field on one.</strong>
 * §7.2 makes `transactions` append-only, so a call that was pending and later succeeded is
 * two rows sharing an idempotency key rather than one row that changed. Nothing in this
 * module updates anything, and there is no endpoint that could: V41 puts a trigger on the
 * table that raises on UPDATE and DELETE.
 */

/** §7.2's list. Only `CHARGE` is written today; the rest are in V41's check for later. */
export type TransactionType =
  | 'VERIFICATION'
  | 'CHARGE'
  | 'REFUND'
  | 'CHARGEBACK'
  | 'CHARGEBACK_REVERSAL'
  | 'PAYOUT';

/**
 * What the provider said, frozen at insert.
 *
 * `PENDING` means the provider accepted the instruction and had not decided. There is no
 * path out of it — a resolution is a new row — which is why {@link LoggedTransaction.attemptNumber}
 * and not the status is what tells one attempt from the next.
 */
export type TransactionStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED';

/** §9.3 requires at least two, so which one made a call is a fact about the row. */
export type ProviderName = 'PAYRIFF' | 'EPOINT' | 'AZERICARD';

/**
 * An amount of money, as `lib/money.ts` already defines it.
 *
 * <p>Re-exported rather than redeclared. §10.3 makes the amount a <strong>string</strong>,
 * never a JSON number — a JSON number is an IEEE 754 double and cannot hold a pledge exactly
 * — and a second interface saying the same thing is a second place for somebody to eventually
 * type `amount: number`. On this surface that is not a formality: a payment log is read next
 * to a provider's own statement, and a figure that has been through a double disagrees with
 * it by a qapik nobody can account for.
 */
export type { Money } from '../money';

/** One call to a provider, and what it said. */
export interface LoggedTransaction {
  id: string;
  /** Absent on a payout, which is about a campaign and a creator rather than a pledge. */
  pledgeId?: string | null;
  projectId: string;
  type: TransactionType;
  status: TransactionStatus;
  /** Always positive. Direction is a property of {@link type}, never a sign. */
  amount: Money;
  provider: ProviderName;
  /**
   * The provider's own reference — the identifier a support conversation and a dispute are
   * both conducted in.
   *
   * Absent when the call never got an answer. A request that timed out before the provider
   * replied is still a row, because it may have charged somebody.
   */
  providerTransactionId?: string | null;
  /** The provider's vocabulary for a refusal. Absent on anything that did not fail. */
  failureCode?: string | null;
  failureMessage?: string | null;
  /** Which of §9.6's four attempts this was, counted from one. */
  attemptNumber: number;
  /** ISO-8601 instant, UTC. */
  createdAt: string;
}

export interface PaymentLogPage {
  /** Echoed, and absent when the request did not ask for it. See {@link PaymentLogRequest}. */
  pledgeId?: string | null;
  projectId?: string | null;
  transactions: LoggedTransaction[];
  nextCursor?: string | null;
}

export const PAYMENT_PAGE_SIZE = 25;

/**
 * What the log may be narrowed by.
 *
 * <p>A pledge or a campaign, and nothing else — those are the two indexes V41 created. There
 * is deliberately no filter on status, provider or type: "every failed charge on the
 * platform" is a real question with no index behind it, and the screen shows the status of
 * every row it draws. The pledge filter is the question §9.6's retry schedule is actually
 * argued from.
 *
 * <p><strong>A request naming both is resolved to the pledge by the service</strong>, which
 * is the narrower question; the response echoes what it actually applied.
 */
export interface PaymentLogRequest {
  pledgeId?: string | null;
  projectId?: string | null;
  after?: string | null;
  limit?: number;
  signal?: AbortSignal;
}

export function paymentLogQuery(request: PaymentLogRequest): string {
  const params = new URLSearchParams();
  params.set('limit', String(request.limit ?? PAYMENT_PAGE_SIZE));
  if (request.pledgeId != null && request.pledgeId !== '') params.set('pledgeId', request.pledgeId);
  if (request.projectId != null && request.projectId !== '')
    params.set('projectId', request.projectId);
  if (request.after != null && request.after !== '') params.set('after', request.after);
  return params.toString();
}

/** One page of the log, newest first. */
export async function readPaymentLog(request: PaymentLogRequest = {}): Promise<PaymentLogPage> {
  const response = await authorizedFetch(`/v1/admin/payments?${paymentLogQuery(request)}`, {
    signal: request.signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as PaymentLogPage;
}

/*
 * The two label tables moved to `admin.screens.payments` with #324. They were English prose in
 * a module a client imports, and the values they were keyed by — the service's own type and
 * status — are what the catalogue is keyed by now.
 */

/**
 * The token a status is drawn in, and the word beside it carries the meaning.
 *
 * <p>docs/ui-kit.md §9.2: colour alone never carries meaning, so every one of these appears
 * with its label. <strong>`SUCCEEDED` is `success` and never lime.</strong> Lime means "act
 * now"; a collection that worked is the opposite of something to act on, and the one place
 * that distinction gets confused is a financial screen where green and lime both look like
 * approval at a glance.
 */
export function statusVariant(status: TransactionStatus): 'success' | 'danger' | 'warning' {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'FAILED') return 'danger';
  return 'warning';
}
