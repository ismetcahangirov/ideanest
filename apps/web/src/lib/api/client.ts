import { ApiError } from './problem';
import { currentAccessToken, refreshAccessToken } from './access-token';

/**
 * Calls the API with the account's access token.
 *
 * Same-origin throughout: `next.config.ts` proxies `/v1` to the service, which
 * is what lets a `SameSite=Strict` cookie travel at all.
 */
export async function authorizedFetch(path: string, init: RequestInit = {}): Promise<Response> {
  let token = currentAccessToken() ?? (await refreshAccessToken());
  if (token === null) throw new ApiError(401, null, 'You are not signed in.');

  let response = await send(path, init, token);

  /*
   * An access token lasts fifteen minutes, so a page left open will meet a 401
   * eventually. Refresh and retry — once.
   *
   * Once, because a second 401 carrying a token minted moments ago is a real
   * refusal rather than an expiry, and retrying that is a loop.
   */
  if (response.status === 401) {
    token = await refreshAccessToken();
    if (token === null) throw new ApiError(401, null, 'You are not signed in.');
    response = await send(path, init, token);
  }

  return response;
}

/**
 * Calls the API without requiring a session, but with one when there is one.
 *
 * For the endpoints the filter chain marks `permitAll` — today the pre-launch
 * page and its reminder signup. Those exist precisely for people who have not
 * registered, so `authorizedFetch` is the wrong shape: it throws when there is no
 * token, which would turn "ask to be told when this opens" into "sign in first",
 * and that is the funnel a pre-launch page is there to avoid.
 *
 * The token is still sent when one is already in memory, because it changes the
 * answer: a signed-in visitor's reminder is registered against their account
 * rather than against a typed address. It is never *fetched* — no refresh is
 * attempted, and a 401 is not retried, because on a public endpoint a 401 is not
 * an expiry to recover from.
 */
export async function publicFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const token = currentAccessToken();
  const headers = new Headers(init.headers);
  if (token !== null) headers.set('Authorization', `Bearer ${token}`);

  return fetch(path, {
    ...init,
    headers,
    cache: 'no-store',
    credentials: 'same-origin',
  });
}

function send(path: string, init: RequestInit, token: string): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set('Authorization', `Bearer ${token}`);

  return fetch(path, {
    ...init,
    headers,
    // The session list has no cache headers of its own, and a stale device list
    // on a security screen is the one thing this page must never show.
    cache: 'no-store',
    credentials: 'same-origin',
  });
}
