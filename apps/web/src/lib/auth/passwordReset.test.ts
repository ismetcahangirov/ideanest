import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { currentAccessToken, setAccessToken } from '../api/access-token';
import { ApiError } from '../api/problem';
import { RESET_LINK_LIFETIME, RESET_TOKEN_PARAM, requestPasswordReset, resetPassword } from './passwordReset';

/**
 * §4.1's A-06 — issue #271.
 *
 * WHAT THESE COVER:
 *
 *   - **the request is unauthenticated and same-origin.** Somebody asking for a reset is by
 *     definition somebody who cannot sign in, so no bearer token is sent and none is fetched.
 *     Relative is what makes the request same-origin, which is the only arrangement in which
 *     the service's `SameSite=Strict` cookie behaves.
 *   - **202 is the only success, and there is nothing to branch on.** The endpoint answers
 *     identically for an address with an account and one without, and a client that returned a
 *     richer result would be reconstructing the enumeration oracle the status was chosen to
 *     close.
 *   - **the token goes in a body.** `VerifyEmailRequest`'s reason applies with more at stake
 *     here: this value replaces a credential rather than proving an address.
 *   - a refusal arrives as an `ApiError` with the service's problem intact, because the screen
 *     prints its sentence — and the three sentences behind `invalid-verification-link` are not
 *     interchangeable.
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

function lastCall(): [string, RequestInit] {
  const call = fetchMock.mock.calls[0];
  return [String(call?.[0]), call?.[1] as RequestInit];
}

function problemResponse(status: number, problem: unknown): Response {
  return new Response(JSON.stringify(problem), {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });
}

describe('requestPasswordReset', () => {
  it('posts the address to a relative path with the client header and stores nothing', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 202 }));

    await requestPasswordReset('aysel@example.com');

    const [path, init] = lastCall();
    expect(path).toBe('/v1/auth/forgot-password');
    expect(JSON.parse(String(init.body))).toEqual({ email: 'aysel@example.com' });
    expect(new Headers(init.headers).get('X-IdeaNest-Client')).toBe('ideanest-web');
    expect(init.cache).toBe('no-store');
    expect(init.credentials).toBe('same-origin');
  });

  it('sends no bearer token and never asks for one', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 202 }));

    await requestPasswordReset('aysel@example.com');

    // `authorizedFetch` would have spent the refresh cookie here. Somebody locked out has no
    // session to spend, and a second call to `/v1/auth/refresh` would be the only request made.
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(new Headers(lastCall()[1].headers).get('authorization')).toBeNull();
    expect(currentAccessToken()).toBeNull();
  });

  it('resolves with nothing, because the 202 says nothing about the account', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 202 }));

    // The same answer for an address with an account and one without. That is the whole design
    // — see `PasswordResetService` — and a client that reported more would give it away.
    await expect(requestPasswordReset('nobody@example.com')).resolves.toBeUndefined();
  });

  it('surfaces the rate limit rather than swallowing it', async () => {
    fetchMock.mockResolvedValue(
      problemResponse(429, { title: 'Too many attempts', detail: 'Wait a little.' }),
    );

    await expect(requestPasswordReset('aysel@example.com')).rejects.toSatisfy(
      (error: unknown) => error instanceof ApiError && error.status === 429,
    );
  });
});

describe('resetPassword', () => {
  it('sends the token in the body and never in the query string', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await resetPassword('tok_1', 'a much longer password');

    const [path, init] = lastCall();
    expect(path).toBe('/v1/auth/reset-password');
    expect(JSON.parse(String(init.body))).toEqual({
      token: 'tok_1',
      password: 'a much longer password',
    });
  });

  it('throws the service’s sentence for a link that cannot be used', async () => {
    fetchMock.mockResolvedValue(
      problemResponse(400, {
        type: 'https://ideanest.az/problems/invalid-verification-link',
        title: 'Verification failed',
        detail: 'This link has expired. Ask for a new one.',
      }),
    );

    await expect(resetPassword('tok_1', 'a much longer password')).rejects.toSatisfy(
      (error: unknown) =>
        error instanceof ApiError &&
        // Expired, spent and never-issued all arrive as this one type with three different
        // sentences. The screen prints the sentence rather than guessing which it was.
        error.problem?.detail === 'This link has expired. Ask for a new one.',
    );
  });
});

describe('the constants both screens read', () => {
  it('names the parameter the reset email writes', () => {
    // The contract with the service is this path and this one parameter name.
    expect(RESET_TOKEN_PARAM).toBe('token');
  });

  it('states the lifetime once, so two screens cannot describe one link differently', () => {
    // `ideanest.auth.password-reset-token-ttl` is PT1H.
    expect(RESET_LINK_LIFETIME).toBe('one hour');
  });
});
