import { authorizedFetch } from '../api/client';
import { setAccessToken } from '../api/access-token';
import { errorFrom } from '../api/problem';
import { postToAuth } from './post';

/**
 * §4.1's A-07 and A-08 — the second factor, on both sides of a session.
 *
 * `TwoFactorController` publishes four endpoints and they do not share an authentication
 * model, which is the whole reason they are four:
 *
 * | Endpoint | Who calls it | With what |
 * |---|---|---|
 * | `/2fa/verify` | somebody halfway through a sign-in | a challenge, no token |
 * | `/2fa/enable` | somebody signed in | a bearer token and their password |
 * | `/2fa/confirm` | the same person, a moment later | a bearer token and a code |
 * | `/2fa/disable` | the same person, later still | a bearer token, their password, and a code |
 *
 * So `verify` goes through `postToAuth` — there is no token to send — and the other three go
 * through `authorizedFetch`. One module because they are one feature; two transports because
 * the controller's own comment says a single handler branching on which credential arrived is
 * "exactly where a bypass hides".
 */

/* -------------------------------------------------------------------------
 * A-07 / A-08 — completing a sign-in (#272)
 * ---------------------------------------------------------------------- */

/**
 * What the challenge screen sends.
 *
 * **Exactly one of `code` and `recoveryCode`**, and the type says so rather than leaving two
 * optional strings for a caller to send together. `TwoFactorChallenges.complete` accepts
 * either and spends the recovery code when it is the one that matched; sending both would be
 * asking the service to decide which of two credentials this person meant to spend.
 */
export type TwoFactorProof =
  | { readonly kind: 'code'; readonly code: string }
  | { readonly kind: 'recovery-code'; readonly recoveryCode: string };

interface TokenBody {
  readonly accessToken?: string;
}

/**
 * Finishes a sign-in with a second factor — `POST /v1/auth/2fa/verify`.
 *
 * On success the access token is written to memory exactly as `signIn` writes it, and the
 * refresh cookie is set by the response. From here on this browser holds an ordinary session:
 * the service is explicit that a two-factor sign-in ends in the same place as a password one.
 *
 * **A refusal is thrown with the service's problem detail intact.** The screen prints that
 * sentence. There are three failures worth distinguishing and this module distinguishes none
 * of them, on purpose — an expired challenge, a wrong code and a spent recovery code all come
 * back as the service's own refusal, and inventing a more specific message would be inventing
 * a claim about which one it was.
 */
export async function completeTwoFactor(challenge: string, proof: TwoFactorProof): Promise<void> {
  const response = await postToAuth('/v1/auth/2fa/verify', {
    challenge,
    ...(proof.kind === 'code' ? { code: proof.code } : { recoveryCode: proof.recoveryCode }),
  });

  if (!response.ok) throw await errorFrom(response);

  const body = (await response.json()) as TokenBody;
  const accessToken = body.accessToken ?? null;
  if (accessToken === null) {
    /*
     * The same refusal `signIn` makes for the same shape. A 200 with no token would leave the
     * browser believing it is signed in while every subsequent request 401s, which is the
     * confusing half of that failure.
     */
    throw new Error('The two-factor response carried no access token.');
  }

  setAccessToken(accessToken);
}

/* -------------------------------------------------------------------------
 * A-07 — enrolment (#278)
 * ---------------------------------------------------------------------- */

/**
 * The secret, returned once.
 *
 * `otpauthUri` is what an authenticator app scans; `secret` is the same value in the form
 * somebody types when they cannot scan. Both are in the one response because the service
 * will not repeat it — a second `enable` generates a different secret.
 */
export interface TwoFactorEnrolment {
  readonly secret: string;
  readonly otpauthUri: string;
  readonly digits: number;
  readonly periodSeconds: number;
  readonly algorithm: string;
}

/**
 * Starts an enrolment — `POST /v1/auth/2fa/enable`.
 *
 * **Two-factor is not on when this returns**, which is the controller's own emphasis and is
 * the reason the screen is two steps rather than one: nothing about signing in changes until
 * `confirm` succeeds, so a phone that dies between the two calls is an abandoned attempt
 * rather than a lockout.
 *
 * The password is required because an access token is fifteen minutes of trust and turning a
 * security control on is not something a stolen one should be able to do.
 */
export async function startTwoFactorEnrolment(password: string): Promise<TwoFactorEnrolment> {
  const response = await authorizedFetch('/v1/auth/2fa/enable', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ password }),
  });

  if (!response.ok) throw await errorFrom(response);
  return (await response.json()) as TwoFactorEnrolment;
}

/**
 * Confirms the enrolment with a current code, and returns the recovery codes —
 * `POST /v1/auth/2fa/confirm`.
 *
 * **This is the only response that will ever contain them.** What is stored is a hash, so
 * they cannot be shown again, only replaced by enrolling afresh. The screen that calls this
 * has to put them in front of somebody before it navigates anywhere, and has to say that it
 * is the only time.
 */
export async function confirmTwoFactorEnrolment(code: string): Promise<readonly string[]> {
  const response = await authorizedFetch('/v1/auth/2fa/confirm', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code }),
  });

  if (!response.ok) throw await errorFrom(response);

  const body = (await response.json()) as { readonly recoveryCodes?: readonly string[] };
  return body.recoveryCodes ?? [];
}

/**
 * Switches two-factor off — `POST /v1/auth/2fa/disable`.
 *
 * The password **and** a proof, because turning the control off is the operation an attacker
 * holding a stolen access token actually wants. A recovery code is accepted in place of a
 * generated one for the person whose phone is the reason they are here.
 */
export async function disableTwoFactor(password: string, proof: TwoFactorProof): Promise<void> {
  const response = await authorizedFetch('/v1/auth/2fa/disable', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      password,
      ...(proof.kind === 'code' ? { code: proof.code } : { recoveryCode: proof.recoveryCode }),
    }),
  });

  if (!response.ok) throw await errorFrom(response);
}
