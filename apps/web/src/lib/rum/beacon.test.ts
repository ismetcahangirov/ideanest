import { describe, expect, it, vi } from 'vitest';
import { BEACON_CONTENT_TYPE, RUM_ENDPOINT, deliver, serialise } from './beacon';
import { REQUEST_ID_HEADER, TRACEPARENT_HEADER } from './correlation';
import type { RumPayload } from './payload';

const correlation = {
  requestId: '019432f1-2c4a-7bb1-9f7e-0f21b7c9a4d2',
  traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
  spanId: '00f067aa0ba902b7',
};

const body = '{"v":1}';

describe('deliver', () => {
  it('uses sendBeacon when the browser has one', () => {
    const sendBeacon = vi.fn().mockReturnValue(true);
    const fetchImpl = vi.fn();

    expect(deliver(body, { sendBeacon, fetch: fetchImpl }, correlation)).toBe('sendBeacon');
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(sendBeacon).toHaveBeenCalledWith(RUM_ENDPOINT, expect.any(Blob));
  });

  /*
   * A bare string is sent as `text/plain;charset=UTF-8`, which the endpoint
   * answers 415. The `Blob` is the only way to set the media type through
   * `sendBeacon`.
   */
  it('sends the body as a JSON blob', async () => {
    const sendBeacon = vi.fn().mockReturnValue(true);
    deliver(body, { sendBeacon }, correlation);

    const blob = sendBeacon.mock.calls[0]?.[1] as Blob;
    expect(blob.type).toBe(BEACON_CONTENT_TYPE);
    await expect(blob.text()).resolves.toBe(body);
  });

  /*
   * `sendBeacon` returns false when the user agent's queue is full or the body
   * is over its own limit. Both are transient and both are recoverable through
   * the other door.
   */
  it('falls back to fetch when sendBeacon refuses the payload', () => {
    const sendBeacon = vi.fn().mockReturnValue(false);
    const fetchImpl = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));

    expect(deliver(body, { sendBeacon, fetch: fetchImpl }, correlation)).toBe('fetch');
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('falls back to fetch when sendBeacon is absent', () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));

    expect(deliver(body, { fetch: fetchImpl }, correlation)).toBe('fetch');
  });

  // Some privacy extensions replace `sendBeacon` with something that throws.
  it('falls back to fetch when sendBeacon throws', () => {
    const sendBeacon = vi.fn().mockImplementation(() => {
      throw new Error('blocked');
    });
    const fetchImpl = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));

    expect(deliver(body, { sendBeacon, fetch: fetchImpl }, correlation)).toBe('fetch');
  });

  /*
   * The whole point of the fallback: the request has to outlive the document, and
   * `keepalive` is what says so. Without it the browser cancels it on unload and
   * the slowest sessions — the ones worth having — are exactly the ones lost.
   */
  it('keeps the fetch alive past the unload, and carries the correlation headers', () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    deliver(body, { fetch: fetchImpl }, correlation);

    const [url, init] = fetchImpl.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(RUM_ENDPOINT);
    expect(init.method).toBe('POST');
    expect(init.keepalive).toBe(true);
    expect(init.credentials).toBe('same-origin');
    const headers = new Headers(init.headers);
    expect(headers.get('content-type')).toBe(BEACON_CONTENT_TYPE);
    expect(headers.get(REQUEST_ID_HEADER)).toBe(correlation.requestId);
    expect(headers.get(TRACEPARENT_HEADER)).toBe(`00-${correlation.traceId}-${correlation.spanId}-01`);
  });

  /*
   * A monitoring feature that throws into the application has become the
   * incident. There is no user-visible consequence of a lost sample.
   */
  it('swallows a rejected fetch rather than raising it into the page', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new TypeError('network'));

    expect(() => deliver(body, { fetch: fetchImpl }, correlation)).not.toThrow();
    await Promise.resolve();
  });

  it('gives up quietly when there is no door at all', () => {
    expect(deliver(body, {}, correlation)).toBe('none');
  });
});

describe('serialise', () => {
  it('produces the wire form the endpoint parses', () => {
    const payload: RumPayload = {
      v: 1,
      requestId: correlation.requestId,
      traceId: correlation.traceId,
      spanId: correlation.spanId,
      sessionId: '0f4b7a2c-1d3e-4f5a-8b9c-0d1e2f3a4b5c',
      route: '/discover',
      connection: '4g',
      device: 'mobile',
      samples: [{ name: 'LCP', value: 1822, navigationType: 'navigate' }],
    };
    expect(JSON.parse(serialise(payload))).toEqual(payload);
  });
});
