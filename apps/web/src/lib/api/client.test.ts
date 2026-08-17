import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authorizedFetch, publicFetch } from './client';
import { setAccessToken } from './access-token';
import { ApiError, errorFrom } from './problem';

const fetchMock = vi.fn<typeof fetch>();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function bare(status: number): Response {
  return new Response(null, { status });
}

function headersOf(call: number): Headers {
  const init = fetchMock.mock.calls[call]?.[1];
  return new Headers(init?.headers);
}

function urlOf(call: number): unknown {
  return fetchMock.mock.calls[call]?.[0];
}

function initOf(call: number): RequestInit | undefined {
  return fetchMock.mock.calls[call]?.[1];
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  // The token is module state; a test that inherited it would pass for the
  // wrong reason.
  setAccessToken(null);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('authorizedFetch', () => {
  it('sends the access token, and never a cached response', async () => {
    setAccessToken('token-1');
    fetchMock.mockResolvedValueOnce(json([]));

    await authorizedFetch('/v1/auth/sessions');

    expect(urlOf(0)).toBe('/v1/auth/sessions');
    expect(headersOf(0).get('Authorization')).toBe('Bearer token-1');
    /*
     * `no-store`, and it stays `no-store` (#200). A session list has no cache
     * headers of its own to revalidate against, so there is no `304` here to
     * win — only a stored copy of a device list on a security screen to lose.
     * `publicFetch` is the half that changed; the two are asserted separately
     * so that generalising one to the other fails a test rather than a review.
     */
    expect(initOf(0)?.cache).toBe('no-store');
  });

  it('exchanges the refresh cookie for a token when it holds none', async () => {
    fetchMock
      .mockResolvedValueOnce(json({ accessToken: 'fresh' }))
      .mockResolvedValueOnce(json([]));

    await authorizedFetch('/v1/auth/sessions');

    expect(urlOf(0)).toBe('/v1/auth/refresh');
    // SameSite=Strict already blocks the cross-site case; this is the second
    // lock, and only script can set it.
    expect(headersOf(0).get('X-IdeaNest-Client')).toBe('ideanest-web');
    expect(headersOf(1).get('Authorization')).toBe('Bearer fresh');
  });

  // An access token lasts fifteen minutes, so a page left open will meet this.
  it('refreshes and retries once when the token has expired', async () => {
    setAccessToken('stale');
    fetchMock
      .mockResolvedValueOnce(bare(401))
      .mockResolvedValueOnce(json({ accessToken: 'fresh' }))
      .mockResolvedValueOnce(json([]));

    const response = await authorizedFetch('/v1/auth/sessions');

    expect(response.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(headersOf(2).get('Authorization')).toBe('Bearer fresh');
  });

  // A 401 carrying a token minted moments ago is a real refusal, not an
  // expiry. Retrying it again would be a loop.
  it('gives up after one retry rather than looping', async () => {
    setAccessToken('stale');
    fetchMock
      .mockResolvedValueOnce(bare(401))
      .mockResolvedValueOnce(json({ accessToken: 'fresh' }))
      .mockResolvedValueOnce(bare(401));

    const response = await authorizedFetch('/v1/auth/sessions');

    expect(response.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('reports a dead session as a 401 rather than returning a broken response', async () => {
    fetchMock.mockResolvedValue(bare(401));

    const failure: unknown = await authorizedFetch('/v1/auth/sessions').catch(
      (cause: unknown) => cause,
    );

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({ status: 401 });
  });

  /*
   * Refresh tokens rotate on use and a reused one is treated as stolen, which
   * ends the whole session family. Two concurrent refreshes would do exactly
   * that to a user whose only mistake was opening two panels at once.
   */
  it('shares one refresh between callers that race for it', async () => {
    let release: (response: Response) => void = () => {};
    const pending = new Promise<Response>((resolve) => {
      release = resolve;
    });

    fetchMock.mockImplementation((input) =>
      input === '/v1/auth/refresh' ? pending : Promise.resolve(json([])),
    );

    const both = Promise.all([
      authorizedFetch('/v1/auth/sessions'),
      authorizedFetch('/v1/me'),
    ]);
    release(json({ accessToken: 'fresh' }));
    await both;

    const refreshes = fetchMock.mock.calls.filter(([input]) => input === '/v1/auth/refresh');
    expect(refreshes).toHaveLength(1);
  });
});

describe('publicFetch', () => {
  /*
   * #200. `no-store` forbids the browser from keeping the response at all, so it
   * never sends `If-None-Match`, so the server can never answer `304` — and
   * `GET /v1/projects/{id}/rewards/public` carries an `ETag` and
   * `Cache-Control: private, no-cache` precisely so that it can
   * (docs/architecture.md §10.3). `no-cache` forces a revalidation on every
   * single read, so no stale body is ever served; it only allows that
   * revalidation to be conditional.
   */
  it('revalidates every read rather than refusing to store it', async () => {
    fetchMock.mockResolvedValueOnce(json({ rewards: [] }));

    await publicFetch('/v1/projects/p1/rewards/public');

    expect(initOf(0)?.cache).toBe('no-cache');
    expect(initOf(0)?.cache).not.toBe('no-store');
    expect(initOf(0)?.credentials).toBe('same-origin');
  });

  /*
   * The token changes the answer where there is one, but it is never fetched: a
   * 401 on a `permitAll` endpoint is not an expiry to recover from, and a
   * refresh here would put a sign-in wall in front of a public price list.
   */
  it('sends the token it already holds, and never asks for one', async () => {
    setAccessToken('token-1');
    fetchMock.mockResolvedValueOnce(json({ rewards: [] }));

    await publicFetch('/v1/projects/p1/rewards/public');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(headersOf(0).get('Authorization')).toBe('Bearer token-1');
  });

  it('sends no Authorization header when it holds no token', async () => {
    fetchMock.mockResolvedValueOnce(json({ rewards: [] }));

    await publicFetch('/v1/projects/p1/rewards/public');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(headersOf(0).has('Authorization')).toBe(false);
  });

  it('leaves the caller its own headers and method', async () => {
    fetchMock.mockResolvedValueOnce(json({ following: true, followerCount: 1 }));

    await publicFetch('/v1/projects/p1/remind', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
    });

    expect(initOf(0)?.method).toBe('POST');
    expect(headersOf(0).get('content-type')).toBe('application/json');
  });
});

/*
 * A revalidation the browser performs for itself is invisible to script: it
 * answers the `fetch` with the stored 200 and its body, and the `304` is spent
 * inside the HTTP cache. So application code sees a bare `304` only if a caller
 * has sent a conditional header of its own — nothing does today — and the point
 * of these two is that such a response degrades rather than crashes.
 */
describe('a 304 that reaches application code', () => {
  it('is handed back untouched by publicFetch', async () => {
    fetchMock.mockResolvedValueOnce(bare(304));

    const response = await publicFetch('/v1/projects/p1/rewards/public');

    expect(response.status).toBe(304);
    expect(response.ok).toBe(false);
  });

  // A 304 is bodiless by definition, so the parser must not need a body.
  it('reads as a bodiless refusal rather than a parse failure', async () => {
    const failure = await errorFrom(bare(304));

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure.status).toBe(304);
    expect(failure.problem).toBeNull();
  });
});
