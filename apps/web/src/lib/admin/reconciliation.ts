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

export interface ReconciliationFinding {
  kind: FindingKind;
  /** §21.2 refuses to add two currencies, so a finding is about exactly one. */
  currency: string;
  /** The sentence, with the figures in it. Prose to act on, not a code to look up. */
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

/** What a finding is called on the screen. One sentence per kind, in §7.2's own terms. */
export const FINDING_TITLES: Record<FindingKind, string> = {
  UNBALANCED: 'The ledger does not sum to zero',
  IMPOSSIBLE_SIGN: 'An account holds a balance it cannot hold',
  DISAGREES_WITH_PAYMENTS: 'The ledger and the payment records disagree',
};

/**
 * What each kind means for somebody who has to act on it.
 *
 * <p>Here rather than in the component because these three sentences are the whole
 * usefulness of the screen: a member of finance reading "UNBALANCED" learns nothing they
 * did not know from the word, and what they need is which of the platform's guarantees has
 * just been broken and therefore who to call.
 */
export const FINDING_MEANINGS: Record<FindingKind, string> = {
  UNBALANCED:
    'PostgreSQL refuses a posting whose debits and credits differ, so a row got past both ' +
    'the application and that trigger. Nothing else on this platform can produce it.',
  IMPOSSIBLE_SIGN:
    'Escrow below zero is money paid out that was never taken; a creator’s account above ' +
    'zero is a creator paid more than they earned. Neither is caught by the sum, because ' +
    'two errors of opposite sign balance perfectly.',
  DISAGREES_WITH_PAYMENTS:
    'What the ledger holds and what the transactions say moved are not the same figure. ' +
    'This is the only check that catches a posting the application never made at all.',
};
