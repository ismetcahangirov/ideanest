/**
 * The five field metrics, and the boundaries they are judged against.
 *
 * <h2>Why these five</h2>
 *
 * LCP, INP and CLS are Core Web Vitals. TTFB and FCP are not, and are collected
 * anyway because they are the two that say *where* a bad LCP came from: an LCP
 * of four seconds behind a TTFB of two is a server problem, and the same LCP
 * behind a TTFB of 100 ms is a client one. Without them the only honest response
 * to a regression is to go and measure again.
 *
 * **FID is absent and must stay absent.** Google retired it in September 2024
 * and INP replaced it. `useReportWebVitals` still subscribes to `onFID` — see
 * `next/dist/client/web-vitals.js` — so a FID sample *will* arrive at the
 * reporter on every page load, and it is dropped there rather than recorded.
 * Keeping it would mean a dashboard with two interaction metrics on it, one of
 * which measures only the first interaction and none of the slow ones.
 *
 * <h2>Where the numbers came from</h2>
 *
 * Google's published good / needs-improvement boundaries, so that a row here
 * means what the same row means in Search Console — and, more locally, so that
 * this table and `apps/web/performance/summarise-lighthouse.mjs` cannot drift
 * apart. `metrics.test.ts` reads that file and fails if LCP, CLS or FCP stop
 * agreeing. The lab summariser deliberately carries no INP (a headless load
 * performs no interaction, so no lab tool can report one) and reports Total
 * Blocking Time as the proxy Google recommends instead; INP's own 200/500
 * boundaries therefore appear here and nowhere else, and TBT's 200/600 appear
 * there and nowhere here. The two halves measure different things and each
 * carries the thresholds of the thing it measures.
 *
 * `docs/architecture.md` names no client-side performance budget of its own —
 * §20.4's targets are API latency — so nothing here contradicts the
 * specification, and nothing here was invented in place of it.
 */

/** The metrics this application records from real sessions. */
export const FIELD_METRICS = ['LCP', 'INP', 'CLS', 'TTFB', 'FCP'] as const;

export type FieldMetricName = (typeof FIELD_METRICS)[number];

export type Rating = 'good' | 'needs-improvement' | 'poor';

export interface MetricThreshold {
  /** At or below this, the metric is `good`. */
  readonly good: number;
  /** Above this, the metric is `poor`. Between the two, `needs-improvement`. */
  readonly poor: number;
  /** `ms`, or the empty string for the unitless CLS. */
  readonly unit: 'ms' | '';
  /**
   * A bound on what a forged payload may write into a percentile, NOT a budget.
   * A real LCP of ten minutes does not exist; a POST claiming one does, and one
   * accepted sample of `1e308` would move every p75 that route ever reports.
   */
  readonly implausibleAbove: number;
}

export const THRESHOLDS: Record<FieldMetricName, MetricThreshold> = {
  LCP: { good: 2500, poor: 4000, unit: 'ms', implausibleAbove: 600_000 },
  INP: { good: 200, poor: 500, unit: 'ms', implausibleAbove: 600_000 },
  CLS: { good: 0.1, poor: 0.25, unit: '', implausibleAbove: 100 },
  TTFB: { good: 800, poor: 1800, unit: 'ms', implausibleAbove: 600_000 },
  FCP: { good: 1800, poor: 3000, unit: 'ms', implausibleAbove: 600_000 },
};

export function isFieldMetricName(candidate: string): candidate is FieldMetricName {
  return (FIELD_METRICS as readonly string[]).includes(candidate);
}

/** Google's rating for one observation. */
export function rate(name: FieldMetricName, value: number): Rating {
  const threshold = THRESHOLDS[name];
  if (value <= threshold.good) return 'good';
  if (value <= threshold.poor) return 'needs-improvement';
  return 'poor';
}

/**
 * Whether a number could have been measured on a real page.
 *
 * Finite, not negative, and under the metric's ceiling. `Number.isFinite`
 * rejects `NaN` and both infinities, which is what `JSON.parse` produces from
 * nothing but is what arithmetic on a hand-written payload can.
 */
export function isPlausibleValue(name: FieldMetricName, value: number): boolean {
  return Number.isFinite(value) && value >= 0 && value <= THRESHOLDS[name].implausibleAbove;
}

/** `1822 ms`, or `0.043` for CLS. For a summary a person reads. */
export function formatValue(name: FieldMetricName, value: number): string {
  return THRESHOLDS[name].unit === 'ms' ? `${Math.round(value)} ms` : value.toFixed(3);
}
