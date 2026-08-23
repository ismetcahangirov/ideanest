import { setAccessToken } from '../api/access-token';
import { authorizedFetch } from '../api/client';
import { ApiError, errorFrom, type Problem } from '../api/problem';
import { postToAuth } from './post';

/**
 * §4.1's A-12 and A-13 — changing the address that signs in, and changing the password.
 * Issue #277.
 *
 * <h2>Three calls, two transports, and the split is the service's</h2>
 *
 * `CredentialController` publishes five paths and states why they are not two: the
 * authenticated ones require a bearer token *and* the current password, and the
 * unauthenticated ones are authorised entirely by a token that arrived in a mailbox. "One
 * handler with two authentication models and a branch deciding which applies is where a
 * bypass hides" is its sentence, and the same argument applies to a client module that
 * reached for one helper and passed a flag.
 *
 * So `changePassword` and `requestEmailChange` go through `authorizedFetch`, and
 * `confirmEmailChange` goes through `postToAuth` — there is no token to send, exactly as
 * there is none for `verifyEmail`. `lib/auth/twoFactor.ts` is arranged the same way for the
 * same reason.
 *
 * <h2>Why the current password is asked for at all</h2>
 *
 * The caller is already authenticated, so the password is a **second** check rather than the
 * first. `AccountCredentialsService` puts it plainly: fifteen minutes of somebody else's
 * access token must not be enough to move the address that resets the password, because that
 * is the whole path an account takeover takes. A screen that treated the password box as
 * friction and pre-filled or skipped it would be removing the only control that stands
 * between a stolen token and an account.
 *
 * <h2>A password change ends this browser's session, and this module says so first</h2>
 *
 * `POST /v1/auth/change-password` revokes every session *including the caller's* — the
 * service is explicit that "every session except one" is a rule the client would then have to
 * be trusted to have picked correctly. The consequence is that the next authorised request
 * after a 204 will 401, so {@link changePassword} drops the in-memory access token itself
 * rather than leaving the application to discover the fact.
 *
 * <h2>{@link refusalOf} is here, and #271's screens import it from here</h2>
 *
 * It belongs beside `describeAuthFailure` in `lib/auth/failures.ts`, which is the module that
 * already answers "what does this refusal mean" — and it is not there because #271 and #277
 * were scoped to two new files rather than to editing that one. What decided *which* of the
 * two new files is that this module needs all four reasons and the reset needs two of them:
 * a copy in each would be two places the `type`-suffix fallback below can be forgotten, and
 * forgetting it fails quietly, by printing the general message where the specific one belongs.
 * Folding it into `failures.ts` is a small follow-up and changes no behaviour.
 */

/* -------------------------------------------------------------------------
 * Reading which refusal this is
 * ---------------------------------------------------------------------- */

/**
 * The refusals §4.1's credential endpoints distinguish, as a client sees them.
 *
 * BRANCHING HAPPENS ON THIS AND NEVER ON `detail`, which is `lib/auth/failures.ts`'s rule and
 * §10.4's: `detail` is prose written for a person and may be reworded or translated at any
 * time. What a screen *shows* is still that prose, because the endpoint knows why it refused
 * and this module does not — the branch decides only which field the sentence is attached to.
 */
export type CredentialRefusal =
  /** 403. The current password was not the account's. Never says which check failed. */
  | 'incorrect-password'
  /** 400. The new password does not satisfy the policy, whose own words are in `detail`. */
  | 'weak-password'
  /** 409. The address asked for already has an account. */
  | 'email-already-in-use'
  /** 400. A link that is not one, has expired, or has already been spent. */
  | 'invalid-verification-link'
  /** Anything else, including a transport failure — the caller shows the general message. */
  | 'other';

/** Everything {@link CredentialRefusal} names except the fallback, which nothing matches. */
type NamedRefusal = Exclude<CredentialRefusal, 'other'>;

const KNOWN: ReadonlySet<string> = new Set<NamedRefusal>([
  'incorrect-password',
  'weak-password',
  'email-already-in-use',
  'invalid-verification-link',
]);

function isNamed(slug: string): slug is NamedRefusal {
  return KNOWN.has(slug);
}

/**
 * The stable reason for a refusal, from `code` where there is one and from `type` where there
 * is not.
 *
 * **BOTH ARE READ, AND THAT IS NOT DEFENSIVENESS.** §10.4 gives every problem a `code`, and
 * `AuthExceptionHandler` sets one on exactly one of its refusals — `ACCOUNT_SUSPENDED` — while
 * the four this module cares about carry their identity only in `type`, as
 * `https://ideanest.az/problems/incorrect-password` and its three siblings. A client that read
 * `code` alone would see `undefined` for every one of them and print the general message where
 * the specific one belongs; a client that read `type` alone would stop working the day a
 * `code` is added and the URI is versioned. Reading `code` first and falling back to the last
 * segment of `type` is correct under both, and needs no change when the second arrives.
 *
 * The two spellings are folded — §10.4's codes are `SCREAMING_SNAKE` and the problem URIs are
 * `kebab-case` — so `WEAK_PASSWORD` and `.../weak-password` are one answer rather than two.
 */
export function refusalOf(cause: unknown): CredentialRefusal {
  if (!(cause instanceof ApiError)) return 'other';

  const problem = cause.problem;
  if (problem === null) return 'other';

  const slug = slugOf(problem);
  return slug !== null && isNamed(slug) ? slug : 'other';
}

function slugOf(problem: Problem): string | null {
  const code = problem.code?.trim() ?? '';
  if (code !== '') return code.toLowerCase().replace(/_/gu, '-');

  const type = problem.type?.trim() ?? '';
  if (type === '') return null;

  const segments = type.split('/');
  const last = segments[segments.length - 1] ?? '';
  return last === '' ? null : last.toLowerCase();
}

/**
 * The sentence the service wrote for this refusal, or `null` where it wrote none.
 *
 * Separate from `describeAuthFailure`, which answers a different question — that one produces
 * the heading and body of an alert, and this one produces the single line that sits under one
 * field. A wrong current password belongs beside the current-password box and not at the top
 * of the form, because a message beside the control is the difference between an instruction
 * and a riddle (`Field` wires it to `aria-describedby` and the announcement follows).
 */
export function refusalDetailOf(cause: unknown): string | null {
  if (!(cause instanceof ApiError)) return null;
  return cause.problem?.detail ?? null;
}

/* -------------------------------------------------------------------------
 * A-13 — the password
 * ---------------------------------------------------------------------- */

export interface PasswordChangeInput {
  readonly currentPassword: string;
  readonly newPassword: string;
}

/**
 * Replaces the password, given the current one — `POST /v1/auth/change-password`.
 *
 * **Succeeding signs this browser out.** The service revokes every session for the account,
 * this one included, and the refresh cookie is dead the moment the 204 is written — so the
 * next authorised request 401s. Two things follow, and both are here rather than in the screen
 * that calls it:
 *
 *   - the in-memory access token is dropped, because a token this module knows is dead is a
 *     token no other part of the application should be allowed to spend. Leaving it in place
 *     would send one doomed request per component that happened to refresh after the change.
 *   - the screen's remaining job is a navigation and a sentence, not a discovery. Telling
 *     somebody *before* they submit is `PasswordChangePanel`'s, and the service's own comment
 *     says so: "saying so before the form is submitted is the client's job."
 *
 * A refusal throws the service's problem detail intact. `incorrect-password` is a **403 and
 * not a 401** — `AuthExceptionHandler` is explicit that the access token was accepted and the
 * second check is what failed, so a client must not react by signing the reader in again over
 * a password typed into the wrong box.
 */
export async function changePassword(input: PasswordChangeInput): Promise<void> {
  const response = await authorizedFetch('/v1/auth/change-password', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      currentPassword: input.currentPassword,
      newPassword: input.newPassword,
    }),
  });

  if (!response.ok) throw await errorFrom(response);

  setAccessToken(null);
}

/* -------------------------------------------------------------------------
 * A-13 — what the sign-in page is told afterwards
 * ---------------------------------------------------------------------- */

/**
 * The parameter the password-change screen writes and the sign-in form reads.
 *
 * ONE PLACE, TWO CALLERS, which is `lib/auth/redirect.ts`'s rule for `?next=` and it is here
 * for the same failure: splitting "how it is written" from "how it is read" is how the two come
 * to disagree, and a handover nobody notices has broken is a sign-in page that silently stops
 * explaining why somebody is looking at it.
 *
 * **It carries a name and never a sentence.** The value is one of a fixed set, matched exactly,
 * and anything else renders nothing at all — a query parameter whose text is printed is a query
 * parameter an attacker writes, and a fabricated notice on a sign-in form is the beginning of a
 * phishing page hosted on our own domain.
 */
export const SIGN_IN_NOTICE_PARAM = 'notice';

/** The only value {@link SIGN_IN_NOTICE_PARAM} takes today. */
export const PASSWORD_CHANGED_NOTICE = 'password-changed';

/**
 * Where somebody lands after changing their password.
 *
 * **The sign-in page, and not the home page, because the sign-out was not their idea.**
 * `SessionProvider.signOut` goes home, correctly, for somebody who pressed Sign out and is
 * finished. This person pressed *Change my password*, was warned that succeeding ends every
 * session, and wants to be back where they were — dropping them on the marketing home page with
 * no explanation would read as the change having gone wrong.
 *
 * No `?next=`. The screen they came from is `/settings/password`, and returning somebody to the
 * form they have just completed is the loop `safeReturnPath` refuses for the authentication
 * routes.
 */
export const SIGN_IN_AFTER_PASSWORD_CHANGE = `/sign-in?${SIGN_IN_NOTICE_PARAM}=${PASSWORD_CHANGED_NOTICE}`;

/* -------------------------------------------------------------------------
 * A-12 — the address
 * ---------------------------------------------------------------------- */

export interface EmailChangeInput {
  readonly currentPassword: string;
  readonly newEmail: string;
}

/**
 * Asks to move the account to another address — `POST /v1/auth/change-email`.
 *
 * **202, and the status is the whole contract.** Nothing about the account has changed when
 * this returns: `users.email` moves only when the new address follows the link it is sent, and
 * until then the old address still signs in, still receives, and still resets. V44 carries the
 * argument — writing the address immediately means one typo puts the account behind a mailbox
 * nobody can read, and both sign-in and the reset that would fix it go to the address on the
 * account.
 *
 * So there is nothing to return and nothing to re-read. A screen that called `refresh()` on
 * the session afterwards would be asking `GET /v1/me` a question whose answer it already
 * knows: the address is the old one.
 *
 * **Both addresses are written to**, which is what A-12 asks for. The new one gets the link;
 * the old one gets a notice with no link at all, because it cannot approve the change and does
 * not need to — what it is for is that somebody losing their account finds out at the address
 * they still hold.
 */
export async function requestEmailChange(input: EmailChangeInput): Promise<void> {
  const response = await authorizedFetch('/v1/auth/change-email', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      currentPassword: input.currentPassword,
      newEmail: input.newEmail,
    }),
  });

  if (!response.ok) throw await errorFrom(response);
}

/**
 * Spends the confirmation link and moves the address — `POST /v1/auth/confirm-email-change`.
 *
 * **Unauthenticated, and the token travels in a body.** Both halves are `verifyEmail`'s and
 * are argued in the same two places. The endpoint takes no session because the person
 * following the link is reading the *new* mailbox, which is the browser least likely to be
 * signed in; and the token is posted rather than being a `GET` the service could have handled
 * directly because `VerifyEmailRequest`'s reason applies unchanged — a query string is written
 * to access logs, kept in browser history, and forwarded in the `Referer` header of whatever
 * the page loads next, and this value is a credential until it is spent.
 *
 * Two refusals, and they are genuinely different outcomes rather than two spellings of one:
 * `invalid-verification-link` is a link that cannot work, and `email-already-in-use` is a link
 * that could have worked and was overtaken. The service does **not** spend the link in the
 * second case, deliberately, "so a change that becomes possible again can still be confirmed"
 * — which is why the screen may say to try the same link later rather than to start again.
 */
export async function confirmEmailChange(token: string): Promise<void> {
  const response = await postToAuth('/v1/auth/confirm-email-change', { token });
  if (!response.ok) throw await errorFrom(response);
}
