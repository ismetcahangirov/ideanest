/**
 * The browser half of `az.ideanest.shared.observability.Correlation`.
 *
 * §18.1 puts `requestId`, `traceId` and `spanId` on every line the service logs,
 * and accepts the first from `X-Request-Id` and the second from a W3C
 * `traceparent` when they are shaped acceptably. A field measurement that used a
 * vocabulary of its own would be a second set of identifiers that join to
 * nothing: the whole value of "this session's LCP was nine seconds" is being able
 * to open the trace of the requests that session made.
 *
 * **The shapes below are copied from the Java, deliberately and exactly.** An
 * identifier this file mints and that file rejects is worse than no identifier,
 * because the beacon still carries one and the log still prints it, and nobody
 * discovers it joins to nothing until they try. `correlation.test.ts` pins each
 * pattern against the Java source's own literal.
 *
 * <h2>Why the identifiers travel in the body and not in headers</h2>
 *
 * `navigator.sendBeacon` cannot set a request header. It takes a URL and a body,
 * and the only header it will emit is the `Content-Type` of the `Blob` it is
 * given. So the correlation identifiers are fields of the payload, and the
 * endpoint reads them from there. When the `fetch` fallback runs it sets
 * `X-Request-Id` and `traceparent` as well — same values, so a proxy log and the
 * application log agree — and the endpoint prefers the headers when it has them,
 * exactly as `CorrelationFilter` does.
 */

/** What the service reads a client's request identifier from. */
export const REQUEST_ID_HEADER = 'X-Request-Id';

/** What it returns its own on. */
export const TRACE_ID_HEADER = 'X-Trace-Id';

/** W3C Trace Context. Lower case, per the specification. */
export const TRACEPARENT_HEADER = 'traceparent';

/**
 * `Correlation.ACCEPTABLE_IDENTIFIER`. Long enough for a UUID with its hyphens,
 * short enough that the header cannot become the payload.
 */
const ACCEPTABLE_IDENTIFIER = /^[A-Za-z0-9_-]{8,64}$/;

/** `Correlation.TRACEPARENT`: `version-traceid-spanid-flags`. */
const TRACEPARENT =
  /^(?!ff)[0-9a-f]{2}-(?!0{32})([0-9a-f]{32})-(?!0{16})([0-9a-f]{16})-[0-9a-f]{2}(?:-.*)?$/;

/** `Correlation.LONGEST_ACCEPTED_HEADER`. A longer header is not parsed at all. */
export const LONGEST_ACCEPTED_HEADER = 256;

/** Thirty-two lower-case hex characters, and never the all-zero trace. */
const TRACE_ID = /^(?!0{32})[0-9a-f]{32}$/;

/** Sixteen, and never the all-zero span. */
const SPAN_ID = /^(?!0{16})[0-9a-f]{16}$/;

/**
 * `crypto.randomUUID()`'s output, and nothing looser.
 *
 * A session identifier is the sampling key and the join key, so it has to be
 * *some* stable string; making it a v4 UUID rather than "any acceptable
 * identifier" means it carries 122 bits of `crypto`-grade randomness and cannot
 * be anything a caller chose to put there — an attacker who could pick session
 * identifiers could pick ones that sample in and drown a route's percentile.
 */
const SESSION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/** The candidate if the service would accept it as a request identifier, else null. */
export function acceptableIdentifier(candidate: string | null | undefined): string | null {
  if (typeof candidate !== 'string' || candidate.length > LONGEST_ACCEPTED_HEADER) return null;
  return ACCEPTABLE_IDENTIFIER.test(candidate) ? candidate : null;
}

/** The caller's trace, if the header really is one, otherwise null. */
export function traceIdFrom(traceparent: string | null | undefined): string | null {
  if (typeof traceparent !== 'string' || traceparent.length > LONGEST_ACCEPTED_HEADER) return null;
  return TRACEPARENT.exec(traceparent.trim().toLowerCase())?.[1] ?? null;
}

export function isTraceId(candidate: string): boolean {
  return TRACE_ID.test(candidate);
}

export function isSpanId(candidate: string): boolean {
  return SPAN_ID.test(candidate);
}

export function isSessionId(candidate: string): boolean {
  return SESSION_ID.test(candidate);
}

/**
 * The `traceparent` a beacon or an API call announces itself with.
 *
 * Version `00`, and the sampled flag set — a trace we chose to report is one we
 * are asking the service to record spans for. The service continues the trace
 * and mints its own span, so the span here is the parent of whatever it does.
 */
export function traceparentOf(traceId: string, spanId: string): string {
  return `00-${traceId}-${spanId}-01`;
}

type RandomSource = Pick<Crypto, 'getRandomValues'>;

function randomHex(bytes: number, source: RandomSource): string {
  const buffer = new Uint8Array(bytes);
  source.getRandomValues(buffer);
  let hex = '';
  for (const byte of buffer) hex += byte.toString(16).padStart(2, '0');
  return hex;
}

/** Thirty-two lower-case hex characters. Never the all-zero trace. */
export function newTraceId(source: RandomSource = crypto): string {
  const candidate = randomHex(16, source);
  return TRACE_ID.test(candidate) ? candidate : `1${candidate.slice(1)}`;
}

/** Sixteen lower-case hex characters. Never the all-zero span. */
export function newSpanId(source: RandomSource = crypto): string {
  const candidate = randomHex(8, source);
  return SPAN_ID.test(candidate) ? candidate : `1${candidate.slice(1)}`;
}

/**
 * A request identifier for one beacon.
 *
 * A UUID, which `ACCEPTABLE_IDENTIFIER` admits — the service mints UUID v7 for
 * the same field and a browser has no v7 generator, but the shape the service
 * validates is the same and the sort order of a client-minted identifier buys
 * nothing on the server side anyway.
 */
export function newRequestId(random: () => string = () => crypto.randomUUID()): string {
  return random();
}
