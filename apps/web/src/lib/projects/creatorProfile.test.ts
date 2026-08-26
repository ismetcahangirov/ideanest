import { describe, expect, it, vi } from 'vitest';
import { fetchCreatorProjects, fetchPublicProfile, profileHref } from './creatorProfile';

/**
 * §4.4's Creator tab, over §4.2's public profile — #282.
 *
 * WHAT THESE COVER:
 *
 *   - **every refusal is one answer.** The endpoint answers 404 — never 403 — for an unknown
 *     slug, a deleted account and an account that has chosen `PRIVATE`, and this module must
 *     not be able to tell them apart. A reader that distinguished them would rebuild the
 *     oracle the 404 exists to close.
 *   - **the list is asked for one row longer than it is shown**, so that removing the campaign
 *     the reader is already on still leaves the tab full.
 *
 * The narrowing these used to cover moved to `lib/profiles/wire.ts` with #323, and so did its
 * tests. What is left here is what this module still decides for itself.
 */

describe('fetching a profile', () => {
  function respondWith(body: unknown, status = 200): typeof fetch {
    return vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), {
        status,
        headers: { 'content-type': 'application/json' },
      }),
    ) as unknown as typeof fetch;
  }

  it('reads the public endpoint at the creator’s slug', async () => {
    const fetchImpl = respondWith({ slug: 'ayan', name: 'Ayan Q', bio: null, avatarUrl: null });

    const profile = await fetchPublicProfile('ayan', {
      fetchImpl,
      env: { IDEANEST_API_ORIGIN: 'https://api.test' },
    });

    expect(profile?.slug).toBe('ayan');
    const [url] = vi.mocked(fetchImpl).mock.calls[0] as [string];
    expect(url).toBe('https://api.test/v1/users/ayan');
  });

  /**
   * The three refusals the endpoint makes are indistinguishable here on purpose. A test that
   * asserted they are all `null` is what stops a later change from letting the interface say
   * "this creator's profile is private", which is the fact `PRIVATE` exists to withhold.
   */
  it('answers null for an unknown, deleted or private account alike', async () => {
    for (const status of [404, 403, 500]) {
      const profile = await fetchPublicProfile('ayan', {
        fetchImpl: respondWith({ title: 'No' }, status),
        env: { IDEANEST_API_ORIGIN: 'https://api.test' },
      });
      expect(profile).toBeNull();
    }
  });

  it('answers null rather than throwing when the service cannot be reached', async () => {
    const profile = await fetchPublicProfile('ayan', {
      fetchImpl: vi.fn().mockRejectedValue(new TypeError('fetch failed')) as unknown as typeof fetch,
      env: { IDEANEST_API_ORIGIN: 'https://api.test' },
    });

    expect(profile).toBeNull();
  });

  it('asks for one more campaign than it shows, so removing this one leaves the list full', async () => {
    const fetchImpl = respondWith({ projects: [], nextCursor: null });

    await fetchCreatorProjects('ayan', {
      fetchImpl,
      env: { IDEANEST_API_ORIGIN: 'https://api.test' },
    });

    const [url] = vi.mocked(fetchImpl).mock.calls[0] as [string];
    expect(url).toBe('https://api.test/v1/users/ayan/projects?limit=7');
  });
});

describe('the profile address', () => {
  it('is the route the profile epic owns, with the handle escaped', () => {
    expect(profileHref('ayan')).toBe('/u/ayan');
    expect(profileHref('a b')).toBe('/u/a%20b');
  });
});
