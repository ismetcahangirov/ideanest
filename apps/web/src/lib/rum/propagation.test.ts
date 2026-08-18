import { describe, expect, it } from 'vitest';
import { propagateCorrelation } from './propagation';

const ORIGIN = 'http://localhost:3000';
const additions = (): Record<string, string> => ({
  'X-Request-Id': '019432f1-2c4a-7bb1-9f7e-0f21b7c9a4d2',
  traceparent: '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01',
});

function headersOf(args: readonly unknown[]): Headers {
  const init = args[1] as RequestInit | undefined;
  return new Headers(init?.headers);
}

describe('propagateCorrelation', () => {
  it('adds the trace to a same-origin API call', () => {
    const args = propagateCorrelation('/v1/discover', undefined, ORIGIN, additions);

    expect(args[0]).toBe('/v1/discover');
    expect(headersOf(args).get('traceparent')).toBe(additions()['traceparent']);
    expect(headersOf(args).get('X-Request-Id')).toBe(additions()['X-Request-Id']);
  });

  it('keeps the headers, method and body the caller supplied', () => {
    const init: RequestInit = {
      method: 'POST',
      body: '{}',
      headers: { 'content-type': 'application/json' },
      cache: 'no-store',
    };
    const args = propagateCorrelation('/v1/pledges', init, ORIGIN, additions);
    const resulting = args[1] as RequestInit;

    expect(resulting.method).toBe('POST');
    expect(resulting.body).toBe('{}');
    expect(resulting.cache).toBe('no-store');
    expect(headersOf(args).get('content-type')).toBe('application/json');
    expect(headersOf(args).get('traceparent')).not.toBeNull();
  });

  /*
   * The moment `lib/api/client.ts` propagates the trace itself, this stops doing
   * it. That is what makes this a stopgap rather than a conflict.
   */
  it('never overwrites a header the caller already set', () => {
    const init: RequestInit = { headers: { traceparent: 'theirs', 'X-Request-Id': 'theirs-too' } };
    const args = propagateCorrelation('/v1/discover', init, ORIGIN, additions);

    expect(headersOf(args).get('traceparent')).toBe('theirs');
    expect(headersOf(args).get('X-Request-Id')).toBe('theirs-too');
    // Nothing changed, so the original arguments are handed straight through.
    expect(args[1]).toBe(init);
  });

  it.each([
    ['a page request', '/discover'],
    ['the beacon endpoint itself', '/api/rum'],
    ['a static asset', '/_next/static/chunks/main.js'],
    ['a cross-origin request', 'https://example.com/v1/discover'],
    ['a path that only looks like the API', '/v1'],
  ])('leaves %s untouched', (_description, input) => {
    const init: RequestInit = { method: 'GET' };
    const args = propagateCorrelation(input, init, ORIGIN, additions);

    expect(args[0]).toBe(input);
    expect(args[1]).toBe(init);
  });

  it('adds the trace to a Request object by rebuilding it', async () => {
    const request = new Request(`${ORIGIN}/v1/pledges`, { method: 'POST', body: '{"a":1}' });
    const args = propagateCorrelation(request, undefined, ORIGIN, additions);
    const rebuilt = args[0] as Request;

    expect(rebuilt).not.toBe(request);
    expect(rebuilt.headers.get('traceparent')).toBe(additions()['traceparent']);
    expect(rebuilt.method).toBe('POST');
    await expect(rebuilt.text()).resolves.toBe('{"a":1}');
  });

  /*
   * §4.5 runs the entire pledge flow through `fetch`. Instrumentation that could
   * break it would be worse than no instrumentation at all.
   */
  it('hands back the original when the URL cannot be resolved', () => {
    const notAUrl = { toString: () => 'http://[' } as unknown as string;
    expect(propagateCorrelation(notAUrl, undefined, ORIGIN, additions)[0]).toBe(notAUrl);
  });

  it('hands back the original when a Request cannot be rebuilt', async () => {
    const request = new Request(`${ORIGIN}/v1/pledges`, { method: 'POST', body: '{"a":1}' });
    // A body that has already been read cannot be attached to a new Request.
    await request.text();

    const args = propagateCorrelation(request, undefined, ORIGIN, additions);

    expect(args[0]).toBe(request);
  });
});
