import { beforeEach, describe, expect, it } from 'vitest';
import { handleRumGet, handleRumPost, type EndpointDependencies } from './endpoint';
import { SlidingWindowRateLimiter } from './limits';
import { LocalSink, RUM_EVENT, localSinkEnabled } from './sink';
import { MAX_BODY_BYTES, PAYLOAD_VERSION, type RumPayload } from './payload';
import { REQUEST_ID_HEADER, TRACE_ID_HEADER } from './correlation';

const valid: RumPayload = {
  v: PAYLOAD_VERSION,
  requestId: '019432f1-2c4a-7bb1-9f7e-0f21b7c9a4d2',
  traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
  spanId: '00f067aa0ba902b7',
  sessionId: '0f4b7a2c-1d3e-4f5a-8b9c-0d1e2f3a4b5c',
  route: '/[locale]/discover',
  connection: '4g',
  device: 'mobile',
  samples: [{ name: 'LCP', value: 1822, navigationType: 'navigate' }],
};

let lines: string[];
let sink: LocalSink;
let dependencies: EndpointDependencies;

function build(overrides: Partial<EndpointDependencies> = {}): EndpointDependencies {
  return {
    perCaller: new SlidingWindowRateLimiter({ limit: 30, windowMs: 60_000 }),
    global: new SlidingWindowRateLimiter({ limit: 600, windowMs: 60_000 }),
    salt: 'test-salt',
    sink,
    now: () => new Date('2026-08-18T09:00:00.000Z'),
    log: (line) => void lines.push(line),
    ...overrides,
  };
}

function post(body: unknown, init: RequestInit = {}): Request {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  const { headers, ...rest } = init;
  return new Request('http://localhost:3000/api/rum', {
    method: 'POST',
    body: text,
    ...rest,
    headers: { 'content-type': 'application/json', ...(headers as Record<string, string>) },
  });
}

beforeEach(() => {
  lines = [];
  sink = new LocalSink();
  dependencies = build();
});

describe('a beacon the reporter sent', () => {
  it('is accepted with nothing to say', async () => {
    const response = await handleRumPost(post(valid), dependencies);

    expect(response.status).toBe(204);
    expect(await response.text()).toBe('');
  });

  /*
   * §18.1's field names, spelled the way the service spells them, so that one
   * query finds the field measurement and the server spans of the same trace.
   */
  it('writes one line per sample with the correlation identifiers on it', async () => {
    await handleRumPost(
      post({
        ...valid,
        samples: [
          { name: 'LCP', value: 1822, navigationType: 'navigate' },
          { name: 'CLS', value: 0.3, navigationType: 'navigate' },
        ],
      }),
      dependencies,
    );

    expect(lines).toHaveLength(2);
    const first = JSON.parse(lines[0] ?? '{}');
    expect(first).toMatchObject({
      event: RUM_EVENT,
      requestId: valid.requestId,
      traceId: valid.traceId,
      sessionId: valid.sessionId,
      route: '/[locale]/discover',
      metric: 'LCP',
      value: 1822,
      rating: 'good',
      connection: '4g',
      device: 'mobile',
      at: '2026-08-18T09:00:00.000Z',
    });
    // Ours either way: continuing the caller's trace is not adopting its span.
    expect(first.spanId).not.toBe(valid.spanId);
    expect(JSON.parse(lines[1] ?? '{}')).toMatchObject({ metric: 'CLS', rating: 'poor' });
  });

  /*
   * The rating is derived, not read off the wire, so a forged payload cannot
   * claim a poor value is good and the thresholds have one home.
   */
  it('rates the value itself', async () => {
    await handleRumPost(
      post({ ...valid, samples: [{ name: 'LCP', value: 9000, navigationType: 'navigate' }] }),
      dependencies,
    );
    expect(JSON.parse(lines[0] ?? '{}')).toMatchObject({ rating: 'poor' });
  });

  it('returns the identifiers so somebody can quote one', async () => {
    const response = await handleRumPost(post(valid), dependencies);

    expect(response.headers.get(REQUEST_ID_HEADER)).toBe(valid.requestId);
    expect(response.headers.get(TRACE_ID_HEADER)).toBe(valid.traceId);
  });

  /*
   * `sendBeacon` cannot set a header, so the identifiers travel in the body; the
   * `fetch` fallback sets them as well, and a header wins — which is the rule
   * `CorrelationFilter` applies.
   */
  it('prefers the headers when the fetch fallback sent them', async () => {
    const response = await handleRumPost(
      post(valid, {
        headers: {
          [REQUEST_ID_HEADER]: 'header-request-id',
          traceparent: '00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01',
        },
      }),
      dependencies,
    );

    expect(response.headers.get(REQUEST_ID_HEADER)).toBe('header-request-id');
    expect(response.headers.get(TRACE_ID_HEADER)).toBe('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa');
  });

  it('ignores an inbound identifier the service would refuse', async () => {
    const response = await handleRumPost(
      // Too short for `ACCEPTABLE_IDENTIFIER`, which is what the service refuses
      // an inbound identifier on. A newline would be the more pointed example and
      // cannot be built: `Headers` refuses to hold one.
      post(valid, { headers: { [REQUEST_ID_HEADER]: 'nope', traceparent: 'nonsense' } }),
      dependencies,
    );

    expect(response.headers.get(REQUEST_ID_HEADER)).toBe(valid.requestId);
    expect(response.headers.get(TRACE_ID_HEADER)).toBe(valid.traceId);
  });
});

describe('what the endpoint refuses', () => {
  it('answers 405 to anything but POST', async () => {
    const response = await handleRumPost(
      new Request('http://localhost:3000/api/rum', { method: 'PUT' }),
      dependencies,
    );

    expect(response.status).toBe(405);
    expect(response.headers.get('allow')).toBe('POST');
  });

  /*
   * `sendBeacon` sends a bare string as `text/plain`. Refusing it is what forces
   * the reporter to use a `Blob` with the right type, rather than discovering the
   * mismatch in a log six months later.
   */
  it('answers 415 to a media type it does not parse', async () => {
    for (const contentType of ['text/plain;charset=UTF-8', 'application/x-www-form-urlencoded']) {
      const response = await handleRumPost(
        post(valid, { headers: { 'content-type': contentType } }),
        build(),
      );
      expect(response.status).toBe(415);
    }
  });

  it('accepts a charset parameter on the media type', async () => {
    const response = await handleRumPost(
      post(valid, { headers: { 'content-type': 'application/json; charset=utf-8' } }),
      dependencies,
    );
    expect(response.status).toBe(204);
  });

  it('answers 413 to a declared length over the cap, without reading the body', async () => {
    const response = await handleRumPost(
      post(valid, { headers: { 'content-length': String(MAX_BODY_BYTES + 1) } }),
      dependencies,
    );
    expect(response.status).toBe(413);
  });

  it('answers 413 to a body that is oversized however it was declared', async () => {
    const oversized = JSON.stringify({ ...valid, route: 'x'.repeat(MAX_BODY_BYTES) });
    const response = await handleRumPost(post(oversized), dependencies);
    expect(response.status).toBe(413);
  });

  /*
   * `String.length` counts characters. A body of 8,000 astral-plane characters is
   * 32,000 bytes and would have been called small.
   */
  it('measures the body in bytes and not in characters', async () => {
    const response = await handleRumPost(post('🙂'.repeat(2_100)), dependencies);
    expect(response.status).toBe(413);
  });

  it.each([
    ['not JSON at all', 'nonsense'],
    ['a JSON value that is not an object', '[]'],
    ['a field nobody reviewed', JSON.stringify({ ...valid, referrer: 'https://news.example' })],
    ['the retired FID', JSON.stringify({ ...valid, samples: [{ name: 'FID', value: 1, navigationType: 'navigate' }] })],
    ['a route that is a URL', JSON.stringify({ ...valid, route: '/[locale]/discover?q=watches' })],
    ['an implausible value', JSON.stringify({ ...valid, samples: [{ name: 'LCP', value: 1e308, navigationType: 'navigate' }] })],
  ])('answers 400 to %s', async (_description, body) => {
    const response = await handleRumPost(post(body), dependencies);

    expect(response.status).toBe(400);
    expect(lines).toHaveLength(0);
    expect(sink.size()).toBe(0);
  });

  it('names the reason without quoting the input back', async () => {
    const response = await handleRumPost(
      post({ ...valid, route: 'aygun@example.az' }),
      dependencies,
    );

    const problem = await response.json();
    expect(problem).toEqual({ error: 'malformed-field' });
    expect(JSON.stringify(problem)).not.toContain('aygun');
  });
});

describe('rate limiting', () => {
  it('refuses a caller that has spent its allowance, and says when to come back', async () => {
    const dependenciesWithTinyLimit = build({
      perCaller: new SlidingWindowRateLimiter({ limit: 2, windowMs: 60_000 }),
    });
    const caller = { headers: { 'x-forwarded-for': '203.0.113.7' } };

    expect((await handleRumPost(post(valid, caller), dependenciesWithTinyLimit)).status).toBe(204);
    expect((await handleRumPost(post(valid, caller), dependenciesWithTinyLimit)).status).toBe(204);

    const refused = await handleRumPost(post(valid, caller), dependenciesWithTinyLimit);
    expect(refused.status).toBe(429);
    expect(Number(refused.headers.get('retry-after'))).toBeGreaterThan(0);
    expect(refused.headers.get('X-RateLimit-Limit')).toBe('2');
    expect(refused.headers.get('X-RateLimit-Remaining')).toBe('0');
  });

  it('tells a caller where it stands before it is refused', async () => {
    const response = await handleRumPost(post(valid), dependencies);
    expect(response.headers.get('X-RateLimit-Limit')).toBe('30');
    expect(response.headers.get('X-RateLimit-Remaining')).toBe('29');
  });

  it('counts callers separately', async () => {
    const limited = build({
      perCaller: new SlidingWindowRateLimiter({ limit: 1, windowMs: 60_000 }),
    });

    const first = { headers: { 'x-forwarded-for': '203.0.113.7' } };
    const second = { headers: { 'x-forwarded-for': '203.0.113.8' } };

    expect((await handleRumPost(post(valid, first), limited)).status).toBe(204);
    expect((await handleRumPost(post(valid, first), limited)).status).toBe(429);
    expect((await handleRumPost(post(valid, second), limited)).status).toBe(204);
  });

  /*
   * The bucket is spoofable — a Next route handler cannot see the peer address,
   * so `X-Forwarded-For` is all there is, and a caller can invent one per
   * request. The global limiter is the one that actually holds: the worst a
   * rotating flood achieves is to spend the endpoint's own budget.
   */
  it('holds even against a caller inventing a new address every time', async () => {
    const limited = build({
      global: new SlidingWindowRateLimiter({ limit: 3, windowMs: 60_000 }),
    });

    for (let index = 0; index < 3; index += 1) {
      const response = await handleRumPost(
        post(valid, { headers: { 'x-forwarded-for': `203.0.113.${index}` } }),
        limited,
      );
      expect(response.status).toBe(204);
    }

    const flooded = await handleRumPost(
      post(valid, { headers: { 'x-forwarded-for': '198.51.100.1' } }),
      limited,
    );
    expect(flooded.status).toBe(429);
  });

  // Refusing for volume should not be costing a JSON parse per refusal.
  it('refuses for volume before it parses anything', async () => {
    const limited = build({
      perCaller: new SlidingWindowRateLimiter({ limit: 0, windowMs: 60_000 }),
    });

    const response = await handleRumPost(post('nonsense'), limited);
    expect(response.status).toBe(429);
  });

  it('never stores the address it counted', async () => {
    await handleRumPost(post(valid, { headers: { 'x-forwarded-for': '203.0.113.7' } }), dependencies);
    expect(lines.join('\n')).not.toContain('203.0.113.7');
  });
});

describe('GET', () => {
  it('answers the p75 table when the local sink is on', async () => {
    for (const value of [1000, 2000, 3000, 9000]) {
      await handleRumPost(
        post({ ...valid, samples: [{ name: 'LCP', value, navigationType: 'navigate' }] }),
        dependencies,
      );
    }

    const response = handleRumGet(dependencies);
    const table = await response.text();

    expect(response.status).toBe(200);
    expect(table).toContain('`/[locale]/discover`');
    expect(table).toContain('3000 ms (needs-improvement)');
  });

  /*
   * 404 and not 403: a public endpoint should not confirm there is something
   * there to be turned on.
   */
  it('does not exist when the local sink is off', () => {
    expect(handleRumGet(build({ sink: null })).status).toBe(404);
  });
});

describe('localSinkEnabled', () => {
  it('is on outside production and off inside it', () => {
    expect(localSinkEnabled({ NODE_ENV: 'development' })).toBe(true);
    expect(localSinkEnabled({ NODE_ENV: 'test' })).toBe(true);
    expect(localSinkEnabled({ NODE_ENV: 'production' })).toBe(false);
    expect(localSinkEnabled({})).toBe(true);
  });

  it('can be forced either way, because `next start` is production on a laptop too', () => {
    expect(localSinkEnabled({ NODE_ENV: 'production', IDEANEST_RUM_LOCAL_SINK: 'true' })).toBe(true);
    expect(localSinkEnabled({ NODE_ENV: 'development', IDEANEST_RUM_LOCAL_SINK: 'false' })).toBe(
      false,
    );
  });
});
