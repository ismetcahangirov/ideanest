import { ApiError } from './problem';
import { currentAccessToken, refreshAccessToken } from './access-token';
import { currentLocaleCookie } from '../i18n/cookie';
import { DEFAULT_LOCALE } from '../i18n/locale';

/**
 * States the language this client is rendering in, on every request it makes — issue #324.
 *
 * <h2>This closes a real asymmetry rather than adding a feature</h2>
 *
 * The service has localised its taxonomy since V11: `CategoryController`,
 * `PublicProjectController`, `DiscoveryController`, `SearchController`, `CollectionController`
 * and `LocationController` all negotiate on `Accept-Language` and all set
 * `Vary: Accept-Language`. This client has never sent one, so the header the browser happened
 * to attach decided the language of every category name, collection title and facet label —
 * which meant a visitor with a Russian browser read Russian category names on an otherwise
 * English page, and nobody chose that.
 *
 * Sending the language the page is actually drawn in makes the two agree. When nobody has
 * stated a preference the value is {@link DEFAULT_LOCALE}, which is the language the interface
 * is in, so the default is not a silent change of behaviour for anybody — it is the first
 * time the two halves of the page have been asked to match.
 *
 * <h2>Set rather than defaulted</h2>
 *
 * `Headers.set`, not `Headers.append`, and it runs before the caller's own headers are
 * honoured nowhere — a caller that passes an explicit `Accept-Language` has a reason to and
 * keeps it, because `new Headers(init.headers)` is constructed first and this only fills a
 * gap. That matters for the one place that already threads a locale of its own:
 * `lib/api/server.ts`, which negotiates on the server and must not be overruled here.
 */
function withLanguage(headers: Headers): Headers {
  if (!headers.has('Accept-Language')) {
    headers.set('Accept-Language', currentLocaleCookie() ?? DEFAULT_LOCALE);
  }

  return headers;
}

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
 *
 * <h2>`no-cache` here, `no-store` on the session read</h2>
 *
 * THE TWO HELPERS DIFFER ON PURPOSE, and the difference is the point of #200.
 *
 * `no-cache` does not mean "may serve something old". It forces a revalidation
 * on **every** request, so a public read is never answered from a stored copy
 * without the service being asked first — the same guarantee `no-store` gives.
 * What it adds is that the revalidation may be *conditional*: the browser keeps
 * the body, sends `If-None-Match`, and a service that has nothing new to say
 * answers `304` with no body at all.
 *
 * `no-store` throws that away. A response the browser is forbidden to store is a
 * response it cannot revalidate against, so no `If-None-Match` is ever sent and
 * a `304` becomes unreachable. §10.3 puts `ETag` and `Cache-Control` on public
 * reads as a convention, and `GET /v1/projects/{id}/rewards/public` chose
 * `private, no-cache` over `no-store` for exactly this reason: a reward list
 * must never read older than it is, but re-sending unchanged tiers, items and
 * shipping rules on every poll of a live stock count is pure waste. `no-store`
 * on this side made the service's choice unreachable from our own client.
 *
 * `send` keeps `no-store` because the trade is not the same there: the session
 * list carries no validator to revalidate against, so there is no `304` to be
 * won — only a stored copy of somebody's device list, on a security screen, to
 * be lost.
 *
 * Both callers today are client components, so this is the browser's HTTP cache
 * and the saving is real. If a Server Component ever reaches for this, Next's
 * extended `fetch` reads `no-cache` as "do not serve this from the Data Cache",
 * which is the same freshness guarantee; only the conditional saving is absent,
 * because a server render has no per-visitor cache to hold a validator in.
 */
export async function publicFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const token = currentAccessToken();
  const headers = withLanguage(new Headers(init.headers));
  if (token !== null) headers.set('Authorization', `Bearer ${token}`);

  return fetch(path, {
    ...init,
    headers,
    cache: 'no-cache',
    credentials: 'same-origin',
  });
}

function send(path: string, init: RequestInit, token: string): Promise<Response> {
  const headers = withLanguage(new Headers(init.headers));
  headers.set('Authorization', `Bearer ${token}`);

  return fetch(path, {
    ...init,
    headers,
    /*
     * `no-store` for the session read, and only for it — NOT a house rule. An
     * account read carries no validator of its own, so there is no conditional
     * request to be had here and nothing is given up by refusing to store the
     * response; a stale device list on a security screen is the one thing that
     * page must never show. Public reads take `no-cache` instead, for the
     * reason `publicFetch` gives — generalising this line to them was #200.
     */
    cache: 'no-store',
    credentials: 'same-origin',
  });
}
