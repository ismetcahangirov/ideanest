import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAccessToken } from '../api/access-token';
import { cancelDeletion, fetchAccountExport, requestDeletion } from './closure';

/**
 * §4.1's A-10 and A-11 — issue #279.
 *
 * WHAT THESE COVER:
 *
 *   - **the two 404s that are answers rather than failures.** A deletion request against an
 *     account that is already gone, and a cancellation with nothing pending, are both states
 *     the reader asked for. Reporting them as errors would tell somebody their closure failed
 *     when there was nothing left to close.
 *   - the password reaches the endpoint and the cancellation carries none, which is §17.4's
 *     deliberate asymmetry — requiring it to cancel would obstruct the victim of a deletion
 *     they did not ask for.
 *   - the export comes back as bytes, so the caller can hand them to the browser. It cannot be
 *     a plain link: the session is an `Authorization` header and a navigation carries none.
 */

const originalFetch = globalThis.fetch;

beforeEach(() => {
  setAccessToken('a-token');
});

afterEach(() => {
  setAccessToken(null);
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
});

/*
 * The stub declares the parameters it is called with, rather than taking none. `vi.fn()` types
 * `mock.calls` from the function it wraps, so a zero-argument stub gives every call the type
 * `[]` — and the assertions below would have to cast through `unknown` to read a body that is
 * plainly there.
 */
function respondWith(body: BodyInit | null, status: number, contentType = 'application/json') {
  const send = vi.fn(
    async (_path: string, _init?: RequestInit) =>
      new Response(body, { status, headers: { 'content-type': contentType } }),
  );
  vi.stubGlobal('fetch', send);
  return send;
}

/** The `RequestInit` of one call, which the stub above guarantees is there. */
function initOf(send: ReturnType<typeof respondWith>, call = 0): RequestInit {
  return send.mock.calls[call]?.[1] ?? {};
}

describe('requestDeletion', () => {
  it('reports the date the account is told, rather than an interval', async () => {
    respondWith(
      JSON.stringify({
        requestedAt: '2026-08-23T09:00:00Z',
        scheduledFor: '2026-09-22T09:00:00Z',
      }),
      202,
    );

    const outcome = await requestDeletion('correct horse');

    expect(outcome).toEqual({
      kind: 'scheduled',
      schedule: { requestedAt: '2026-08-23T09:00:00Z', scheduledFor: '2026-09-22T09:00:00Z' },
    });
  });

  it('sends the password, which is what stops a stolen token closing an account', async () => {
    const send = respondWith(JSON.stringify({ requestedAt: 'a', scheduledFor: 'b' }), 202);

    await requestDeletion('correct horse');

    expect(JSON.parse(String(initOf(send).body))).toEqual({ password: 'correct horse' });
  });

  it('reads a 404 as an account that is already gone, not as a failure', async () => {
    respondWith(null, 404);
    expect(await requestDeletion('correct horse')).toEqual({ kind: 'already-gone' });
  });

  it('surfaces the rate limit rather than retrying into it', async () => {
    respondWith(JSON.stringify({ title: 'Too many attempts' }), 429);
    await expect(requestDeletion('x')).rejects.toMatchObject({ status: 429 });
  });
});

describe('cancelDeletion', () => {
  it('carries no password, deliberately', async () => {
    const send = respondWith(null, 204);

    expect(await cancelDeletion()).toBe('cancelled');

    expect(initOf(send).method).toBe('DELETE');
    expect(initOf(send).body).toBeUndefined();
  });

  it('reads a 404 as nothing pending', async () => {
    respondWith(null, 404);
    expect(await cancelDeletion()).toBe('nothing-pending');
  });
});

describe('fetchAccountExport', () => {
  it('returns the bytes so the caller can hand them to the browser', async () => {
    respondWith('{"account":{}}', 200);

    const blob = await fetchAccountExport();
    expect(await blob.text()).toBe('{"account":{}}');
  });

  it('throws on a refusal rather than downloading the problem document', async () => {
    respondWith(JSON.stringify({ title: 'Too many exports' }), 429);
    await expect(fetchAccountExport()).rejects.toMatchObject({ status: 429 });
  });
});
