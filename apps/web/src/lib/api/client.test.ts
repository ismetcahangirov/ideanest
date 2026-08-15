import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authorizedFetch } from './client';
import { setAccessToken } from './access-token';
import { ApiError } from './problem';

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
    expect(fetchMock.mock.calls[0]?.[1]?.cache).toBe('no-store');
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
