/**
 * The access token lives in a module variable and nowhere else.
 *
 * It is a fifteen-minute bearer credential. Putting it in `localStorage` would
 * hand it to any script that ever runs on the page and outlive the tab for no
 * benefit; the durable half of the session is the `HttpOnly` refresh cookie,
 * which script cannot read by design.
 */

/**
 * Required on any request that authenticates with the refresh cookie. The
 * cookie is already `SameSite=Strict`; this is the second lock — a header this
 * specific cannot be set by a form post or an image tag, only by script, and
 * script that can set it is already same-origin.
 */
const CLIENT_HEADER = 'X-IdeaNest-Client';
const CLIENT_HEADER_VALUE = 'ideanest-web';

let accessToken: string | null = null;
let refreshInFlight: Promise<string | null> | null = null;

export function currentAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

async function requestRefresh(): Promise<string | null> {
  const response = await fetch('/v1/auth/refresh', {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { [CLIENT_HEADER]: CLIENT_HEADER_VALUE },
  });

  if (!response.ok) {
    // A failed refresh has already cleared the cookie server-side. Dropping the
    // token here keeps the two halves of the session in agreement.
    accessToken = null;
    return null;
  }

  const body = (await response.json()) as { accessToken?: string };
  accessToken = body.accessToken ?? null;
  return accessToken;
}

/**
 * Exchanges the refresh cookie for a fresh access token.
 *
 * Single-flight, and that is not an optimisation. Refresh tokens rotate on
 * every use and the old one is then treated as stolen if it reappears, so two
 * concurrent refreshes would race to invalidate each other and the reuse
 * detector would end the whole session family. One request, shared.
 */
export function refreshAccessToken(): Promise<string | null> {
  refreshInFlight ??= requestRefresh().finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

/**
 * Ends this browser's session: clears the refresh cookie server-side and drops
 * the access token here.
 *
 * The token goes first. If the network call fails the user is still signed out
 * locally, which is the safer of the two ways to be wrong.
 */
export async function signOut(): Promise<void> {
  accessToken = null;

  await fetch('/v1/auth/logout', {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { [CLIENT_HEADER]: CLIENT_HEADER_VALUE },
  });
}
