import {
  REQUEST_ID_HEADER,
  TRACE_ID_HEADER,
  TRACEPARENT_HEADER,
  acceptableIdentifier,
  newSpanId,
  traceIdFrom,
} from './correlation';
import { SlidingWindowRateLimiter, bucketKey, callerAddress, type RateLimitDecision } from './limits';
import { BEACON_CONTENT_TYPE } from './beacon';
import { MAX_BODY_BYTES, parseRumPayload, type RumPayload } from './payload';
import { LocalSink, logLine, recordsFrom } from './sink';
import { formatSummary, summarise } from './summary';

/**
 * `POST /api/rum`, as a function of its request.
 *
 * The route file is four lines that call this; everything worth testing is here,
 * where a test can hand it a `Request` and read the `Response` without a server.
 *
 * <h2>The refusals, and the order they happen in</h2>
 *
 * | Condition | Answer |
 * |---|---|
 * | Not `POST` | `405`, with `Allow` |
 * | Not `application/json` | `415` |
 * | `Content-Length` above the cap, or a body that turns out to be | `413` |
 * | Over the per-caller or global rate limit | `429`, with `Retry-After` |
 * | Anything `payload.ts` refuses | `400`, naming the reason and no value |
 * | Accepted | `204` |
 *
 * Cheapest first, and the rate limit before the parse: a caller being refused
 * for volume should not be costing a JSON parse per refusal, which is the whole
 * point of refusing them.
 *
 * `204` and not `200`: there is nothing to say, and a body would be bytes spent
 * on a response nobody reads — the client has usually stopped existing by the
 * time it arrives.
 *
 * <h2>Correlation</h2>
 *
 * The same rule `CorrelationFilter` applies. An inbound `X-Request-Id` is used
 * when it matches the shape the service accepts, and an inbound `traceparent`
 * supplies the trace when it parses; otherwise the payload's own fields are
 * used, which is the ordinary case because `navigator.sendBeacon` cannot set a
 * header. The span is always ours. Both identifiers come back on the response as
 * `X-Request-Id` and `X-Trace-Id`, so a person reporting a problem can quote one.
 *
 * <h2>What is deliberately not here</h2>
 *
 * No CORS headers, so the endpoint is same-origin only, which is what the
 * reporter is. No authentication, because the visitors whose experience is being
 * measured are mostly not signed in and requiring a session would measure only
 * the half of the audience that already got through the door.
 */

/** Per caller. Six metrics a page load, and a page load is not free. */
const PER_CALLER_LIMIT = 30;

/** Every caller together. See `limits.ts` for why the second limiter exists. */
const GLOBAL_LIMIT = 600;

const WINDOW_MS = 60_000;

export interface EndpointDependencies {
  readonly perCaller: SlidingWindowRateLimiter;
  readonly global: SlidingWindowRateLimiter;
  readonly salt: string;
  readonly sink: LocalSink | null;
  readonly now: () => Date;
  readonly log: (line: string) => void;
}

/** The dependencies a deployed process runs with. */
export function defaultDependencies(options: {
  readonly salt: string;
  readonly sink: LocalSink | null;
}): EndpointDependencies {
  return {
    perCaller: new SlidingWindowRateLimiter({ limit: PER_CALLER_LIMIT, windowMs: WINDOW_MS }),
    global: new SlidingWindowRateLimiter({ limit: GLOBAL_LIMIT, windowMs: WINDOW_MS }),
    salt: options.salt,
    sink: options.sink,
    now: () => new Date(),
    // eslint-disable-next-line no-console -- stdout is the sink; see sink.ts.
    log: (line) => console.log(line),
  };
}

export async function handleRumPost(
  request: Request,
  dependencies: EndpointDependencies,
): Promise<Response> {
  if (request.method !== 'POST') {
    return new Response(null, { status: 405, headers: { allow: 'POST' } });
  }

  const mediaType = (request.headers.get('content-type') ?? '').split(';')[0]?.trim().toLowerCase();
  if (mediaType !== BEACON_CONTENT_TYPE) {
    return new Response(null, { status: 415 });
  }

  /*
   * The declared length first, so an oversized body is refused before it is
   * read. A caller may lie about it or omit it, which is why the read below
   * checks the real size as well — but the ones that do not lie cost nothing.
   */
  const declared = Number(request.headers.get('content-length') ?? '');
  if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) {
    return new Response(null, { status: 413 });
  }

  const caller = bucketKey(callerAddress(request.headers), dependencies.salt);
  const perCaller = dependencies.perCaller.record(caller);
  const global = dependencies.global.record('*');
  // The tightest budget is the one reported. A caller told it had 500 left on
  // the request that is about to be refused has been told nothing useful.
  const decision = tightest(perCaller, global);
  if (!decision.allowed) {
    return new Response(null, {
      status: 429,
      headers: { ...rateLimitHeaders(decision), 'retry-after': String(decision.resetSeconds) },
    });
  }

  let body: string;
  try {
    body = await request.text();
  } catch {
    return new Response(null, { status: 400 });
  }

  /*
   * The real size, in bytes and not in characters. A body of 8,000 astral-plane
   * characters is 32,000 bytes, and `String.length` would have called it small.
   */
  if (new TextEncoder().encode(body).byteLength > MAX_BODY_BYTES) {
    return new Response(null, { status: 413 });
  }

  const result = parseRumPayload(body);
  if (!result.ok) {
    return json({ error: result.reason }, 400, rateLimitHeaders(decision));
  }

  const correlation = correlate(request, result.payload);
  const records = recordsFrom({ ...result.payload, ...correlation }, dependencies.now());
  for (const record of records) dependencies.log(logLine(record));
  dependencies.sink?.accept(records);

  return new Response(null, {
    status: 204,
    headers: {
      ...rateLimitHeaders(decision),
      [REQUEST_ID_HEADER]: correlation.requestId,
      [TRACE_ID_HEADER]: correlation.traceId,
    },
  });
}

/**
 * `GET /api/rum` — the p75 table, in development.
 *
 * `404` and not `403` when the sink is off, because a public endpoint should not
 * confirm that there is something there to be turned on.
 */
export function handleRumGet(dependencies: EndpointDependencies): Response {
  if (dependencies.sink === null) return new Response(null, { status: 404 });

  const rows = summarise(dependencies.sink.observations());
  return new Response(`${formatSummary(rows)}\n`, {
    status: 200,
    headers: { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' },
  });
}

/**
 * The identifiers this request is logged under.
 *
 * Header before body, because a header was set by the `fetch` fallback and a
 * body field could have been set by anything; both are validated with the
 * service's own rules before either is written to a line.
 */
function correlate(
  request: Request,
  payload: RumPayload,
): { requestId: string; traceId: string; spanId: string } {
  const headerRequestId = acceptableIdentifier(request.headers.get(REQUEST_ID_HEADER));
  const headerTraceId = traceIdFrom(request.headers.get(TRACEPARENT_HEADER));
  return {
    requestId: headerRequestId ?? payload.requestId,
    traceId: headerTraceId ?? payload.traceId,
    // Ours either way. Continuing the caller's trace does not mean adopting the
    // caller's span: the work done here is a new span whose parent is theirs.
    spanId: newSpanId(),
  };
}

function tightest(left: RateLimitDecision, right: RateLimitDecision): RateLimitDecision {
  if (left.allowed !== right.allowed) return left.allowed ? right : left;
  return left.remaining <= right.remaining ? left : right;
}

/**
 * §10.3's `X-RateLimit-*` trio, in seconds.
 *
 * The same three names and the same unit the service uses, so a client that
 * already understands the API's answer understands this one. The IETF draft's
 * `RateLimit` fields the service also sends are omitted here: nothing reads them
 * on this endpoint, whose only client sends and forgets.
 */
function rateLimitHeaders(decision: RateLimitDecision): Record<string, string> {
  return {
    'X-RateLimit-Limit': String(decision.limit),
    'X-RateLimit-Remaining': String(decision.remaining),
    'X-RateLimit-Reset': String(decision.resetSeconds),
  };
}

function json(body: unknown, status: number, headers: Record<string, string>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...headers, 'content-type': 'application/json; charset=utf-8' },
  });
}
