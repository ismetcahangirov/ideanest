import { ApiError } from '../api/problem';

/**
 * What an authentication refusal means, and what the reader is told about it.
 *
 * <h2>The service's own words, and a `code` to branch on</h2>
 *
 * §10.4 gives every refusal a `title`, a `detail` and a `code`. **Branching happens on
 * `code` and never on `detail`**, which is the rule `lib/discovery/api.ts` states as well:
 * `detail` is prose written for a person, and it may be reworded or translated at any time.
 * What is shown is that prose, because the endpoint knows why it refused and this module does
 * not — "That device name is too long" is actionable, "something went wrong" is not.
 *
 * <h2>Three refusals need more than their sentence</h2>
 *
 *   - **`ACCOUNT_SUSPENDED` (403).** `AuthExceptionHandler` is explicit that it is 403 rather
 *     than 401 precisely so a client stops offering to sign in again — the credentials were
 *     correct. So this is the one refusal that suppresses the retry.
 *   - **The rate limit (429).** §17.3 allows five attempts per fifteen minutes, and the
 *     refusal carries how long is left. Saying "try again in 12 minutes" is the difference
 *     between waiting and hammering the form until the window resets — and the control stays,
 *     because after the wait the same request works.
 *   - **A refusal with no body at all.** The request never reached the service. Saying so is
 *     more useful than inventing a reason, and it points at the reader's connection rather
 *     than at their password.
 */

export interface AuthFailure {
  /** The heading. Short, and never a status code. */
  readonly title: string;
  /** The sentence under it — the service's own where there is one. */
  readonly detail: string;
  /**
   * Whether pressing the control again could ever work.
   *
   * **False for exactly one refusal: a suspension.** The credentials were correct and no
   * number of retries will change the answer, so the form withdraws the submit control rather
   * than leaving somebody pressing a button that cannot succeed.
   *
   * TRUE FOR THE RATE LIMIT, which is the distinction worth being careful about. A 429 is a
   * refusal that expires: the reader waits out the window and the same request works. Hiding
   * the button would leave them on a screen with no way forward except a reload, and the wait
   * is communicated in `detail`, where it belongs.
   */
  readonly retryable: boolean;
}

/** Seconds until a rate-limited caller may try again, as a sentence. */
function waitFor(seconds: number): string {
  if (seconds <= 60) return 'in under a minute';
  const minutes = Math.ceil(seconds / 60);
  return minutes === 1 ? 'in about a minute' : `in about ${minutes} minutes`;
}

export function describeAuthFailure(cause: unknown): AuthFailure {
  if (!(cause instanceof ApiError)) {
    /*
     * Not a refusal — a bug in this application, or a body that was not the JSON it claimed
     * to be. It is not the reader's to fix and it must not be presented as though their
     * details were wrong.
     */
    return {
      title: 'Something went wrong',
      detail: 'The request could not be completed. Try again in a moment.',
      retryable: true,
    };
  }

  const problem = cause.problem;

  if (problem === null) {
    return {
      title: 'The service could not be reached',
      detail: 'Check your connection and try again. Nothing was submitted.',
      retryable: true,
    };
  }

  if (problem.code === 'ACCOUNT_SUSPENDED') {
    return {
      title: problem.title ?? 'Account suspended',
      detail: problem.detail ?? 'This account has been suspended. Contact support to appeal.',
      retryable: false,
    };
  }

  if (cause.status === 429) {
    const seconds = problem.retryAfterSeconds;
    return {
      title: problem.title ?? 'Too many attempts',
      detail:
        seconds === undefined
          ? (problem.detail ?? 'Too many attempts. Wait a few minutes and try again.')
          : `${problem.detail ?? 'Too many attempts.'} You can try again ${waitFor(seconds)}.`,
      // See `retryable`: the window expires, so the control stays.
      retryable: true,
    };
  }

  return {
    title: problem.title ?? 'That did not work',
    detail: problem.detail ?? 'The service refused this request and did not say why.',
    retryable: true,
  };
}

/**
 * Field-level messages from a validation refusal, keyed by field name.
 *
 * §10.4's `errors` map. A message beside the field it is about is the difference between
 * "A name is required" being an instruction and being a riddle — and `Field` already wires
 * one to its control's `aria-describedby`, so the announcement follows for free.
 *
 * An empty map for anything that is not a validation failure, so a caller can render it
 * unconditionally.
 */
export function fieldErrorsOf(cause: unknown): Readonly<Record<string, string>> {
  if (!(cause instanceof ApiError)) return {};
  return cause.problem?.errors ?? {};
}
