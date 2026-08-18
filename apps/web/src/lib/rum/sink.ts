import { rate, type FieldMetricName, type Rating } from './metrics';
import type { RumPayload } from './payload';
import type { Observation } from './summary';

/**
 * Where a measurement goes once it has been accepted, and the honest answer to
 * "and then what".
 *
 * <h2>There is no analytics vendor here, and that is a decision, not an omission</h2>
 *
 * The obvious thing to do with field data is to post it to a third party who
 * will draw the graphs. Doing that would put every visitor's page views through
 * somebody else's servers, add a processor to whatever §17.4 and §22 have to say
 * about it, and commit the platform to a contract — and none of that is a
 * frontend implementation detail to be settled inside a pull request about
 * instrumentation. `docs/architecture.md` §14.2 lists "Analytics — product
 * analytics with feature flags" as a *choice not yet made*, and §14.4 already
 * names the monitoring stack the platform expects to run: Prometheus, Grafana,
 * Loki.
 *
 * So this writes **one structured JSON line per sample to stdout**, using
 * §18.1's field names, and stops there. In every deployed environment that line
 * is collected by whatever collects the application's other lines — Loki, per
 * §14.4 — and the p75 is a query rather than a product. Locally it is a line in
 * the terminal and a table at `GET /api/rum`.
 *
 * The shape of a production sink is therefore already fixed, and is written down
 * in `docs/observability/real-user-monitoring.md`: something that reads
 * `event=rum.metric` lines and aggregates a nearest-rank p75 per route, per
 * metric, over a rolling window. What it must *not* be is a decision made by
 * whoever needs a dashboard on a Thursday.
 *
 * <h2>The in-memory buffer is for a developer's terminal and nothing else</h2>
 *
 * It is a ring, it is per process, and it is empty after a restart. It exists so
 * that `GET /api/rum` can answer "is anything arriving, and what does it say",
 * which is the question somebody actually has while working on a page. It is off
 * in production by default — see `IDEANEST_RUM_LOCAL_SINK` — because a public
 * endpoint returning aggregated performance data is a thing to turn on
 * deliberately, not to discover.
 */

/** One accepted sample, flattened for the log. */
export interface SinkRecord {
  readonly at: string;
  readonly requestId: string;
  readonly traceId: string;
  readonly spanId: string;
  readonly sessionId: string;
  readonly route: string;
  readonly metric: FieldMetricName;
  readonly value: number;
  readonly rating: Rating;
  readonly navigationType: string;
  readonly connection: string;
  readonly device: string;
}

/** The `event` every line carries, so that one query finds all of them. */
export const RUM_EVENT = 'rum.metric';

/**
 * A payload, exploded into one record per sample.
 *
 * The rating is computed here rather than read from the wire: `payload.ts`
 * explains why the client is not trusted with it. `at` is the server's clock for
 * the same reason.
 */
export function recordsFrom(payload: RumPayload, now: Date): SinkRecord[] {
  const at = now.toISOString();
  return payload.samples.map((sample) => ({
    at,
    requestId: payload.requestId,
    traceId: payload.traceId,
    spanId: payload.spanId,
    sessionId: payload.sessionId,
    route: payload.route,
    metric: sample.name,
    value: sample.value,
    rating: rate(sample.name, sample.value),
    navigationType: sample.navigationType,
    connection: payload.connection,
    device: payload.device,
  }));
}

/**
 * One line, JSON, with §18.1's `requestId`, `traceId` and `spanId` spelled the
 * way the service spells them — so a query written against the documentation
 * finds the field measurement and the server spans of the same trace together.
 */
export function logLine(record: SinkRecord): string {
  return JSON.stringify({ event: RUM_EVENT, ...record });
}

/**
 * The last few thousand records, for `GET /api/rum` in development.
 *
 * Five thousand is about a megabyte and covers a working session comfortably.
 * The oldest go first, which is right for a buffer whose only reader is asking
 * "what has just been arriving".
 */
export class LocalSink {
  readonly #capacity: number;
  #records: SinkRecord[] = [];

  constructor(capacity = 5_000) {
    this.#capacity = capacity;
  }

  accept(records: readonly SinkRecord[]): void {
    this.#records.push(...records);
    if (this.#records.length > this.#capacity) {
      this.#records = this.#records.slice(this.#records.length - this.#capacity);
    }
  }

  observations(): Observation[] {
    return this.#records.map((record) => ({
      route: record.route,
      name: record.metric,
      value: record.value,
    }));
  }

  size(): number {
    return this.#records.length;
  }
}

/** Whether the in-memory buffer and its `GET` are enabled. */
export const LOCAL_SINK_VARIABLE = 'IDEANEST_RUM_LOCAL_SINK';

/**
 * On outside production, and in production only when somebody says so.
 *
 * The default is the safe direction for a public endpoint; the override exists
 * because `next start` runs with `NODE_ENV=production` on a laptop as well as on
 * a server, and verifying the endpoint end to end means running exactly that.
 */
export function localSinkEnabled(env: Record<string, string | undefined>): boolean {
  const raw = env[LOCAL_SINK_VARIABLE]?.trim().toLowerCase();
  if (raw === 'true') return true;
  if (raw === 'false') return false;
  return env['NODE_ENV'] !== 'production';
}
