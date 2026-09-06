import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';
import type { Money } from '../money';

/**
 * §4.11's AD-05 payout queue — issues #69 and #306.
 *
 * <h2>What the screen has to show, and why it is every figure</h2>
 *
 * This is where somebody signs off money leaving the platform. A single net figure with a
 * note saying "fees deducted" is not something anybody can check, so the service sends the
 * whole breakdown and the screen renders all of it: what came in, what the platform took,
 * what the processor took, what went back as refunds, and what is left.
 *
 * <h2>Dual approval is two rows and cannot be one person</h2>
 *
 * The service makes `(payout, approver)` a primary key, so "two different people" is a
 * constraint rather than a rule somebody has to remember. `stillNeeded` comes from the
 * service rather than being counted here, because the browser cannot see that two rows are
 * two different accounts.
 */

export type PayoutState =
  | 'CALCULATED'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'PAID'
  | 'FAILED'
  | 'CANCELLED';

/**
 * Where a payout's creator stands with identity verification — issue #431.
 *
 * Eight values and not a boolean, because a finance operator looking at a payout that will
 * not approve has to be able to tell a creator who has never been asked from one whose
 * documents are already in the review queue: those are different things to do next. V55's
 * argument, which #431 quotes — a payout that will not approve and does not say why "makes
 * the same campaign look as though nothing is owed".
 *
 * `WAIVED` is deliberately not folded into `VERIFIED`. A waiver is #436's override, granted
 * by a named person for a bounded time, and drawing it as though somebody had checked a
 * document would be the screen telling an operator something that is not true.
 */
export type VerificationStanding =
  | 'NOT_REQUIRED'
  | 'WAIVED'
  | 'VERIFIED'
  | 'NEVER_REQUESTED'
  | 'AWAITING_DOCUMENTS'
  | 'UNDER_REVIEW'
  | 'REJECTED'
  | 'EXPIRED';

export interface Payout {
  id: string;
  projectId: string;
  creatorId: string;
  gross: Money;
  platformFee: Money;
  processingFee: Money;
  taxWithheld: Money;
  refunded: Money;
  net: Money;
  /** Which terms produced the deductions, so the arithmetic can be traced back. */
  feeScheduleId?: string | null;
  state: PayoutState;
  payableAt: string;
  /**
   * Whether the hold has expired, computed against the server's clock.
   *
   * Sent rather than derived: a client comparing `payableAt` to its own clock would show a
   * payout as approvable a few seconds early, and the service would then refuse a button
   * that looked enabled.
   */
  payableNow: boolean;
  approvalsRequired: number;
  /** Where the creator stands with identity verification (#431). Read live by the service. */
  creatorStanding: VerificationStanding;
  /**
   * Whether that standing is what is holding this payout.
   *
   * Sent rather than derived from `creatorStanding`, for `payableNow`'s reason: which
   * standings release a payout is a rule, and a second copy of it in the browser is the copy
   * that disagrees the day the rule changes.
   */
  heldForVerification: boolean;
  payoutTransactionId?: string | null;
  failureCode?: string | null;
  failureMessage?: string | null;
  calculatedAt: string;
  sentAt?: string | null;
}

export interface PayoutApproval {
  approverId: string;
  approvedAt: string;
  note?: string | null;
}

export interface PayoutFile {
  payout: Payout;
  approvals: PayoutApproval[];
  /** How many more signatures it needs. From the service — see the module comment. */
  stillNeeded: number;
}

export interface PayoutPage {
  payouts: Payout[];
  page: number;
  hasMore: boolean;
}

/** The queue: everything still on its way, oldest first, holds included. */
export async function readPayoutQueue(page = 0, signal?: AbortSignal): Promise<PayoutPage> {
  const response = await authorizedFetch(`/v1/admin/payouts/queue?page=${page}`, { signal });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as PayoutPage;
}

/** Everything, newest first, optionally narrowed to one state. */
export async function listPayouts(
  state: PayoutState | null,
  page = 0,
  signal?: AbortSignal,
): Promise<PayoutPage> {
  const parameters = new URLSearchParams({ page: String(page) });
  if (state != null) parameters.set('state', state);

  const response = await authorizedFetch(`/v1/admin/payouts?${parameters}`, { signal });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as PayoutPage;
}

/** One payout with its signatures. */
export async function readPayout(payoutId: string, signal?: AbortSignal): Promise<PayoutFile> {
  const response = await authorizedFetch(`/v1/admin/payouts/${encodeURIComponent(payoutId)}`, {
    signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as PayoutFile;
}

/** Works out what a campaign owes, and starts the hold. */
export async function calculatePayout(projectId: string, signal?: AbortSignal): Promise<Payout> {
  const response = await authorizedFetch('/v1/admin/payouts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ projectId }),
    signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as Payout;
}

/** Signs off. Signing twice is a no-op, not a second signature. */
export async function approvePayout(
  payoutId: string,
  note: string | null,
  signal?: AbortSignal,
): Promise<PayoutFile> {
  const response = await authorizedFetch(
    `/v1/admin/payouts/${encodeURIComponent(payoutId)}/approvals`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ note }),
      signal,
    },
  );
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as PayoutFile;
}

/** Takes the caller's own signature back. There is no way to withdraw somebody else's. */
export async function withdrawApproval(
  payoutId: string,
  signal?: AbortSignal,
): Promise<PayoutFile> {
  const response = await authorizedFetch(
    `/v1/admin/payouts/${encodeURIComponent(payoutId)}/approvals`,
    { method: 'DELETE', signal },
  );
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as PayoutFile;
}

/**
 * Instructs the provider.
 *
 * The destination is typed per send rather than stored on the creator's account, because
 * §9's payout-destination schema is not built. That is a real gap and this is the honest
 * shape of it — the value is not persisted anywhere, and never appears in a log.
 */
export async function sendPayout(
  payoutId: string,
  destinationReference: string,
  signal?: AbortSignal,
): Promise<Payout> {
  const response = await authorizedFetch(`/v1/admin/payouts/${encodeURIComponent(payoutId)}/send`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ destinationReference }),
    signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as Payout;
}

/** Withdraws a payout before it is sent. */
export async function cancelPayout(payoutId: string, signal?: AbortSignal): Promise<Payout> {
  const response = await authorizedFetch(
    `/v1/admin/payouts/${encodeURIComponent(payoutId)}/cancel`,
    { method: 'POST', signal },
  );
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as Payout;
}
