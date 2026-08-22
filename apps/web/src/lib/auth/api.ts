import { setAccessToken } from '../api/access-token';
import { errorFrom } from '../api/problem';

/**
 * §4.1's authentication endpoints, as the browser calls them — A-01, A-02, A-03.
 *
 * ONE MODULE, ONE PLACE, the rule `lib/discovery/api.ts` and `lib/projects/api.ts` already
 * state. Every shape here comes from `az.ideanest.auth.api`; nothing invents a field.
 *
 * <h2>Not the generated client, and not `authorizedFetch`</h2>
 *
 * `@ideanest/api-client` reads and does not write — every method on it is a `GET`, for the
 * reason its own comment gives. These are the three writes that create a session in the
 * first place, so they are plain `fetch` against the relative `/v1` path that
 * `next.config.mjs` proxies. Relative is what makes the request same-origin, and
 * same-origin is the only arrangement in which the `SameSite=Strict` refresh cookie the
 * service sets on a sign-in is stored at all.
 *
 * `authorizedFetch` is the wrong shape for the same reason `publicFetch` exists: it throws
 * when there is no token, and somebody signing in is by definition somebody who has none.
 *
 * <h2>The access token never touches storage</h2>
 *
 * A sign-in answers with a fifteen-minute bearer token in the body and a thirty-day refresh
 * token in an `HttpOnly` cookie. `setAccessToken` puts the first in the module variable
 * `lib/api/access-token.ts` owns and nowhere else; the second is never visible to script by
 * design. This module is the only place that writes the token on the way in, so there is
 * one answer to "where did this session come from".
 */

/**
 * `X-IdeaNest-Client`, which `lib/api/access-token.ts` sends on every cookie-authenticated
 * request and this module sends on all three of its own.
 *
 * On the sign-in it carries no cookie yet and is sent anyway. The header costs nothing, the
 * service reads it as "this is the web client", and a client that sets it only where it is
 * strictly required is a client whose next endpoint forgets to.
 */
const CLIENT_HEADER = 'X-IdeaNest-Client';
const CLIENT_HEADER_VALUE = 'ideanest-web';

async function post(path: string, body: unknown): Promise<Response> {
  return fetch(path, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      [CLIENT_HEADER]: CLIENT_HEADER_VALUE,
    },
    body: JSON.stringify(body),
    credentials: 'same-origin',
    /*
     * `no-store`, and this is the case `lib/api/client.ts` calls out as genuinely wanting
     * it rather than `no-cache`: a credential exchange carries no validator to win a `304`
     * with, so nothing is given up by refusing to store it, and what would be stored is
     * somebody's sign-in response.
     */
    cache: 'no-store',
  });
}

/* -------------------------------------------------------------------------
 * A-01 — registration
 * ---------------------------------------------------------------------- */

export interface RegistrationInput {
  readonly email: string;
  readonly password: string;
  readonly name: string;
  /** `az`, `en`, `ru` or `tr`. Omitted means the service's default, Azerbaijani. */
  readonly locale?: string;
}

/**
 * Creates an account and issues its verification token — `POST /v1/auth/register`.
 *
 * **It answers 202 whether or not the address was already registered, and the client must
 * not try to be cleverer than that.** `AuthController` explains why at length: an endpoint
 * that answered differently for a known address hands anybody holding a breach list the
 * subset of it with an account here. So there is nothing to return, and the page that calls
 * this shows the same "check your email" state either way.
 */
export async function register(input: RegistrationInput): Promise<void> {
  const response = await post('/v1/auth/register', {
    email: input.email,
    password: input.password,
    name: input.name,
    ...(input.locale === undefined ? {} : { locale: input.locale }),
  });

  if (!response.ok) throw await errorFrom(response);
}

/* -------------------------------------------------------------------------
 * A-02 — email verification
 * ---------------------------------------------------------------------- */

/**
 * Redeems a verification link — `POST /v1/auth/verify-email`.
 *
 * THE TOKEN TRAVELS IN A BODY, which is why this is a `POST` of a value the page read out
 * of its own URL rather than a link the service could have handled directly.
 * `VerifyEmailRequest` gives the reason: a query string is written to access logs, kept in
 * browser history, and forwarded in the `Referer` header of whatever the page loads next —
 * and this value is a credential until it is spent.
 *
 * A refused token throws an `ApiError` carrying the service's own problem detail, and the
 * page prints that sentence rather than guessing whether the token expired, was already
 * spent, or never existed.
 */
export async function verifyEmail(token: string): Promise<void> {
  const response = await post('/v1/auth/verify-email', { token });
  if (!response.ok) throw await errorFrom(response);
}

/* -------------------------------------------------------------------------
 * A-03 — sign in
 * ---------------------------------------------------------------------- */

export interface SignInInput {
  readonly email: string;
  readonly password: string;
  /** What to call this browser in the account's session list (§4.1 A-09). */
  readonly deviceLabel?: string;
}

/**
 * What a sign-in can end as.
 *
 * TWO OUTCOMES FROM ONE 200, because that is what the service sends. `TokenController`
 * answers `200` with `twoFactorRequired: true` and no tokens when the account has a second
 * factor confirmed, and it is explicit that this is not a refusal — the password was
 * accepted and the flow is halfway through. A client that read it as a failure would tell
 * somebody their password was wrong when it was right.
 *
 * A discriminated union rather than an optional field, so a caller cannot reach for a token
 * that a challenge does not carry.
 */
export type SignInOutcome =
  | { readonly kind: 'signed-in' }
  | {
      readonly kind: 'two-factor-required';
      readonly challenge: string;
      readonly expiresInSeconds: number;
    };

interface TokenBody {
  readonly accessToken?: string;
  readonly twoFactorRequired?: boolean;
  readonly challenge?: string;
  readonly expiresInSeconds?: number;
}

/**
 * Signs in with an email address and a password — `POST /v1/auth/login`.
 *
 * `tokenDelivery` is left unset, which the service reads as `cookie`: the refresh token
 * goes into the `HttpOnly` cookie and never into a body this application could read.
 * `SignInRequest` states the rule — a browser must not be handed a refresh token that one
 * cross-site scripting bug turns into a thirty-day credential.
 *
 * Every refusal is thrown as an `ApiError` with the service's problem detail intact, and
 * the sign-in page branches on `code`: `ACCOUNT_SUSPENDED` is a 403 that must not offer a
 * retry, a 429 is §17.3's five-attempts-per-fifteen-minutes limit, and everything else is
 * the one wrong-credentials sentence the service wrote.
 */
export async function signIn(input: SignInInput): Promise<SignInOutcome> {
  const response = await post('/v1/auth/login', {
    email: input.email,
    password: input.password,
    ...(input.deviceLabel === undefined ? {} : { deviceLabel: input.deviceLabel }),
  });

  if (!response.ok) throw await errorFrom(response);

  const body = (await response.json()) as TokenBody;

  if (body.twoFactorRequired === true) {
    /*
     * No token is written here, deliberately. Half a sign-in is not a session, and setting
     * an access token the service did not issue would be inventing one.
     */
    return {
      kind: 'two-factor-required',
      challenge: body.challenge ?? '',
      expiresInSeconds: body.expiresInSeconds ?? 0,
    };
  }

  const accessToken = body.accessToken ?? null;
  if (accessToken === null) {
    /*
     * A 200 carrying neither a token nor a challenge is a contract this build does not
     * understand. Treating it as success would leave the browser believing it is signed in
     * while every subsequent request 401s, which is the confusing half of that failure.
     */
    throw new Error('The sign-in response carried neither an access token nor a challenge.');
  }

  setAccessToken(accessToken);
  return { kind: 'signed-in' };
}

/**
 * A label for this browser, for the account's session list.
 *
 * DERIVED, NOT ASKED FOR. §4.1's A-09 device list is only useful if its rows are
 * distinguishable, and a sign-in form that asked somebody to name their laptop before
 * letting them in is a form nobody finishes. The service treats the value as display text
 * and nothing else — `SignInRequest` says so — so a coarse guess is honest, and a precise
 * fingerprint would be a tracking surface built for no reason.
 *
 * `undefined` where nothing can be told, rather than the word "Unknown": a session row
 * showing only the address and time it was created is better than one asserting a device
 * nobody has.
 */
export function deviceLabelOf(userAgent: string | undefined): string | undefined {
  if (userAgent === undefined || userAgent.trim() === '') return undefined;

  const platform = /iPhone|iPad|iPod/u.test(userAgent)
    ? 'iOS'
    : /Android/u.test(userAgent)
      ? 'Android'
      : /Mac OS X|Macintosh/u.test(userAgent)
        ? 'macOS'
        : /Windows/u.test(userAgent)
          ? 'Windows'
          : /Linux/u.test(userAgent)
            ? 'Linux'
            : null;

  /*
   * Order matters and is not alphabetical: every Chromium browser calls itself Safari as
   * well, and Edge calls itself Chrome. Testing the most specific token first is what stops
   * every row in the list reading "Safari".
   */
  const browser = /Edg\//u.test(userAgent)
    ? 'Edge'
    : /Chrome\//u.test(userAgent)
      ? 'Chrome'
      : /Firefox\//u.test(userAgent)
        ? 'Firefox'
        : /Safari\//u.test(userAgent)
          ? 'Safari'
          : null;

  if (platform === null && browser === null) return undefined;
  if (platform === null) return `${browser} browser`;
  if (browser === null) return `${platform} browser`;
  return `${browser} on ${platform}`;
}
