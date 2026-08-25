import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Nothing outside `src/i18n/` may reach for the raw navigation primitives — issue #123.
 *
 * <h2>Why this is a test and not a lint rule</h2>
 *
 * There is no ESLint in this repository — `pnpm lint` runs per-package scripts and
 * `apps/web` has none — so a comment saying "the linter catches this" would be a claim
 * about a check that does not run. The repository already enforces this kind of rule with
 * tests instead: `packages/ui` fails its suite on a hex literal, and
 * `lib/account/navigation.test.ts` fails when the rail and the module list disagree.
 *
 * <h2>What goes wrong without it</h2>
 *
 * `next/link` renders `<a href="/discover">`, with no language on it. `middleware.ts`
 * answers that with a redirect decided by a cookie, so a reader three pages into the Russian
 * site can be moved to Azerbaijani by clicking a navigation item — and it reproduces for
 * nobody whose cookie happens to agree with the page they are on, which is most people
 * during review. `usePathname` fails the other way and even more quietly: it returns
 * `/az/settings`, every `aria-current="page"` comparison against `/settings` stops matching,
 * and the site loses its "you are here" without anything throwing.
 *
 * Both are invisible in a screenshot. A grep is the only thing that reliably sees them.
 */
const APP_ROOT = join(process.cwd(), 'src');

/** The module this file is guarding, and the only place allowed to import the primitives. */
const EXEMPT = ['i18n'];

/**
 * `notFound` and `useSearchParams` are absent deliberately — neither takes or returns a path,
 * so neither can lose a language. Only the four that do are restricted.
 */
const RESTRICTED = ['useRouter', 'usePathname', 'redirect', 'permanentRedirect'] as const;

function sourceFiles(dir: string): string[] {
  const found: string[] = [];

  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      found.push(...sourceFiles(path));
      continue;
    }
    /* Tests are exempt: they mock these modules by name, which is the point of a mock. */
    if (/\.tsx?$/.test(entry) && !/\.test\.tsx?$/.test(entry)) found.push(path);
  }

  return found;
}

function isExempt(path: string): boolean {
  const segments = relative(APP_ROOT, path).split(sep);
  return EXEMPT.includes(segments[0] ?? '');
}

describe('the navigation primitives', () => {
  const files = sourceFiles(APP_ROOT).filter((path) => !isExempt(path));

  it('finds the application to check, so a broken walk cannot pass silently', () => {
    /* A guard that scans nothing passes forever. */
    expect(files.length).toBeGreaterThan(100);
  });

  it('are never imported from `next/link` outside `src/i18n`', () => {
    const offenders = files.filter((path) =>
      /from ['"]next\/link['"]/.test(readFileSync(path, 'utf8')),
    );

    expect(
      offenders.map((path) => relative(APP_ROOT, path)),
      'import { Link } from the i18n navigation module instead — a raw next/link drops the language',
    ).toEqual([]);
  });

  it.each(RESTRICTED)('never import `%s` from `next/navigation` outside `src/i18n`', (name) => {
    const pattern = new RegExp(`import \\{[^}]*\\b${name}\\b[^}]*\\} from ['"]next/navigation['"]`);
    const offenders = files.filter((path) => pattern.test(readFileSync(path, 'utf8')));

    expect(
      offenders.map((path) => relative(APP_ROOT, path)),
      `${name} must come from src/i18n — the version in next/navigation has no language on it`,
    ).toEqual([]);
  });

  it('allows an anchor that opts out, provided it prefixes the path itself', () => {
    /*
     * Three components use a full-document `<a>` on purpose, because signing in is a boundary
     * the client cache should not carry state across. They are legitimate, and each one calls
     * `localeHref` — this asserts that the exception stays paired with the fix rather than
     * becoming a way around the rule above.
     *
     * The match is on the anchor's own expression rather than on the file, and it only fires
     * for an href that is VISIBLY an application path: a literal beginning with `/`, or the
     * one helper that builds one. Anything else — `issueHref(module.issue)` in the console,
     * a campaign's own website, a `mailto:` — is somebody else's address and must be left
     * exactly as written. A cruder rule flagged the console's link to a GitHub issue.
     */
    const ANCHOR = /<a\s[^>]*?href=\{([^}]*(?:\{[^}]*\}[^}]*)*)\}/gs;
    const INTERNAL = /(?:['"`]\/(?!\/)|signInHref\()/;

    for (const path of files) {
      const source = readFileSync(path, 'utf8');

      for (const [, expression] of source.matchAll(ANCHOR)) {
        if (expression === undefined || !INTERNAL.test(expression)) continue;

        expect(
          expression,
          `${relative(APP_ROOT, path)} points an anchor at an application path without localeHref`,
        ).toContain('localeHref');
      }
    }
  });
});
