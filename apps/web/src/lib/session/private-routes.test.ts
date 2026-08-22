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
