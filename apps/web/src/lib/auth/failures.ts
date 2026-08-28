import { ApiError } from '../api/problem';
import type { AuthFailuresCopy } from '../i18n/auth-copy';
import { fillPlaceholders } from '../i18n/placeholders';

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
 *
 * <h2>THE FALLBACKS ARRIVE AS AN ARGUMENT — issue #324</h2>
 *
 * Every sentence below that is not the service's own has to exist in §21.1's four languages,
 * and this is a pure function reached from eight client components: it cannot look a message
 * up, because looking one up in the browser means the `use-intl` runtime and the catalogue in
 * every bundle that touches a form. So the words are resolved on the server by the page and
 * handed down — `lib/i18n/auth-copy.ts` carries the measurement that decided it.
 *
 * The argument is **required rather than optional**. An optional one would default to English
 * and the defect would be invisible in review: a screen that answers a Turkish reader in
 * English at the exact moment they are locked out of their account.
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

/**
 * Seconds until a rate-limited caller may try again, as a sentence in the reader's language.
 *
 * Three keys rather than one ICU plural, because the three are not three plural forms of one
 * sentence: "in under a minute" is a different claim from "in about a minute", and the
 * languages that decline the noun decline it inside `waitMinutes` where the number is.
 */
function waitFor(seconds: number, copy: AuthFailuresCopy): string {
  if (seconds <= 60) return copy.waitUnderMinute;

  const minutes = Math.ceil(seconds / 60);
  return minutes === 1
    ? copy.waitOneMinute
    : fillPlaceholders(copy.waitMinutes, { minutes: String(minutes) });
}

export function describeAuthFailure(cause: unknown, copy: AuthFailuresCopy): AuthFailure {
  if (!(cause instanceof ApiError)) {
    /*
     * Not a refusal — a bug in this application, or a body that was not the JSON it claimed
     * to be. It is not the reader's to fix and it must not be presented as though their
     * details were wrong.
     */
    return {
      title: copy.unexpectedTitle,
      detail: copy.unexpectedDetail,
      retryable: true,
    };
  }

  const problem = cause.problem;

  if (problem === null) {
    return {
      title: copy.unreachableTitle,
      detail: copy.unreachableDetail,
      retryable: true,
    };
  }

  if (problem.code === 'ACCOUNT_SUSPENDED') {
    return {
      title: problem.title ?? copy.suspendedTitle,
      detail: problem.detail ?? copy.suspendedDetail,
      retryable: false,
    };
  }

  if (cause.status === 429) {
    const seconds = problem.retryAfterSeconds;
    return {
      title: problem.title ?? copy.rateLimitedTitle,
      detail:
        seconds === undefined
          ? (problem.detail ?? copy.rateLimitedDetail)
          : fillPlaceholders(copy.retryAfter, {
              detail: problem.detail ?? copy.rateLimitedShort,
              wait: waitFor(seconds, copy),
            }),
      // See `retryable`: the window expires, so the control stays.
      retryable: true,
    };
  }

  return {
    title: problem.title ?? copy.refusedTitle,
    detail: problem.detail ?? copy.refusedDetail,
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
