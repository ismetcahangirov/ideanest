import { describe, expect, it } from 'vitest';
import az from '../../../messages/az.json';
import en from '../../../messages/en.json';
import ru from '../../../messages/ru.json';
import tr from '../../../messages/tr.json';

/** All four, because a note missing from one of them is a module blocked for no stated reason. */
const CATALOGUES = [az, en, ru, tr];
import {
  CONSOLE_GROUPS,
  CONSOLE_MODULES,
  builtModuleCount,
  isCurrentConsoleLink,
  screensOf,
} from './navigation';

/**
 * The console's own contents page — §4.11, issue #294.
 *
 * <p>The first test here is the epic's definition of done, asserted rather than reviewed:
 * "every module in §4.11's table has either a screen or an open blocker naming what it
 * needs". The failure it prevents is a module quietly acquiring a `blocked` state with no
 * sentence beside it, which reads to a new member of staff as "coming soon" and to everybody
 * else as a to-do nobody wrote down.
 */
describe('the console modules', () => {
  it('covers §4.11 exactly once each', () => {
    expect(CONSOLE_MODULES).toHaveLength(16);

    const codes = CONSOLE_MODULES.map((module) => module.code);
    expect(new Set(codes).size).toBe(codes.length);
    expect(codes[0]).toBe('AD-01');
    expect(codes.at(-1)).toBe('AD-16');
  });

  it('gives every module either a screen or a reason it has none', () => {
    for (const module of CONSOLE_MODULES) {
      if (module.state === 'blocked') {
        expect(module.href, `${module.code} is blocked and must not link anywhere`).toBeNull();
        expect(
          noteFor(module.code),
          `${module.code} must say what it waits on, in every language`,
        ).toBeTruthy();
      } else {
        expect(module.href, `${module.code} is built and must link somewhere`).toBeTruthy();
      }
      // Every one of the sixteen names an issue, so a reader can go and read the argument
      // rather than repeat it.
      expect(module.issue).toBeGreaterThan(0);
    }
  });

  it('says what a part-built module is still missing', () => {
    // The half-built ones are the easiest to misread: a link that works implies a module
    // that is finished. Every one of them has to say which half is not.
    const partial = CONSOLE_MODULES.filter((module) => module.state === 'partial');
    expect(partial.length).toBeGreaterThan(0);
    for (const module of partial) {
      expect(
        noteFor(module.code),
        `${module.code} is part built and must say what is missing, in every language`,
      ).toBeTruthy();
    }
  });

  it('counts the modules a reader can actually reach', () => {
    const reachable = CONSOLE_MODULES.filter((module) => module.href !== null).length;
    expect(builtModuleCount()).toBe(reachable);
  });
});

/**
 * Whether every language has a note for a module — issue #324.
 *
 * <p>The note moved to `admin.modules.{code}.waitingOn` and the state stayed here, so the
 * invariant the two tests below assert now spans the catalogue: a module marked unfinished
 * with no sentence saying why is the defect, and a sentence that exists in English and not in
 * Turkish is the same defect for three quarters of the platform's readers.
 */
function noteFor(code: string): boolean {
  return CATALOGUES.every((catalogue) => {
    const module = (catalogue.admin.modules as Record<string, { waitingOn?: string }>)[code];
    return typeof module?.waitingOn === 'string' && module.waitingOn.length > 0;
  });
}

describe('the console navigation', () => {
  it('links only to screens a module actually owns', () => {
    // `components/shell/navigation.ts` states the rule for the public site and it holds
    // here: an entry in a navigation is a promise that pressing it goes somewhere. The
    // failure this catches is a rail entry added before its screen, which is exactly where
    // somebody puts one.
    const owned = new Set(CONSOLE_MODULES.flatMap(screensOf));

    for (const group of CONSOLE_GROUPS) {
      for (const link of group.links) {
        expect(
          owned.has(link),
          `${link} is in the rail and belongs to no module`,
        ).toBe(true);
      }
    }
  });

  it('lists every screen a built module owns', () => {
    // The other direction, and the one that catches a screen shipped with no way to reach
    // it: the ledger and the three curation screens are each a module's second, third or
    // fourth, and none of them has a row of its own on the index.
    const railed = new Set(CONSOLE_GROUPS.flatMap((group) => group.links));

    for (const module of CONSOLE_MODULES) {
      for (const screen of screensOf(module)) {
        expect(railed.has(screen), `${screen} exists and is in no rail`).toBe(true);
      }
    }
  });

  it('names every destination exactly once', () => {
    const hrefs = CONSOLE_GROUPS.flatMap((group) => group.links);
    expect(new Set(hrefs).size).toBe(hrefs.length);
  });
});

/**
 * The rail and the screen it opens — issue #403.
 *
 * <p>The rail called `/admin/disputes` "Ödəniş mübahisələri" and the screen's own `<h1>` and
 * `<title>` said "Geri tələblər". Somebody clicked one word and arrived at another, in a
 * console where every other entry agrees with its page, and nothing said so.
 */
describe('the rail and the page it opens', () => {
  /**
   * The catalogue node a console href's page copy lives under.
   *
   * <p>Derived rather than tabulated: the key is the path under `/admin` camel-cased, which
   * holds for twenty-five of the twenty-six. The submission queue is the exception, and it is
   * named here rather than renamed — the key is in four catalogues and in a route, and the
   * inconsistency worth catching is between the two words a reader sees.
   */
  function pageKeyFor(href: string): string {
    if (href === '/admin/moderation/submissions') return 'submissions';

    const [head = '', ...rest] = href.slice('/admin/'.length).split(/[/-]/);
    return head + rest.map((part) => part.slice(0, 1).toUpperCase() + part.slice(1)).join('');
  }

  it('calls a screen the same thing in both places, in every language', () => {
    for (const catalogue of CATALOGUES) {
      const links = catalogue.admin.links as Record<string, string>;
      const pages = catalogue.admin.pages as Record<string, { title?: string }>;

      for (const [href, label] of Object.entries(links)) {
        const title = pages[pageKeyFor(href)]?.title;
        expect(title, `${href} has a rail entry and no page title`).toBeTruthy();

        /*
         * One name has to contain the other rather than equal it. A rail is narrower than a
         * heading and "Analytics" opening "Platform analytics" is the same name said shorter,
         * which is not the failure — two different nouns is, and containment is what tells
         * them apart.
         */
        const one = label.toLocaleLowerCase();
        const other = (title as string).toLocaleLowerCase();
        expect(
          one.includes(other) || other.includes(one),
          `${href} is "${label}" in the rail and "${title as string}" on the page`,
        ).toBe(true);
      }
    }
  });
});

describe('isCurrentConsoleLink', () => {
  it('marks the entry whose path is being rendered', () => {
    expect(isCurrentConsoleLink('/admin/audit', '/admin/audit')).toBe(true);
    expect(isCurrentConsoleLink('/admin/audit', '/admin/ledger')).toBe(false);
  });

  it('does not mark a parent as current on its own child', () => {
    // A prefix rule would mark Collections current on the badge screen as well as the badge
    // entry itself, and `aria-current="page"` appearing twice tells a screen reader the page
    // is in two places.
    expect(isCurrentConsoleLink('/admin/curation', '/admin/curation/badges')).toBe(false);
    expect(isCurrentConsoleLink('/admin/curation/badges', '/admin/curation/badges')).toBe(true);
  });

  it('marks Collections current on a collection that has no entry of its own', () => {
    // Opening one collection must not make the rail go blank.
    expect(isCurrentConsoleLink('/admin/curation', '/admin/curation/autumn-picks')).toBe(true);
  });
});
