import { ApiError } from '../api/problem';

/**
 * What a console screen does when the service says no — issues #294 and #304 to #314.
 *
 * <h2>Why this is shared and the moderation queue's copy is not</h2>
 *
 * Nine screens landed with this epic and every one of them meets the same four outcomes: it
 * worked, the browser has no session, the account is not staff, or something else went wrong.
 * The first two console screens each wrote that out by hand, which was right when there were
 * two and would be nine near-identical `messageFor` functions now — and the failure mode of
 * nine copies is that the eighth is the one somebody forgets to teach about a new refusal
 * code.
 *
 * <p>`components/moderation/ModerationQueue.tsx` and `components/admin/UserDirectory.tsx`
 * keep their own, deliberately. Both branch on codes that mean something only on those two
 * screens — `PROJECT_TRANSITION_NOT_ALLOWED` names a state machine, `ACCOUNT_SUSPENSION_REFUSED`
 * names a rule about suspending yourself — and folding those in here would put a sentence
 * about campaigns in front of a reader looking at the ledger.
 *
 * <h2>It branches on the code and never on prose</h2>
 *
 * §10.4 gives every refusal a `code` beside its status. Two 403s that cannot be told apart
 * would force this file to match on sentences the service is free to reword, and the day
 * somebody rewords one, the screen stops recognising a refusal it used to handle.
 */

/** The four things a console screen can be doing. */
export type ConsoleStatus = 'loading' | 'ready' | 'failed' | 'signed-out' | 'forbidden';

/**
 * Which of them a failure means.
 *
 * <p>`signed-out` and `forbidden` are separated because they need different words and lead to
 * different actions: one is fixed by signing in again and the other cannot be fixed by the
 * person reading it. Collapsing them into "you cannot see this" would send somebody whose
 * token merely expired looking for a moderator to add them to a list they are already on.
 */
export function statusFor(cause: unknown): ConsoleStatus {
  if (cause instanceof ApiError && cause.status === 401) return 'signed-out';
  if (cause instanceof ApiError && cause.status === 403) return 'forbidden';
  return 'failed';
}

/**
 * The capability a 403 named, when it named one — issue #295.
 *
 * <p>Before the role model there was one refusal, because there was one question: a caller
 * was staff or was not. A moderator opening the refund console got the same 403 as a
 * stranger, which reads as a broken console rather than as a screen that is not theirs.
 *
 * <p>The service now answers `INSUFFICIENT_STAFF_CAPABILITY` with the capability in `meta`,
 * and a screen that can name it can tell somebody what to go and ask for. Null for the
 * refusals that are not about a capability — a signed-out session, a stranger — and for a
 * service that has not been redeployed since #295.
 */
export function requiredCapabilityFrom(cause: unknown): string | null {
  if (!(cause instanceof ApiError) || cause.problem?.code !== 'INSUFFICIENT_STAFF_CAPABILITY') {
    return null;
  }

  const capability = cause.problem?.meta?.capability;
  return typeof capability === 'string' ? capability : null;
}

/**
 * Turns a refusal into something a member of staff can act on.
 *
 * @param subject what the reader was trying to read, in the words the screen uses for it —
 *     "the audit trail", "the ledger". It is interpolated into the 403, which is the one
 *     message a reader meets without having done anything wrong
 */
export function consoleMessageFor(cause: unknown, subject: string): string {
  if (cause instanceof ApiError) {
    if (cause.status === 403) {
      const capability = requiredCapabilityFrom(cause);

      // Two different 403s since #295, and they lead somewhere different: one is fixed by
      // asking an administrator for a role, and the other cannot be fixed by the person
      // reading it. Collapsing them would send a colleague looking for a bug.
      return capability === null
        ? `Your account does not work on this platform, so ${subject} is not yours to read.`
        : `Reading ${subject} needs ${capability}, which your roles do not include.`;
    }

    const code = cause.problem?.code;

    if (code === 'UNKNOWN_LEDGER_ACCOUNT') {
      // Deliberately the service's own message: it names the value that was sent and points
      // at the six accounts §7.2 allows, which is more than this file knows.
      return cause.problem?.detail ?? 'That is not one of the platform ledger accounts.';
    }
    if (code === 'COLLECTION_NOT_FOUND') {
      return 'That collection no longer exists. It may have been renamed since this page was loaded.';
    }
    if (code === 'COLLECTION_SLUG_TAKEN') {
      return 'A collection already uses that handle. Handles are permanent, so pick another.';
    }
    if (code === 'CURATION_REJECTED') {
      return cause.problem?.detail ?? 'The service refused that change to the collection.';
    }

    return (
      cause.problem?.detail ?? cause.problem?.title ?? 'The service refused the request. Try again.'
    );
  }
  return 'The service could not be reached. Check your connection and try again.';
}

/**
 * Whether a rejection is a cancelled request rather than a failure.
 *
 * Every console screen aborts its in-flight read when a filter changes, and an abort that
 * reached the error path would paint "something went wrong" over a screen that is loading
 * correctly.
 */
export function wasAborted(cause: unknown): boolean {
  return cause instanceof DOMException && cause.name === 'AbortError';
}

/**
 * Enough of an identifier to tell two rows apart, said aloud without pain.
 *
 * <p>The console's lists carry identifiers and frequently nothing else — a ledger posting has
 * a transaction id and no name, an audit row names an entity nothing turns into a title.
 * Eight characters is what git settled on for the same problem, and
 * `lib/moderation/describe.ts` already uses it on the queue, so the two screens agree about
 * how long a shortened identifier is.
 */
export function shortId(id: string): string {
  return id.slice(0, 8);
}
