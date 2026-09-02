import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';

/**
 * §4.11's AD-05, the half nobody could see — issue #106.
 *
 * <h2>Why "financial operations tooling" was still open with AD-05 built</h2>
 *
 * #70 built the nightly reconciliation and #138 gave it a gauge, and between them the
 * answer to "do the platform's books balance?" was reachable in exactly two places: a log
 * line at 02:30 and a Prometheus scrape. Neither is where the person who has to act on it
 * works. The console had a payment log, a ledger, a payout queue, refunds and chargebacks —
 * every financial operation except the one that checks whether the sum of them is right.
 *
 * <h2>Two calls, and the second is not a convenience</h2>
 *
 * The service holds the last report **in the process that made it**, deliberately: it is
 * regenerated every night, and a table would be a schema, a migration and a retention rule
 * for a value with a lifetime of one day. The honest consequence is that a console request
 * lands on one replica, so a fleet redeployed this morning answers "never run" until
 * tonight. {@link runReconciliation} is the way out of that — two aggregate queries that
 * write nothing — rather than a button that exists because buttons are nice.
 */

/** Which of the three questions was answered wrongly. What the screen groups on. */
export type FindingKind = 'UNBALANCED' | 'IMPOSSIBLE_SIGN' | 'DISAGREES_WITH_PAYMENTS';

/**
 * Which of the four checks produced a finding — issue #403.
 *
 * <p>Finer than {@link FindingKind}, and it has to be: two of these are `IMPOSSIBLE_SIGN` and
 * say opposite things, so a screen given only the kind is given the severity and not the
 * meaning. The catalogue keys its sentences on this.
 */
export type FindingCode =
  | 'LEDGER_NOT_ZERO'
  | 'CREATOR_OVERPAID'
  | 'PLATFORM_ACCOUNT_NEGATIVE'
  | 'DISAGREES_WITH_PAYMENTS';

export interface ReconciliationFinding {
  kind: FindingKind;
  code: FindingCode;
  /** The ledger account, or absent for a finding about a whole currency rather than one position. */
  account?: string | null;
  /** §21.2 refuses to add two currencies, so a finding is about exactly one. */
  currency: string;
  /** The figure the sentence turns on, as a string, because it is money. */
  amount: string;
  /** The second figure, for the finding that compares two. Absent everywhere else. */
  otherAmount?: string | null;
  /**
   * The service's own sentence, in English, with the figures in it.
   *
   * <p><strong>Not what the screen renders, since #403.</strong> It is the log line: English
   * prose carrying a raw account identifier and an unformatted amount, and it was the only
   * text on the reconciliation screen an Azerbaijani reader could not read — on the rows
   * carrying the actual result. The console builds its own sentence from the parts above.
   */
  detail: string;
}

export interface ReconciliationReport {
  /**
   * Whether a pass has ever happened on the replica that answered.
   *
   * **The field that stops this screen lying.** A reconciliation that silently stopped
   * running produces `balanced: true, findings: []` — which is also what a healthy
   * platform produces — and without this the two are one response.
   */
  hasRun: boolean;
  /** Null when `hasRun` is false. */
  runAt: string | null;
  /** Account-and-currency positions read. Zero on a platform that has taken no money. */
  accountsChecked: number;
  balanced: boolean;
  findings: ReconciliationFinding[];
}

/** The last pass this replica made. */
export async function readReconciliation(signal?: AbortSignal): Promise<ReconciliationReport> {
  const response = await authorizedFetch('/v1/admin/reconciliation', { signal });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as ReconciliationReport;
}

/**
 * Runs one now, and answers what it found.
 *
 * <p>No `Idempotency-Key`, unlike the writes on the neighbouring screens. §10.3 asks for one
 * on a payment mutation, and this mutates nothing: `LedgerReconciliation` reports and never
 * repairs, because the correcting entry depends on which of a dozen things went wrong and a
 * job that guessed would turn a detectable problem into an undetectable one. Running the
 * same pass twice costs two queries and produces the same answer.
 */
export async function runReconciliation(signal?: AbortSignal): Promise<ReconciliationReport> {
  const response = await authorizedFetch('/v1/admin/reconciliation/runs', {
    method: 'POST',
    signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as ReconciliationReport;
}

/*
 * The two finding tables moved to `admin.screens.reconciliation` with #324 — they are the
 * whole usefulness of the screen and they are prose, so they belong where a translator can
 * reach them. Both are keyed by {@link FindingKind}, which is the service's own vocabulary and
 * stays here.
 */
