import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { currentAccessToken, setAccessToken } from '../api/access-token';
import { ApiError } from '../api/problem';
import {
  PASSWORD_CHANGED_NOTICE,
  SIGN_IN_AFTER_PASSWORD_CHANGE,
  SIGN_IN_NOTICE_PARAM,
  changePassword,
  confirmEmailChange,
  refusalDetailOf,
  refusalOf,
  requestEmailChange,
} from './credentials';

/**
 * §4.1's A-12 and A-13 — issue #277.
 *
 * WHAT THESE COVER:
 *
 *   - **the refusal is read from `type` as well as from `code`.** `AuthExceptionHandler` sets a
 *     `code` on exactly one of its problems and identifies these four only by their `type`
 *     URI, so a client that read `code` alone would print the general message everywhere the
 *     specific one belongs — and one that read `type` alone would break the day a `code` is
 *     added. Both spellings fold to one answer.
 *   - **a password change drops the access token.** The service revokes every session including
 *     the caller's, so a token this module knows is dead must not be left where something else
 *     can spend it on a request that can only 401.
 *   - the two authenticated calls carry a bearer token and the confirmation does not — the
 *     confirmation is unauthenticated by design, because the person following the link is
 *     reading the new mailbox.
 *   - the confirmation's token travels in a body and never in a query string, for the reason
 *     `VerifyEmailRequest` gives: a query string is written to access logs, kept in history,
 *     and forwarded in `Referer`.
 *   - a refusal arrives as an `ApiError` with the service's problem intact, because every
 *     screen prints `detail` and branches on the reason.
 */

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  setAccessToken('access-token');
});

afterEach(() => {
  vi.unstubAllGlobals();
  setAccessToken(null);
});

function problemResponse(status: number, problem: unknown): Response {
  return new Response(JSON.stringify(problem), {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });
}

function lastCall(): [string, RequestInit] {
  const call = fetchMock.mock.calls[0];
  return [String(call?.[0]), call?.[1] as RequestInit];
}

describe('refusalOf', () => {
  it('reads the last segment of the problem type, which is where these four live', () => {
    // None of the four sets a `code`; each is identified only by its type URI.
    for (const slug of [
      'incorrect-password',
      'weak-password',
      'email-already-in-use',
      'invalid-verification-link',
    ] as const) {
      const error = new ApiError(400, { type: `https://ideanest.az/problems/${slug}` });
      expect(refusalOf(error)).toBe(slug);
    }
  });

  it('prefers `code` when the service starts sending one, in either spelling', () => {
    expect(refusalOf(new ApiError(400, { code: 'WEAK_PASSWORD' }))).toBe('weak-password');
    expect(refusalOf(new ApiError(403, { code: 'incorrect-password' }))).toBe(
      'incorrect-password',
    );
  });

  it('answers `other` for anything it does not recognise, including a transport failure', () => {
    expect(refusalOf(new ApiError(500, { type: 'https://ideanest.az/problems/teapot' }))).toBe(
      'other',
    );
    expect(refusalOf(new ApiError(429, null))).toBe('other');
    expect(refusalOf(new TypeError('offline'))).toBe('other');
  });

  it('hands back the service’s own sentence, which is the only one that knows', () => {
    const error = new ApiError(400, {
      type: 'https://ideanest.az/problems/invalid-verification-link',
      detail: 'This link has already been used.',
    });

    // Not "this link is invalid": the service writes three different sentences behind one
    // type, and only it knows which of them this is.
    expect(refusalDetailOf(error)).toBe('This link has already been used.');
    expect(refusalDetailOf(new TypeError('offline'))).toBeNull();
  });
});

describe('changePassword', () => {
  it('posts both passwords to the documented path', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await changePassword({ currentPassword: 'old one', newPassword: 'a much longer one' });

    const [path, init] = lastCall();
    expect(path).toBe('/v1/auth/change-password');
    expect(JSON.parse(String(init.body))).toEqual({
      currentPassword: 'old one',
      newPassword: 'a much longer one',
    });
    expect(new Headers(init.headers).get('authorization')).toBe('Bearer access-token');
  });

  it('drops the access token, because the service has just revoked every session', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await changePassword({ currentPassword: 'old one', newPassword: 'a much longer one' });

    expect(currentAccessToken()).toBeNull();
  });

  it('keeps the token when the change was refused, so the form can be retried', async () => {
    fetchMock.mockResolvedValue(
      problemResponse(403, {
        type: 'https://ideanest.az/problems/incorrect-password',
        title: 'Password required',
        detail: 'That is not the password on this account.',
      }),
    );

    await expect(
      changePassword({ currentPassword: 'wrong', newPassword: 'a much longer one' }),
    ).rejects.toSatisfy(
      (error: unknown) => error instanceof ApiError && refusalOf(error) === 'incorrect-password',
    );

    // A 403 means the access token was accepted and the second check failed. Signing the
    // reader out over a password typed into the wrong box is the reaction the status was
    // chosen to prevent.
    expect(currentAccessToken()).toBe('access-token');
  });
});

describe('requestEmailChange', () => {
  it('posts the password and the new address, and returns nothing to act on', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 202 }));

    await expect(
      requestEmailChange({ currentPassword: 'mine', newEmail: 'new@example.com' }),
    ).resolves.toBeUndefined();

    const [path, init] = lastCall();
    expect(path).toBe('/v1/auth/change-email');
    expect(JSON.parse(String(init.body))).toEqual({
      currentPassword: 'mine',
      newEmail: 'new@example.com',
    });
  });

  it('throws the 409 with its sentence, which names a real conflict rather than an oracle', async () => {
    fetchMock.mockResolvedValue(
      problemResponse(409, {
        type: 'https://ideanest.az/problems/email-already-in-use',
        title: 'Address unavailable',
        detail: 'That address already has an account.',
      }),
    );

    await expect(
      requestEmailChange({ currentPassword: 'mine', newEmail: 'taken@example.com' }),
    ).rejects.toSatisfy(
      (error: unknown) =>
        error instanceof ApiError &&
        error.status === 409 &&
        refusalOf(error) === 'email-already-in-use',
    );
  });
});

describe('confirmEmailChange', () => {
  it('sends the token in a body, unauthenticated, and stores nothing', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await confirmEmailChange('tok_1');

    const [path, init] = lastCall();
    // Never `?token=`: a query string is written to access logs, kept in browser history, and
    // forwarded in `Referer` — and this value is a credential until it is spent.
    expect(path).toBe('/v1/auth/confirm-email-change');
    expect(JSON.parse(String(init.body))).toEqual({ token: 'tok_1' });

    // No bearer token: the person following the link is reading the new mailbox, which is the
    // browser least likely to be signed in.
    expect(new Headers(init.headers).get('authorization')).toBeNull();
    expect(new Headers(init.headers).get('X-IdeaNest-Client')).toBe('ideanest-web');
    expect(init.cache).toBe('no-store');
    expect(init.credentials).toBe('same-origin');
  });
});

describe('the handover to the sign-in page', () => {
  it('is a fixed name and a fixed value, never a sentence in a URL', () => {
    // Text printed from a query parameter is text an attacker writes, and a fabricated notice
    // on a sign-in form is a phishing page hosted on our own domain.
    const url = new URL(SIGN_IN_AFTER_PASSWORD_CHANGE, 'https://ideanest.invalid');

    expect(url.pathname).toBe('/sign-in');
    expect(url.searchParams.get(SIGN_IN_NOTICE_PARAM)).toBe(PASSWORD_CHANGED_NOTICE);
    // No `?next=`: returning somebody to the form they have just completed is a loop.
    expect(url.searchParams.get('next')).toBeNull();
  });
});
