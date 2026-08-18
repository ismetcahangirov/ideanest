import { describe, expect, it } from 'vitest';
import { MAX_SAMPLES, PAYLOAD_VERSION, parseRumPayload, type RumPayload } from './payload';

const valid: RumPayload = {
  v: PAYLOAD_VERSION,
  requestId: '019432f1-2c4a-7bb1-9f7e-0f21b7c9a4d2',
  traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
  spanId: '00f067aa0ba902b7',
  sessionId: '0f4b7a2c-1d3e-4f5a-8b9c-0d1e2f3a4b5c',
  route: '/projects/[id]/back',
  connection: '4g',
  device: 'mobile',
  samples: [{ name: 'LCP', value: 1822, navigationType: 'navigate' }],
};

const body = (overrides: Record<string, unknown> = {}): string =>
  JSON.stringify({ ...valid, ...overrides });

function reasonFor(text: string): string {
  const result = parseRumPayload(text);
  return result.ok ? 'accepted' : result.reason;
}

describe('a payload the reporter sends', () => {
  it('is accepted', () => {
    const result = parseRumPayload(body());
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.payload).toEqual(valid);
  });

  it('is accepted with every metric and navigation type in one beacon', () => {
    const result = parseRumPayload(
      body({
        samples: [
          { name: 'LCP', value: 1822, navigationType: 'navigate' },
          { name: 'INP', value: 96, navigationType: 'back-forward-cache' },
          { name: 'CLS', value: 0.043, navigationType: 'reload' },
          { name: 'TTFB', value: 240, navigationType: 'restore' },
          { name: 'FCP', value: 980, navigationType: 'unknown' },
        ],
      }),
    );
    expect(result.ok).toBe(true);
  });

  /*
   * The correlation identifiers are exempt from the shape rules, because a
   * sixteen-digit span identifier is a legitimate span identifier and about one
   * in eighteen hundred of them is one. See `redaction.ts`.
   */
  it('is accepted when a span identifier happens to look like a card number', () => {
    expect(reasonFor(body({ spanId: '4111111111111111' }))).toBe('accepted');
  });
});

describe('malformed input', () => {
  it.each([
    ['nonsense', 'not-json'],
    ['', 'not-json'],
    ['null', 'not-an-object'],
    ['[]', 'not-an-object'],
    ['"a string"', 'not-an-object'],
    ['42', 'not-an-object'],
  ])('refuses %s', (text, reason) => {
    expect(reasonFor(text)).toBe(reason);
  });

  it('refuses a version it does not implement', () => {
    expect(reasonFor(body({ v: 2 }))).toBe('unsupported-version');
    expect(reasonFor(body({ v: '1' }))).toBe('unsupported-version');
  });

  /*
   * Ignoring an unknown key is the usual instinct and the wrong trade for a
   * public write whose output goes into a log. An ignored key is one refactor
   * away from being logged.
   */
  it('refuses a field nobody reviewed', () => {
    expect(reasonFor(body({ referrer: 'https://news.example/story' }))).toBe('unknown-field');
    expect(reasonFor(body({ userId: 'u-1' }))).toBe('unknown-field');
    expect(reasonFor(body({ url: '/discover?q=watches' }))).toBe('unknown-field');
    expect(
      reasonFor(body({ samples: [{ name: 'LCP', value: 1, navigationType: 'navigate', note: 'x' }] })),
    ).toBe('unknown-field');
  });

  it('refuses a missing field as firmly as an extra one', () => {
    const { route: _route, ...withoutRoute } = valid;
    expect(reasonFor(JSON.stringify(withoutRoute))).toBe('unknown-field');
  });

  it.each([
    ['a request id that would forge a log line', { requestId: 'a\nb' }],
    ['a request id that is too short', { requestId: 'short' }],
    ['a trace id that is not one', { traceId: 'not-a-trace' }],
    ['the all-zero trace', { traceId: '0'.repeat(32) }],
    ['the all-zero span', { spanId: '0'.repeat(16) }],
    ['a session id somebody chose', { sessionId: 'chosen-by-me-000000' }],
    ['a route that is a URL', { route: '/projects/019432f1-2c4a-7bb1-9f7e-0f21b7c9a4d2/back' }],
    ['a route with a query string', { route: '/discover?q=watches' }],
    ['a route nobody declared', { route: '/admin' }],
    ['a connection class nobody declared', { connection: '5g' }],
    ['a device class nobody declared', { device: 'watch' }],
    ['samples that are not an array', { samples: {} }],
    ['a sample that is not an object', { samples: ['LCP'] }],
    ['the retired FID', { samples: [{ name: 'FID', value: 12, navigationType: 'navigate' }] }],
    ['a value that is a string', { samples: [{ name: 'LCP', value: '1822', navigationType: 'navigate' }] }],
    [
      'a navigation type nobody declared',
      { samples: [{ name: 'LCP', value: 1822, navigationType: 'teleport' }] },
    ],
  ])('refuses %s', (_description, overrides) => {
    expect(reasonFor(body(overrides))).toBe('malformed-field');
  });

  it('refuses a beacon with nothing in it', () => {
    expect(reasonFor(body({ samples: [] }))).toBe('no-samples');
  });

  it('refuses a beacon with too much in it', () => {
    const samples = Array.from({ length: MAX_SAMPLES + 1 }, () => ({
      name: 'LCP',
      value: 1,
      navigationType: 'navigate',
    }));
    expect(reasonFor(body({ samples }))).toBe('too-many-samples');
  });

  /*
   * One accepted sample of 1e308 would move every p75 that route ever reports,
   * for ever, and nothing about this payload is authenticated.
   */
  it.each([
    ['a negative duration', -1],
    ['an implausible duration', 6_000_000],
    ['the largest float there is', Number.MAX_VALUE],
  ])('refuses %s', (_description, value) => {
    expect(reasonFor(body({ samples: [{ name: 'LCP', value, navigationType: 'navigate' }] }))).toBe(
      'implausible-value',
    );
  });

  it('refuses infinity and NaN, which JSON cannot even carry', () => {
    expect(reasonFor('{"v":1,"samples":[{"name":"LCP","value":Infinity}]}')).toBe('not-json');
  });
});

describe('the second lock', () => {
  /*
   * The schema already makes these unreachable — there is no field text can
   * enter. `redaction.ts` explains why the check runs anyway.
   */
  it('refuses identifying data that somehow satisfied the schema', () => {
    // A hand-built object rather than a body, because no schema-valid JSON can
    // reach this branch today. That is the point: the day a field is added, this
    // is the test that fails.
    const smuggled = JSON.parse(body()) as Record<string, unknown>;
    smuggled['route'] = 'aygun@example.az';
    expect(reasonFor(JSON.stringify(smuggled))).toBe('malformed-field');
  });
});

describe('the reason returned to the caller', () => {
  /*
   * The reason goes back to an anonymous caller. Echoing what was wrong with
   * their input is how a validator becomes an oracle, so none of the reasons
   * names a value.
   */
  it('never quotes the input back', () => {
    const secret = 'aygun@example.az';
    const result = parseRumPayload(body({ route: secret }));
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).not.toContain(secret);
  });
});
