/**
 * Compares the First Load JS of every route against `budgets.json` and exits
 * non-zero when one of them no longer fits.
 *
 * Run it after `pnpm --filter @ideanest/web build`:
 *
 *     node apps/web/performance/check-first-load-js.mjs
 *
 * No dependencies, deliberately. This runs on a pull request from a fork, so
 * everything it needs has to already be on disk after the build.
 */

import { readFileSync, statSync, appendFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const appDir = resolve(here, '..');

/**
 * `next build` under Turbopack no longer prints the Size / First Load JS
 * columns, and Next 16 emits no `app-build-manifest.json` either, so there is
 * nothing to scrape. This file is the replacement: Next's own per-route
 * accounting, written to disk on every build. Reading it is exact where
 * parsing a formatted table was a guess about column widths.
 */
const STATS = join(appDir, '.next', 'diagnostics', 'route-bundle-stats.json');
const BUDGETS = join(here, 'budgets.json');

const KIB = 1024;

/** @returns {never} */
function die(message) {
  process.stderr.write(`${message}\n`);
  process.exit(1);
}

let stats;
try {
  stats = JSON.parse(readFileSync(STATS, 'utf8'));
} catch {
  die(`No build output at ${STATS}.\nRun \`pnpm --filter @ideanest/web build\` first.`);
}

const budgets = JSON.parse(readFileSync(BUDGETS, 'utf8'));

/**
 * The stats file records chunk paths with the host's separator, and this is
 * checked on Windows as often as on the Linux runner.
 */
const toPosix = (p) => p.split('\\').join('/');

/**
 * The chunks every route loads. When a dependency lands in the shared graph,
 * all twelve route budgets break at once and none of them says why; this line
 * is what names the cause. It is measured on disk rather than taken from any
 * route's total, because "shared" is a property of the intersection and not of
 * whichever route happens to carry the least of its own code.
 */
const chunkSets = stats.map((route) => new Set(route.firstLoadChunkPaths.map(toPosix)));
const sharedChunks = chunkSets.length
  ? [...chunkSets[0]].filter((chunk) => chunkSets.every((set) => set.has(chunk)))
  : [];
const sharedBytes = sharedChunks.reduce(
  (total, chunk) => total + statSync(join(appDir, chunk)).size,
  0,
);

/** @type {{ label: string, actualKib: number, budgetKib: number }[]} */
const rows = [{ label: 'shared by all routes', actualKib: sharedBytes / KIB, budgetKib: budgets.sharedFirstLoadKib }];
for (const route of [...stats].sort((a, b) => a.route.localeCompare(b.route))) {
  const budgetKib = budgets.routes[route.route];
  rows.push({
    label: route.route,
    actualKib: route.firstLoadUncompressedJsBytes / KIB,
    budgetKib: budgetKib ?? Number.NaN,
  });
}

/** @type {{ label: string, message: string }[]} */
const failures = [];

for (const row of rows) {
  if (Number.isNaN(row.budgetKib)) {
    failures.push({
      label: row.label,
      message:
        `${row.label} has no budget. It ships ${row.actualKib.toFixed(1)} KiB of First Load JS; ` +
        `add it to apps/web/performance/budgets.json.`,
    });
    continue;
  }

  if (row.actualKib > row.budgetKib) {
    failures.push({
      label: row.label,
      message:
        `${row.label} is ${(row.actualKib - row.budgetKib).toFixed(1)} KiB over budget ` +
        `(${row.actualKib.toFixed(1)} KiB against a ceiling of ${row.budgetKib} KiB).`,
    });
    continue;
  }

  /**
   * A ceiling nobody ever lowers stops being a budget and becomes a record of
   * the worst the page has ever been. Failing on slack is what makes the
   * ratchet turn both ways: delete enough code and you are made to bank it.
   */
  const slackPercent = ((row.budgetKib - row.actualKib) / row.budgetKib) * 100;
  if (slackPercent > budgets.maxSlackPercent) {
    failures.push({
      label: row.label,
      message:
        `${row.label} is ${slackPercent.toFixed(1)}% under its ceiling, more than the ` +
        `${budgets.maxSlackPercent}% this budget file allows. Lower it to ` +
        `${Math.ceil(row.actualKib * 1.05)} KiB so the saving is kept.`,
    });
  }
}

for (const route of Object.keys(budgets.routes)) {
  if (!stats.some((entry) => entry.route === route)) {
    failures.push({
      label: route,
      message: `${route} has a budget but no longer exists in the build. Remove it from budgets.json.`,
    });
  }
}

const table = [
  '| Route | First Load JS | Budget | Headroom |',
  '|---|---:|---:|---:|',
  ...rows.map((row) => {
    const budget = Number.isNaN(row.budgetKib) ? '—' : `${row.budgetKib} KiB`;
    const headroom = Number.isNaN(row.budgetKib)
      ? 'no budget'
      : `${(row.budgetKib - row.actualKib).toFixed(1)} KiB`;
    return `| \`${row.label}\` | ${row.actualKib.toFixed(1)} KiB | ${budget} | ${headroom} |`;
  }),
].join('\n');

process.stdout.write(`${table}\n\n`);

/**
 * Uncompressed bytes, which is what Next records. Transfer size would be the
 * more honest number if it were stable, but gzip output shifts by a few bytes
 * across zlib versions, so a Node upgrade would move every budget at once and
 * the first fix anybody reaches for is to widen the budget.
 */
process.stdout.write('Sizes are uncompressed JavaScript, the unit `next build` records.\n');

if (process.env.GITHUB_STEP_SUMMARY) {
  appendFileSync(
    process.env.GITHUB_STEP_SUMMARY,
    `## First Load JS\n\n${table}\n\nUncompressed bytes. Budgets: \`apps/web/performance/budgets.json\`.\n\n`,
  );
}

if (failures.length === 0) {
  process.stdout.write(`\nAll ${rows.length} budgets hold.\n`);
  process.exit(0);
}

for (const failure of failures) {
  // `::error::` puts the line in the checks tab next to the job, so the reason
  // is visible without opening the log.
  process.stdout.write(`::error title=Performance budget::${failure.message}\n`);
}

process.stdout.write(
  `\n${failures.length} budget${failures.length === 1 ? '' : 's'} broken. ` +
    `apps/web/performance/README.md says what to do about it.\n`,
);
process.exit(1);
