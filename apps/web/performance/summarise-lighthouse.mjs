/**
 * Reduces a directory of Lighthouse JSON reports to one advisory table.
 *
 *     node apps/web/performance/summarise-lighthouse.mjs <dir>
 *
 * It always exits zero. Lab Core Web Vitals on a shared CI runner move by more
 * between two runs of unchanged code than most regressions worth catching move
 * them, so gating on this number would fail pull requests for reasons nobody
 * wrote. The first response to a check like that is to delete it, and the
 * bundle budget next to it would go out with the bathwater. This reports; the
 * budget enforces.
 */

import { readdirSync, readFileSync, appendFileSync } from 'node:fs';
import { join } from 'node:path';

const dir = process.argv[2];
if (!dir) {
  process.stderr.write('Usage: summarise-lighthouse.mjs <directory of Lighthouse JSON reports>\n');
  process.exit(1);
}

/**
 * Thresholds are Google's published good/needs-improvement boundaries, not
 * numbers this repository chose, so that "amber" here means the same thing it
 * means in Search Console.
 *
 * INP is deliberately absent. It is defined over real user interactions and a
 * headless load performs none, so no lab tool reports it; Total Blocking Time
 * is the proxy Google recommends in its place, and calling it INP would be a
 * comfortable lie in a table people will read quickly.
 */
const METRICS = [
  { id: 'largest-contentful-paint', label: 'LCP', good: 2500, poor: 4000, unit: 'ms' },
  { id: 'cumulative-layout-shift', label: 'CLS', good: 0.1, poor: 0.25, unit: '' },
  { id: 'total-blocking-time', label: 'TBT (lab proxy for INP)', good: 200, poor: 600, unit: 'ms' },
  { id: 'first-contentful-paint', label: 'FCP', good: 1800, poor: 3000, unit: 'ms' },
];

const reports = readdirSync(dir)
  .filter((name) => name.endsWith('.json'))
  .map((name) => JSON.parse(readFileSync(join(dir, name), 'utf8')));

if (reports.length === 0) {
  process.stdout.write('::warning title=Lighthouse::No reports were produced; nothing to summarise.\n');
  process.exit(0);
}

/**
 * The median is taken per metric rather than by electing one representative
 * run. A representative run is the honest answer when the numbers are read
 * together as a trace; here they are read as four independent gauges, and a
 * single outlier run should not be allowed to set all four.
 */
const median = (values) => {
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
};

const rate = (metric, value) => {
  if (value <= metric.good) return 'good';
  if (value <= metric.poor) return 'needs work';
  return 'poor';
};

const format = (metric, value) =>
  metric.unit === 'ms' ? `${Math.round(value)} ms` : value.toFixed(3);

/** @type {Map<string, object[]>} */
const byUrl = new Map();
for (const report of reports) {
  const url = new URL(report.requestedUrl).pathname;
  byUrl.set(url, [...(byUrl.get(url) ?? []), report]);
}

const lines = [
  `| Route | Runs | ${METRICS.map((m) => m.label).join(' | ')} |`,
  `|---|---:|${METRICS.map(() => '---:').join('|')}|`,
];

for (const [url, group] of [...byUrl].sort(([a], [b]) => a.localeCompare(b))) {
  const cells = METRICS.map((metric) => {
    const values = group
      .map((report) => report.audits[metric.id]?.numericValue)
      .filter((value) => typeof value === 'number');
    if (values.length === 0) return 'n/a';
    const value = median(values);
    return `${format(metric, value)} (${rate(metric, value)})`;
  });
  lines.push(`| \`${url}\` | ${group.length} | ${cells.join(' | ')} |`);
}

const version = reports[0].lighthouseVersion;
const table = [
  `Median of the runs below, Lighthouse ${version} under its default mobile emulation`,
  '(4× CPU slowdown, throttled network). Advisory: these numbers do not fail the build.',
  '',
  ...lines,
  '',
  'INP is not listed because it cannot be measured without a real interaction.',
  'Thresholds are Google\'s published good/needs-improvement boundaries.',
].join('\n');

process.stdout.write(`${table}\n`);

if (process.env.GITHUB_STEP_SUMMARY) {
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## Lab Core Web Vitals\n\n${table}\n\n`);
}
