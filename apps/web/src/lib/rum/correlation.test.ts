import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  LONGEST_ACCEPTED_HEADER,
  acceptableIdentifier,
  isSessionId,
  isSpanId,
  isTraceId,
  newRequestId,
  newSpanId,
  newTraceId,
  traceIdFrom,
  traceparentOf,
} from './correlation';

/**
 * The Java is the authority. An identifier this file mints and that file
 * rejects still ends up in a log, joins to nothing, and is discovered only by
 * somebody trying to use it.
 */
const correlationJava = readFileSync(
  resolve(process.cwd(), '../api/src/main/java/az/ideanest/shared/observability/Correlation.java'),
  'utf8',
);

describe('the shapes copied from Correlation.java', () => {
  it('matches the identifier pattern the service validates against', () => {
    expect(correlationJava).toContain('[A-Za-z0-9_-]{8,64}');
  });

  it('matches the traceparent pattern the service validates against', () => {
    expect(correlationJava).toContain(
      '^(?!ff)[0-9a-f]{2}-(?!0{32})([0-9a-f]{32})-(?!0{16})([0-9a-f]{16})-[0-9a-f]{2}(?:-.*)?$',
    );
  });

  it('uses the same header names and the same length cap', () => {
    expect(correlationJava).toContain('"X-Request-Id"');
    expect(correlationJava).toContain('"X-Trace-Id"');
    expect(correlationJava).toContain('"traceparent"');
    expect(correlationJava).toContain('LONGEST_ACCEPTED_HEADER = 256');
    expect(LONGEST_ACCEPTED_HEADER).toBe(256);
  });
});

describe('acceptableIdentifier', () => {
  it('accepts what the service accepts', () => {
    expect(acceptableIdentifier('019432f1-2c4a-7bb1-9f7e-0f21b7c9a4d2')).toBe(
      '019432f1-2c4a-7bb1-9f7e-0f21b7c9a4d2',
    );
    expect(acceptableIdentifier('abcd1234')).toBe('abcd1234');
  });

  /*
   * An inbound identifier ends up on every line of the request. A newline forges
   * a log entry; a megabyte forges an outage.
   */
  it('refuses what would forge a log line', () => {
    expect(acceptableIdentifier('short')).toBeNull();
    expect(acceptableIdentifier('a'.repeat(65))).toBeNull();
    expect(acceptableIdentifier('a'.repeat(300))).toBeNull();
    expect(acceptableIdentifier('has space')).toBeNull();
    expect(acceptableIdentifier('line\nbreak')).toBeNull();
    expect(acceptableIdentifier('semi;colon')).toBeNull();
    expect(acceptableIdentifier(null)).toBeNull();
    expect(acceptableIdentifier(undefined)).toBeNull();
  });
});

describe('traceIdFrom', () => {
  const trace = '4bf92f3577b34da6a3ce929d0e0e4736';
  const span = '00f067aa0ba902b7';

  it('reads the trace out of a valid traceparent', () => {
    expect(traceIdFrom(`00-${trace}-${span}-01`)).toBe(trace);
    expect(traceIdFrom(`00-${trace.toUpperCase()}-${span}-01`)).toBe(trace);
    // A participant that does not understand a newer version reads what it needs
    // and ignores the rest, which is what the specification asks of it.
    expect(traceIdFrom(`01-${trace}-${span}-01-extra`)).toBe(trace);
  });

  it('refuses the invalid forms the specification names', () => {
    expect(traceIdFrom(`ff-${trace}-${span}-01`)).toBeNull();
    expect(traceIdFrom(`00-${'0'.repeat(32)}-${span}-01`)).toBeNull();
    expect(traceIdFrom(`00-${trace}-${'0'.repeat(16)}-01`)).toBeNull();
    expect(traceIdFrom('nonsense')).toBeNull();
    expect(traceIdFrom(null)).toBeNull();
    expect(traceIdFrom('0'.repeat(400))).toBeNull();
  });

  it('round-trips what traceparentOf builds', () => {
    const built = traceparentOf(trace, span);
    expect(built).toBe(`00-${trace}-${span}-01`);
    expect(traceIdFrom(built)).toBe(trace);
  });
});

describe('minting', () => {
  it('mints identifiers the service would accept', () => {
    for (let attempt = 0; attempt < 200; attempt += 1) {
      const traceId = newTraceId();
      const spanId = newSpanId();
      expect(isTraceId(traceId)).toBe(true);
      expect(isSpanId(spanId)).toBe(true);
      expect(traceIdFrom(traceparentOf(traceId, spanId))).toBe(traceId);
      expect(acceptableIdentifier(newRequestId())).not.toBeNull();
    }
  });

  it('never mints the all-zero trace or span', () => {
    const zeros = {
      getRandomValues: (array: Uint8Array): Uint8Array => array.fill(0),
    } as unknown as Pick<Crypto, 'getRandomValues'>;
    expect(isTraceId(newTraceId(zeros))).toBe(true);
    expect(isSpanId(newSpanId(zeros))).toBe(true);
    expect(newTraceId(zeros)).not.toBe('0'.repeat(32));
    expect(newSpanId(zeros)).not.toBe('0'.repeat(16));
  });
});

describe('isSessionId', () => {
  it('accepts only what crypto.randomUUID produces', () => {
    expect(isSessionId(crypto.randomUUID())).toBe(true);
    expect(isSessionId('0f4b7a2c-1d3e-4f5a-8b9c-0d1e2f3a4b5c')).toBe(true);
    // Not v4.
    expect(isSessionId('019432f1-2c4a-7bb1-9f7e-0f21b7c9a4d2')).toBe(false);
    expect(isSessionId('chosen-by-somebody')).toBe(false);
    expect(isSessionId('')).toBe(false);
  });
});
