import { describe, expect, it } from 'vitest';
import { PRIVATE_PATH_PREFIXES } from '../seo/indexability';
import { SESSION_REQUIRED_PATHS, requiresSession } from './private-routes';

describe('requiresSession', () => {
  it('covers the account, its settings and its inbox', () => {
    expect(requiresSession('/settings')).toBe(true);
    expect(requiresSession('/settings/sessions')).toBe(true);
    expect(requiresSession('/notifications')).toBe(true);
  });

  it('covers the editor and the dashboard, whatever the campaign id is', () => {
    expect(requiresSession('/projects/new')).toBe(true);
    expect(requiresSession('/projects/abc/edit')).toBe(true);
    expect(requiresSession('/projects/abc/edit/story')).toBe(true);
    expect(requiresSession('/projects/abc/dashboard/backers')).toBe(true);
  });

  it('leaves the public surfaces alone', () => {
    expect(requiresSession('/')).toBe(false);
    expect(requiresSession('/discover')).toBe(false);
    expect(requiresSession('/categories/games')).toBe(false);
    expect(requiresSession('/search')).toBe(false);
    expect(requiresSession('/projects/abc/my-campaign')).toBe(false);
  });

  /**
   * The two entries that prove this list is not the crawler's.
   *
   * `apps/web/README.md` calls the checkout the half-way case — its reward list is `permitAll`
   * and reads through `publicFetch`, so the prices render for somebody who has not registered
   * and only the two mutations need a token. The pre-launch page exists for the followers it
   * collects, who have not registered at all. A guard on either would turn the page into the
   * funnel it was built to avoid.
   */
  it('does not guard the checkout or the pre-launch page, which are public by design', () => {
    expect(requiresSession('/projects/abc/back')).toBe(false);
    expect(requiresSession('/projects/abc/prelaunch')).toBe(false);
  });

  /**
   * The routes this pull request added, on the side of the line each belongs to — #267.
   *
   * The guard was built by #318 and its list has not changed, which is the point of
   * asserting it here: a list that no route has to remember to join is also a list nobody
   * remembers to check. Every one of these is a new page, and getting any of them wrong is
   * silent — a guarded public page 404s for the audience it exists for, and an unguarded
   * private one renders somebody's pledge to whoever holds the URL.
   */
  it('guards the credential screens the account area gained', () => {
    // §4.1's A-12 and A-13 (#277). Covered by `/settings` rather than by entries of their
    // own, which is the prefix rule doing its job.
    expect(requiresSession('/settings/email')).toBe(true);
    expect(requiresSession('/settings/password')).toBe(true);
  });

  it('guards the pledge manager and everything under it', () => {
    // §4.5's PL-09 and PL-10 (#287). A pledge is money somebody committed and the screen
    // can cancel it.
    expect(requiresSession('/pledges')).toBe(true);
    expect(requiresSession('/pledges/abc')).toBe(true);
    expect(requiresSession('/pledges/abc/address')).toBe(true);
  });

  it('leaves the recovery pages and the public profile alone', () => {
    /*
     * §4.1's A-06 (#271). GUARDING THESE WOULD BE ABSURD IN A WAY THAT IS EASY TO DO BY
     * ACCIDENT: somebody asking for a password reset is by definition somebody who cannot
     * sign in, so a guard would redirect them to the form they cannot get past and then
     * offer them the reset link they were already following.
     */
    expect(requiresSession('/reset-password')).toBe(false);
    expect(requiresSession('/reset-password/confirm')).toBe(false);

    /*
     * §4.1's A-12's confirmation. The credential is the token in the message, and the
     * person following it is reading the new mailbox — which is the browser least likely
     * to be signed in.
     */
    expect(requiresSession('/confirm-email-change')).toBe(false);

    // §4.2's P-04 to P-07 (#274). A public profile read by a stranger is the audience it
    // exists for; visibility is decided by the service, which answers 404 for a private
    // one rather than letting the client decide.
    expect(requiresSession('/u/ismet')).toBe(false);
  });

  it('matches whole segments, never a prefix of one', () => {
    expect(requiresSession('/settings-guide')).toBe(false);
    expect(requiresSession('/adminstration')).toBe(false);
  });

  it('does not treat a shorter path as covered by a longer pattern', () => {
    // `/projects/*/edit` must not claim `/projects/abc`, which is not a route at all.
    expect(requiresSession('/projects/abc')).toBe(false);
    expect(requiresSession('/projects')).toBe(false);
  });
});

/**
 * The relationship between the two lists, asserted rather than described.
 *
 * They are deliberately separate — "not indexable" and "requires a session" are different
 * questions, exactly as `lib/seo/indexability.ts` argues "public" and "indexable" are. This
 * test is what stops somebody deriving one from the other later and quietly changing who can
 * read a page in order to change what a crawler sees.
 */
describe('the two path lists', () => {
  it('are not the same list', () => {
    expect(SESSION_REQUIRED_PATHS).not.toEqual(PRIVATE_PATH_PREFIXES);
  });

  it('disagree exactly where the specification says they should', () => {
    for (const path of ['/projects/*/prelaunch', '/projects/*/back', '/discover?', '/search']) {
      expect(PRIVATE_PATH_PREFIXES).toContain(path);
      expect(SESSION_REQUIRED_PATHS).not.toContain(path);
    }
  });
});
