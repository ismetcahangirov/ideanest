/**
 * @vitest-environment node
 *
 * This file reads a source file and compares numbers. Spinning up jsdom for it
 * would cost more than every assertion in it put together, and the suite is
 * already wide enough that the marginal worker matters.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The subset list in `layout.tsx` is the only thing standing between this
 * product and a row of tofu boxes where a creator's name should be.
 *
 * It reads the layout's source rather than importing it, because importing the
 * module executes `next/font/google`, which reaches the network at build time
 * and is not something a unit test should depend on. What matters here is the
 * literal that was written down, and the source is where that literal is.
 *
 * Resolved from `process.cwd()` — vitest's root is this package — rather than
 * from `import.meta.url`, which under the jsdom environment is not a `file:`
 * URL and cannot be turned into a path.
 */
const layoutSource = readFileSync(resolve(process.cwd(), 'src/app/layout.tsx'), 'utf8');

/**
 * The `unicode-range` Google Fonts serves for each cut, copied from the
 * stylesheet at `fonts.googleapis.com/css2?family=Inter` on 2026-08-18.
 *
 * They are recorded rather than fetched for the same reason the test does not
 * import the layout: a unit test that fails when a CDN is slow teaches everyone
 * to ignore it. If Google ever narrows a range this test will go on passing and
 * the check has to be redone by hand — that is the trade, and it is the right
 * way round, because the failure this guards against is somebody editing the
 * list below, not Google redrawing its subsets.
 */
const SUBSET_RANGES: Record<string, ReadonlyArray<readonly [number, number]>> = {
  latin: [
    [0x0000, 0x00ff],
    [0x0131, 0x0131],
    [0x0152, 0x0153],
    [0x02bb, 0x02bc],
    [0x02c6, 0x02c6],
    [0x02da, 0x02da],
    [0x02dc, 0x02dc],
    [0x2000, 0x206f],
  ],
  'latin-ext': [
    [0x0100, 0x02ba],
    [0x02bd, 0x02c5],
    [0x02c7, 0x02cc],
    [0x02ce, 0x02d7],
    [0x02dd, 0x02ff],
    [0x1e00, 0x1e9f],
  ],
};

/**
 * Every character Azerbaijani adds to the Latin alphabet, in both cases. `ə` is
 * the one to watch: it is the most common letter in the language and the one a
 * Latin-only subset drops.
 */
const AZERBAIJANI = [...'əğıöşüçƏĞİÖŞÜÇ'];

/** The subsets actually passed to `Inter()` in the root layout. */
function declaredSubsets(): string[] {
  const match = /subsets:\s*\[([^\]]*)\]/.exec(layoutSource);
  if (!match?.[1]) throw new Error('No `subsets:` array found in layout.tsx');
  return [...match[1].matchAll(/'([^']+)'/g)].map((m) => m[1] as string);
}

function covers(subsets: readonly string[], codePoint: number): boolean {
  return subsets.some((subset) =>
    SUBSET_RANGES[subset]?.some(([from, to]) => codePoint >= from && codePoint <= to),
  );
}

describe('Inter subsets', () => {
  it('declares every subset it needs and no more', () => {
    expect(declaredSubsets().sort()).toEqual(['latin', 'latin-ext']);
  });

  it.each(AZERBAIJANI.map((character) => [character, character.codePointAt(0) as number]))(
    'covers %s (U+%s)',
    (character, codePoint) => {
      expect(
        covers(declaredSubsets(), codePoint),
        `${character} is not in any declared subset`,
      ).toBe(true);
    },
  );

  /**
   * Preloading a cut nothing is written in spends bandwidth the largest
   * contentful paint needs. Cyrillic is the tempting one — Azerbaijani had a
   * Cyrillic orthography until 1991 and it is not what anyone types today.
   */
  it('declares none of the cuts this product never renders', () => {
    expect(declaredSubsets()).not.toContain('cyrillic');
    expect(declaredSubsets()).not.toContain('cyrillic-ext');
    expect(declaredSubsets()).not.toContain('greek');
    expect(declaredSubsets()).not.toContain('greek-ext');
    expect(declaredSubsets()).not.toContain('vietnamese');
  });
});
