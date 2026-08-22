import { describe, expect, it } from 'vitest';
import { FOOTER_GROUPS, PRIMARY_NAVIGATION, isCurrent } from './navigation';

describe('isCurrent', () => {
  it('marks a section current from anywhere inside it', () => {
    // A reader on a subcategory is inside Categories, and a navigation that says otherwise is
    // a navigation that has lost them.
    expect(isCurrent('/categories', '/categories')).toBe(true);
    expect(isCurrent('/categories', '/categories/games')).toBe(true);
    expect(isCurrent('/categories', '/categories/games/tabletop')).toBe(true);
  });

  it('is exact for the home page, which would otherwise be current everywhere', () => {
    expect(isCurrent('/', '/')).toBe(true);
    expect(isCurrent('/', '/discover')).toBe(false);
  });

  it('matches a whole segment rather than a prefix of one', () => {
    expect(isCurrent('/discover', '/discovery-guide')).toBe(false);
  });
});

/**
 * The rule `DashboardNav` states and that matters more in the shell, because this is on every
 * page: an entry pointing at a 404 tells a visitor the site is broken.
 *
 * The list below is every route this application serves that the shell is allowed to point at.
 * A new navigation entry has to be added here as well, which is the point — the test is a
 * second pair of eyes on "does that page exist".
 */
const ROUTES_THAT_EXIST = new Set([
  '/',
  '/discover',
  '/categories',
  '/search',
  '/notifications',
  '/projects/new',
  '/settings/notifications',
  '/settings/sessions',
  '/sign-in',
  '/register',
]);

describe('the navigation lists', () => {
  it('point only at routes that exist', () => {
    const targets = [
      ...PRIMARY_NAVIGATION.map((link) => link.href),
      ...FOOTER_GROUPS.flatMap((group) => group.links.map((link) => link.href)),
    ];

    for (const href of targets) {
      expect(ROUTES_THAT_EXIST, `${href} is in the shell's navigation`).toContain(href);
    }
  });

  it('keep the header to the two entries §8.6’s collapsed pill has room for', () => {
    expect(PRIMARY_NAVIGATION).toHaveLength(2);
  });

  it('carry no legal column, because §22 has not written the pages yet', () => {
    // A Terms link resolving to nothing is a promise about a document that does not exist.
    const headings = FOOTER_GROUPS.map((group) => group.heading);
    expect(headings).not.toContain('Legal');
  });

  it('label every entry', () => {
    for (const group of FOOTER_GROUPS) {
      for (const link of group.links) expect(link.label.trim()).not.toBe('');
    }
  });
});
