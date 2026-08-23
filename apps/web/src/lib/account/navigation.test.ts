import { describe, expect, it } from 'vitest';
import { SESSION_REQUIRED_PATHS } from '../session/private-routes';
import {
  ACCOUNT_GROUPS,
  ACCOUNT_LINKS,
  accountLinkFor,
  isCurrentAccountLink,
} from './navigation';

/**
 * §4.2's account navigation — issue #275.
 *
 * WHAT THESE COVER:
 *
 *   - **every entry points at a route this application serves.** `components/shell/navigation.ts`
 *     has the same test for the same reason, and it matters more here: an account navigation is
 *     exactly where somebody adds the entry before the page.
 *   - **every entry is behind the session guard.** Each of these screens reads one person's own
 *     data, so a path that `requiresSession` does not match is a path an anonymous visitor
 *     reaches and gets a wall of 401s on.
 *   - the current-page match is exact. A prefix match would be a second answer to a question
 *     `isCurrent` already answers differently for the site header, and the difference is
 *     deliberate.
 */

const ROUTES_THAT_EXIST = new Set([
  /*
   * #287's pledge manager, which is the one entry not under `/account` or `/settings` —
   * `navigation.ts` argues why the URL could not move. The list here is deliberately a
   * literal set rather than anything derived from the navigation itself: a test that read
   * its expectation from the thing under test would pass for any navigation at all.
   */
  '/pledges',
  '/account/saved',
  '/account/following',
  '/account/surveys',
  '/account/deliveries',
  '/settings/profile',
  '/settings/notifications',
  '/settings/sessions',
  '/settings/email',
  '/settings/password',
  '/settings/security',
  '/settings/privacy',
]);

/** The same matcher `SessionProvider` guards with, kept in step by importing the list. */
function guarded(pathname: string): boolean {
  return SESSION_REQUIRED_PATHS.some((pattern) => {
    const patternSegments = pattern.split('/');
    const pathSegments = pathname.split('/');
    if (pathSegments.length < patternSegments.length) return false;
    return patternSegments.every(
      (segment, index) => segment === '*' || segment === pathSegments[index],
    );
  });
}

describe('the account navigation', () => {
  it('points only at routes that exist', () => {
    for (const link of ACCOUNT_LINKS) {
      expect(ROUTES_THAT_EXIST, `${link.href} is in the account navigation`).toContain(link.href);
    }
  });

  it('lists every route that exists, so a built screen is not unreachable', () => {
    expect(new Set(ACCOUNT_LINKS.map((link) => link.href))).toEqual(ROUTES_THAT_EXIST);
  });

  it('puts every entry behind the session guard', () => {
    for (const link of ACCOUNT_LINKS) {
      expect(guarded(link.href), `${link.href} requires a session`).toBe(true);
    }
  });

  it('labels and summarises every entry', () => {
    for (const link of ACCOUNT_LINKS) {
      expect(link.label.trim()).not.toBe('');
      expect(link.summary.trim()).not.toBe('');
    }
  });

  it('offers the profile editor, which #276 gave an endpoint to save to', () => {
    /*
     * This assertion used to be its own inverse, and the comment under it said the service
     * had no `PATCH /v1/me`, so an entry here would be a form with nowhere to save. #276 built
     * `GET /v1/me/profile` and `PATCH /v1/me/profile` — a named pair rather than the account
     * patch that was asked for, for the reason `OwnProfileController` gives — so the entry is
     * now the correct one and its absence would be the defect.
     */
    expect(ACCOUNT_LINKS.map((link) => link.href)).toContain('/settings/profile');
  });

  it('puts the profile first among the settings, as the only one about what strangers see', () => {
    const settings = ACCOUNT_GROUPS.find((group) => group.heading === 'Settings');
    expect(settings?.links[0]?.href).toBe('/settings/profile');
  });

  it('groups them by the question being asked rather than by URL prefix', () => {
    expect(ACCOUNT_GROUPS.map((group) => group.heading)).toEqual(['Your account', 'Settings']);
  });
});

describe('isCurrentAccountLink', () => {
  it('is exact', () => {
    expect(isCurrentAccountLink('/settings/security', '/settings/security')).toBe(true);
    expect(isCurrentAccountLink('/settings', '/settings/security')).toBe(false);
    expect(isCurrentAccountLink('/account/saved', '/account/saved-later')).toBe(false);
  });
});

describe('accountLinkFor', () => {
  it('finds the entry for a path, and answers null for one that is not ours', () => {
    expect(accountLinkFor('/account/surveys')?.label).toBe('Surveys');
    expect(accountLinkFor('/pledges/abc/address')).toBeNull();
  });
});
