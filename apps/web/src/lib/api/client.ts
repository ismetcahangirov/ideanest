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
