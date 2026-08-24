import { describe, expect, it } from 'vitest';
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
        expect(module.waitingOn, `${module.code} must say what it waits on`).toBeTruthy();
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
      expect(module.waitingOn, `${module.code} is part built and must say what is missing`).toBeTruthy();
    }
  });

  it('counts the modules a reader can actually reach', () => {
    const reachable = CONSOLE_MODULES.filter((module) => module.href !== null).length;
    expect(builtModuleCount()).toBe(reachable);
  });
});

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
          owned.has(link.href),
          `${link.href} is in the rail and belongs to no module`,
        ).toBe(true);
      }
    }
  });

  it('lists every screen a built module owns', () => {
    // The other direction, and the one that catches a screen shipped with no way to reach
    // it: the ledger and the three curation screens are each a module's second, third or
    // fourth, and none of them has a row of its own on the index.
    const railed = new Set(CONSOLE_GROUPS.flatMap((group) => group.links.map((link) => link.href)));

    for (const module of CONSOLE_MODULES) {
      for (const screen of screensOf(module)) {
        expect(railed.has(screen), `${screen} exists and is in no rail`).toBe(true);
      }
    }
  });

  it('names every destination exactly once', () => {
    const hrefs = CONSOLE_GROUPS.flatMap((group) => group.links.map((link) => link.href));
    expect(new Set(hrefs).size).toBe(hrefs.length);
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
