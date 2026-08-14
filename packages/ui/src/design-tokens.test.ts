import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { colors } from '@ideanest/design-tokens';

/**
 * Guard rail for the rule stated in docs/ui-kit.md: every colour comes from the
 * token file. A literal hex inside a component means the palette has silently
 * grown a member nobody agreed to, and it will drift from the rest of the system.
 *
 * This test is the reason a designer can trust the token file is the whole truth.
 */

const SRC = join(import.meta.dirname, '.');

/** Hex colours that may legitimately appear in source. */
const ALLOWED = new Set<string>([
  // Referenced in an explanatory comment about a contrast failure.
  '#34D058',
  '#C6F432',
]);

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      walk(full, out);
    } else if (/\.(ts|tsx|css)$/.test(entry)) {
      out.push(full);
    }
  }
  return out;
}

describe('colour discipline', () => {
  const files = walk(SRC).filter(
    (f) => !f.endsWith('design-tokens.test.ts') && !f.includes('sample-data'),
  );

  it('finds source files to scan', () => {
    expect(files.length).toBeGreaterThan(10);
  });

  it.each(files.map((f) => [relative(SRC, f), f]))(
    'has no unapproved hex literal: %s',
    (_label, file) => {
      const source = readFileSync(file, 'utf8');
      const found: string[] = source.match(/#[0-9a-fA-F]{3,8}\b/g) ?? [];
      const offenders = found
        .map((h: string) => h.toUpperCase())
        .filter((h: string) => !ALLOWED.has(h));

      expect(
        offenders,
        `Use a token from @ideanest/design-tokens instead of a literal colour. ` +
          `See docs/ui-kit.md §2.`,
      ).toEqual([]);
    },
  );
});

describe('token contract', () => {
  it('keeps the documented contrast pairings intact', () => {
    // Near-black on lime is the only legible pairing; see docs/ui-kit.md §9.1.
    expect(colors.textOnLime).toBe('#0A0A0A');
    expect(colors.lime500).toBe('#C6F432');
  });

  it('keeps lime and success distinct', () => {
    // Conflating them tells backers "achieved" when the system means "hurry".
    expect(colors.lime500).not.toBe(colors.success);
  });

  it('does not use pure black as the page surface', () => {
    // Pure black smears during scroll on OLED panels.
    expect(colors.surface1).not.toBe('#000000');
  });
});
