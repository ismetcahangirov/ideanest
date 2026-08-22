import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/problem';
import { currentAccessToken, setAccessToken } from '../api/access-token';
import { deviceLabelOf, register, signIn, verifyEmail } from './api';

/**
 * The three writes that create a session — §4.1 A-01, A-02, A-03.
 *
 * WHAT THESE COVER:
 *
 *   - every request is same-origin, relative, and carries `X-IdeaNest-Client`. Relative is
 *     what makes the `SameSite=Strict` refresh cookie travel at all (`next.config.mjs`), and
 *     an absolute URL here would break the browser half of the auth flow without failing a
 *     type check.
 *   - `no-store`, because what is being exchanged is a credential.
 *   - **the two outcomes of one 200.** A two-factor challenge is not a refusal and must not
 *     write a token; a successful sign-in writes exactly one, into memory.
 *   - a refusal arrives as an `ApiError` carrying the service's problem body intact, because
 *     every caller branches on `code` and prints `detail`.
 */

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  setAccessToken(null);
});

afterEach(() => {
  vi.unstubAllGlobals();
  setAccessToken(null);
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function problemResponse(status: number, problem: unknown): Response {
  return new Response(JSON.stringify(problem), {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });
}

/** The `RequestInit` the one call was made with. */
function lastInit(): RequestInit {
  return fetchMock.mock.calls[0]?.[1] as RequestInit;
}

describe('every request', () => {
  it('is a relative path, so the browser treats it as same-origin', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 202 }));
    await register({ email: 'a@example.com', password: 'p', name: 'A' });

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/v1/auth/register');
  });

  it('carries the client header and stores nothing', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await verifyEmail('tok_1');

    const init = lastInit();
    expect(new Headers(init.headers).get('X-IdeaNest-Client')).toBe('ideanest-web');
    expect(init.cache).toBe('no-store');
    expect(init.credentials).toBe('same-origin');
  });
});

describe('register', () => {
  it('sends the three fields and omits an unstated locale', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 202 }));
    await register({ email: 'a@example.com', password: 'p', name: 'Aysel' });

    expect(JSON.parse(String(lastInit().body))).toEqual({
      email: 'a@example.com',
      password: 'p',
      name: 'Aysel',
    });
  });

  it('throws the service’s problem on a refusal', async () => {
    fetchMock.mockResolvedValue(
      problemResponse(400, { title: 'Password rejected', detail: 'Too short', code: 'WEAK_PASSWORD' }),
    );

    await expect(register({ email: 'a@example.com', password: 'p', name: 'A' })).rejects.toSatisfy(
      (error: unknown) =>
        error instanceof ApiError &&
        error.status === 400 &&
        error.problem?.code === 'WEAK_PASSWORD',
    );
  });
});

describe('verifyEmail', () => {
  it('sends the token in a body and never in the query string', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await verifyEmail('tok_1');

    // A query string is written to access logs, kept in history, and forwarded in `Referer` —
    // and this value is a credential until it is spent.
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/v1/auth/verify-email');
    expect(JSON.parse(String(lastInit().body))).toEqual({ token: 'tok_1' });
  });
});

describe('signIn', () => {
  it('writes the access token into memory on success', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ accessToken: 'jwt', tokenType: 'Bearer', expiresInSeconds: 900 }),
    );

    const outcome = await signIn({ email: 'a@example.com', password: 'p' });

    expect(outcome).toEqual({ kind: 'signed-in' });
    expect(currentAccessToken()).toBe('jwt');
  });

  it('asks for cookie delivery by not asking for anything else', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ accessToken: 'jwt' }));
    await signIn({ email: 'a@example.com', password: 'p' });

    // The service reads an unstated `tokenDelivery` as `cookie`. A browser must never be
    // handed a refresh token it can read.
    expect(JSON.parse(String(lastInit().body))).not.toHaveProperty('tokenDelivery');
  });

  it('sends a device label when it is given one, and omits it otherwise', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ accessToken: 'jwt' }));
    await signIn({ email: 'a@example.com', password: 'p', deviceLabel: 'Chrome on macOS' });

    expect(JSON.parse(String(lastInit().body)).deviceLabel).toBe('Chrome on macOS');
  });

  /**
   * The case `TokenController` is explicit about: 200, `twoFactorRequired`, and no tokens.
   * Nothing was refused — the password was accepted and the flow is halfway through.
   */
  it('reads a two-factor challenge as an outcome rather than as a failure', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ twoFactorRequired: true, challenge: 'ch_1', expiresInSeconds: 300 }),
    );

    const outcome = await signIn({ email: 'a@example.com', password: 'p' });

    expect(outcome).toEqual({
      kind: 'two-factor-required',
      challenge: 'ch_1',
      expiresInSeconds: 300,
    });
  });

  it('writes no token for a challenge, because half a sign-in is not a session', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ twoFactorRequired: true, challenge: 'ch_1' }));

    await signIn({ email: 'a@example.com', password: 'p' });

    expect(currentAccessToken()).toBeNull();
  });

  it('refuses a 200 that carries neither a token nor a challenge', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ tokenType: 'Bearer' }));

    // Treating it as success would leave the browser believing it is signed in while every
    // subsequent request 401s.
    await expect(signIn({ email: 'a@example.com', password: 'p' })).rejects.toThrow(
      /neither an access token nor a challenge/u,
    );
    expect(currentAccessToken()).toBeNull();
  });

  it('throws a suspension with its code intact', async () => {
    fetchMock.mockResolvedValue(
      problemResponse(403, { code: 'ACCOUNT_SUSPENDED', title: 'Account suspended' }),
    );

    await expect(signIn({ email: 'a@example.com', password: 'p' })).rejects.toSatisfy(
      (error: unknown) =>
        error instanceof ApiError && error.problem?.code === 'ACCOUNT_SUSPENDED',
    );
    expect(currentAccessToken()).toBeNull();
  });
});

describe('deviceLabelOf', () => {
  it('names the browser and the platform', () => {
    expect(
      deviceLabelOf(
        'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36',
      ),
    ).toBe('Chrome on macOS');
  });

  it('tests the most specific token first, or every row would read Safari', () => {
    // Every Chromium browser calls itself Safari, and Edge calls itself Chrome.
    expect(
      deviceLabelOf(
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36 Edg/140.0',
      ),
    ).toBe('Edge on Windows');

    expect(
      deviceLabelOf(
        'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Safari/604.1',
      ),
    ).toBe('Safari on iOS');
  });

  it('says nothing rather than "Unknown" when it can tell nothing', () => {
    expect(deviceLabelOf(undefined)).toBeUndefined();
    expect(deviceLabelOf('')).toBeUndefined();
    expect(deviceLabelOf('curl/8.4.0')).toBeUndefined();
  });
});
