import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  FIELD_METRICS,
  THRESHOLDS,
  formatValue,
  isFieldMetricName,
  isPlausibleValue,
  rate,
} from './metrics';

/**
 * Resolved from `process.cwd()` — vitest's root is this package — rather than
 * from `import.meta.url`, which under the jsdom environment is not a `file:`
 * URL. `src/app/font-subsets.test.ts` reads `layout.tsx` the same way.
 */
const labSummariser = readFileSync(
  resolve(process.cwd(), 'performance/summarise-lighthouse.mjs'),
  'utf8',
);

/**
 * The lab half of the measurement already publishes thresholds, and a reader
 * comparing a field p75 against a Lighthouse median has to be comparing them
 * against the same boundary or the comparison is theatre. These read the numbers
 * out of the other file rather than restating them, so the two cannot be edited
 * apart.
 */
describe('the thresholds the lab half already uses', () => {
  it.each([
    ['largest-contentful-paint', 'LCP'],
    ['cumulative-layout-shift', 'CLS'],
    ['first-contentful-paint', 'FCP'],
  ] as const)('agrees with summarise-lighthouse.mjs for %s', (id, name) => {
    const line = labSummariser
      .split('\n')
      .find((candidate) => candidate.includes(`id: '${id}'`));
    expect(line, `no ${id} row in summarise-lighthouse.mjs`).toBeDefined();

    const good = Number(/good:\s*([\d.]+)/.exec(line ?? '')?.[1]);
    const poor = Number(/poor:\s*([\d.]+)/.exec(line ?? '')?.[1]);

    expect(THRESHOLDS[name].good).toBe(good);
    expect(THRESHOLDS[name].poor).toBe(poor);
  });

  /*
   * TBT is the lab proxy Google recommends where INP cannot be measured, and it
   * has its own boundaries — 200/600 rather than INP's 200/500. Copying one onto
   * the other is the mistake this asserts against.
   */
  it('does not borrow the lab TBT boundary for INP', () => {
    expect(labSummariser).toContain('total-blocking-time');
    expect(THRESHOLDS.INP).toMatchObject({ good: 200, poor: 500 });
  });
});

describe('the metric vocabulary', () => {
  it('is the five field metrics and does not include the retired FID', () => {
    expect([...FIELD_METRICS]).toEqual(['LCP', 'INP', 'CLS', 'TTFB', 'FCP']);
    expect(isFieldMetricName('FID')).toBe(false);
    expect(isFieldMetricName('INP')).toBe(true);
    // `useReportWebVitals` also emits these; neither is a field metric here.
    expect(isFieldMetricName('Next.js-hydration')).toBe(false);
    expect(isFieldMetricName('')).toBe(false);
  });
});

describe('rate', () => {
  it.each([
    ['LCP', 2500, 'good'],
    ['LCP', 2500.01, 'needs-improvement'],
    ['LCP', 4000, 'needs-improvement'],
    ['LCP', 4000.01, 'poor'],
    ['CLS', 0.1, 'good'],
    ['CLS', 0.25, 'needs-improvement'],
    ['CLS', 0.26, 'poor'],
    ['INP', 200, 'good'],
    ['INP', 500, 'needs-improvement'],
    ['INP', 501, 'poor'],
    ['TTFB', 800, 'good'],
    ['TTFB', 1800, 'needs-improvement'],
    ['TTFB', 1801, 'poor'],
  ] as const)('rates %s of %s as %s', (name, value, expected) => {
    expect(rate(name, value)).toBe(expected);
  });

  // The boundary is inclusive on the good side, which is how Google states it.
  it('treats the boundary itself as the better rating', () => {
    expect(rate('FCP', THRESHOLDS.FCP.good)).toBe('good');
    expect(rate('FCP', THRESHOLDS.FCP.poor)).toBe('needs-improvement');
  });
});

describe('isPlausibleValue', () => {
  it('accepts a real measurement', () => {
    expect(isPlausibleValue('LCP', 0)).toBe(true);
    expect(isPlausibleValue('LCP', 1822)).toBe(true);
    expect(isPlausibleValue('CLS', 0.043)).toBe(true);
  });

  /*
   * One accepted sample of 1e308 would move every p75 that route ever reports,
   * and nothing about the payload is authenticated.
   */
  it('refuses what a forged payload would carry', () => {
    expect(isPlausibleValue('LCP', -1)).toBe(false);
    expect(isPlausibleValue('LCP', Number.MAX_VALUE)).toBe(false);
    expect(isPlausibleValue('LCP', Number.POSITIVE_INFINITY)).toBe(false);
    expect(isPlausibleValue('LCP', Number.NaN)).toBe(false);
    expect(isPlausibleValue('CLS', 101)).toBe(false);
  });
});

describe('formatValue', () => {
  it('prints milliseconds rounded and CLS to three places', () => {
    expect(formatValue('LCP', 1822.6)).toBe('1823 ms');
    expect(formatValue('CLS', 0.0431)).toBe('0.043');
  });
});
