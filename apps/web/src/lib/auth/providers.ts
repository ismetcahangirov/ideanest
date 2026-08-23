import { readSignInOutcome, type SignInOutcome } from './api';
import { postToAuth } from './post';

/**
 * §4.1's A-04 and A-05 — signing in with Google and with Apple, from the browser.
 *
 * <h2>What the service expects, and what that leaves for this side</h2>
 *
 * `POST /v1/auth/oauth/{provider}` takes an **ID token the client already obtained from the
 * provider** and a nonce, verifies the signature against the provider's JWKS, and answers
 * with exactly what a password sign-in answers with — including a two-factor challenge when
 * the account has one. §17.1 lists every check it makes.
 *
 * So there is no redirect flow to build and no authorisation code to exchange. What this
 * application does is: obtain an ID token, and post it. Everything about trusting it happens
 * on the other side, which is the point of the arrangement — a browser cannot verify a
 * signature against a key set it also fetched.
 *
 * <h2>The nonce is generated here and travels twice</h2>
 *
 * The same value is handed to the provider — which embeds it in the token it signs — and sent
 * beside the token, so the service can compare them. §17.1 is candid about what that buys:
 * "client-supplied for now, which binds the token to the request but does not prove
 * freshness". A server-issued nonce needs shared storage the platform does not have yet
 * (#142). This module does not pretend otherwise; it generates a value with the platform's
 * CSPRNG and nothing more.
 *
 * <h2>A provider with no client identifier is not offered</h2>
 *
 * The service answers **501** for a provider it has no configuration for. A button that
 * always fails is worse than no button, and on a sign-in page it is worse still — it is
 * offered to somebody who has not got in yet. `configuredProviders()` is what the buttons are
 * rendered from, so an unconfigured deployment shows the email form alone.
 *
 * The identifiers are `NEXT_PUBLIC_`, which is not a leak: a client identifier is public by
 * construction — it travels in every authorisation request the provider ever sees. What is
 * secret is the client *secret*, and neither of these flows uses one (§17.1's note on Apple).
 */

export type ProviderId = 'google' | 'apple';

export interface ProviderConfig {
  readonly id: ProviderId;
  readonly label: string;
  readonly clientId: string;
}

/**
 * The client identifiers, read at build time.
 *
 * WRITTEN OUT RATHER THAN INDEXED. `process.env[name]` does not survive the Next build:
 * `NEXT_PUBLIC_` variables are substituted by a literal text replacement of
 * `process.env.NEXT_PUBLIC_FOO`, so a dynamic lookup compiles to a read of an object that is
 * empty in the browser. Two lines is the cost of the values actually being there.
 */
const CLIENT_IDS: Readonly<Record<ProviderId, string | undefined>> = {
  google: process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID,
  apple: process.env.NEXT_PUBLIC_APPLE_CLIENT_ID,
};

const LABELS: Readonly<Record<ProviderId, string>> = {
  google: 'Google',
  apple: 'Apple',
};

/**
 * The providers this deployment can actually sign somebody in with, in the order they are
 * offered. Empty is the ordinary answer in development and is not a failure.
 */
export function configuredProviders(): readonly ProviderConfig[] {
  const order: readonly ProviderId[] = ['google', 'apple'];

  return order.flatMap((id) => {
    const clientId = CLIENT_IDS[id];
    if (clientId === undefined || clientId.trim() === '') return [];
    return [{ id, label: LABELS[id], clientId: clientId.trim() }];
  });
}

/**
 * A nonce for one authorisation request.
 *
 * 32 bytes from `crypto.getRandomValues`, base64url so it survives being put in a URL and in
 * a JWT claim without escaping. Never `Math.random()`: it is not a CSPRNG in any engine, and
 * a predictable nonce is a nonce that binds nothing.
 */
export function generateNonce(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);

  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);

  return btoa(binary).replace(/\+/gu, '-').replace(/\//gu, '_').replace(/=+$/u, '');
}

export interface ProviderSignInInput {
  readonly provider: ProviderId;
  /** The provider's ID token, straight from its SDK. Never inspected here. */
  readonly idToken: string;
  /** The value `generateNonce` produced for the request that obtained the token. */
  readonly nonce: string;
  /**
   * Apple's one chance to tell us a name.
   *
   * §17.1: Apple sends it **once**, in the body of the first authorisation response, never in
   * the ID token and never on a later sign-in. It is used only when an account is created and
   * never modifies an existing one, so forwarding it on every sign-in is harmless and
   * forgetting it once costs somebody their name for good.
   */
  readonly name?: string;
  /** What to call this browser in the account's session list (§4.1 A-09). */
  readonly deviceLabel?: string;
}

/**
 * Exchanges a provider ID token for a session — `POST /v1/auth/oauth/{provider}`.
 *
 * Ends in the same two outcomes a password sign-in ends in, read by the same function, for
 * the reason `readSignInOutcome` gives.
 *
 * Refusals arrive as an `ApiError` with the service's problem detail intact and the sign-in
 * page prints it. The one worth knowing about is the **409**: §17.1's last linking rule
 * refuses to link a verified provider address to a local account that has not verified the
 * same address, because auto-linking it is the pre-registration attack. The service writes a
 * sentence saying what to do — check the verification email — and this client does not
 * paraphrase it.
 */
export async function signInWithProvider(input: ProviderSignInInput): Promise<SignInOutcome> {
  const response = await postToAuth(`/v1/auth/oauth/${encodeURIComponent(input.provider)}`, {
    idToken: input.idToken,
    nonce: input.nonce,
    ...(input.name === undefined ? {} : { name: input.name }),
    ...(input.deviceLabel === undefined ? {} : { deviceLabel: input.deviceLabel }),
  });

  return readSignInOutcome(response);
}
