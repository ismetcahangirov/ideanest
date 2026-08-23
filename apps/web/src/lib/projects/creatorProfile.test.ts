import { describe, expect, it, vi } from 'vitest';
import {
  fetchCreatorProjects,
  fetchPublicProfile,
  profileHref,
  readProfile,
  readProjectPage,
} from './creatorProfile';

/**
 * §4.4's Creator tab, over §4.2's public profile — #282.
 *
 * WHAT THESE COVER:
 *
 *   - **nothing is invented for a field that is absent.** A biography that has not been
 *     written is `null`, not an empty string, so the tab omits the row rather than printing a
 *     box that says the creator has not written one. On a page whose subject is whether to
 *     send somebody money, an invented blank is worse than a missing row.
 *   - **every refusal is one answer.** The endpoint answers 404 — never 403 — for an unknown
 *     slug, a deleted account and an account that has chosen `PRIVATE`, and this module must
 *     not be able to tell them apart. A reader that distinguished them would rebuild the
 *     oracle the 404 exists to close.
 *   - **money is never a number.** An amount arrives as a string and stays one, whatever a
 *     card does with it afterwards.
 *   - **a row with nothing to link to is dropped**, because it would render as an unlabelled
 *     hole in a list of somebody's work.
 */

describe('reading a profile', () => {
  it('keeps the five fields the endpoint publishes', () => {
    const profile = readProfile({
      slug: 'ayan',
      name: 'Ayan Q',
      avatarUrl: 'https://cdn.test/a.jpg',
      bio: 'Photographer in Baku.',
      joinedAt: '2024-02-01T00:00:00Z',
    });

    expect(profile).toEqual({
      slug: 'ayan',
      name: 'Ayan Q',
      avatarUrl: 'https://cdn.test/a.jpg',
      bio: 'Photographer in Baku.',
      joinedAt: '2024-02-01T00:00:00Z',
    });
  });

  /** An explicit null is "they have not written one", never "it has not arrived yet". */
  it('reads an unwritten biography and an unset avatar as null', () => {
    const profile = readProfile({
      slug: 'ayan',
      name: 'Ayan Q',
      avatarUrl: null,
      bio: null,
      joinedAt: '2024-02-01T00:00:00Z',
    });

    expect(profile?.bio).toBeNull();
    expect(profile?.avatarUrl).toBeNull();
  });

  it('refuses a body with no handle or no name, so the tab falls back to the byline', () => {
    expect(readProfile({ name: 'Ayan Q' })).toBeNull();
    expect(readProfile({ slug: 'ayan' })).toBeNull();
    expect(readProfile(null)).toBeNull();
  });
});

describe('reading a creator’s campaigns', () => {
  const card = {
    id: 'p1',
    title: 'A folding bicycle',
    slug: 'a-folding-bicycle',
    creatorSlug: 'ayan',
    blurb: 'It folds.',
    state: 'SUCCESSFUL',
    goal: { amount: '10000.00', currency: 'AZN' },
    pledged: { amount: '12500.00', currency: 'AZN' },
    backersCount: 214,
    deadline: '2026-01-01T00:00:00Z',
    launchedAt: '2025-12-01T00:00:00Z',
    coverImage: { url: 'https://cdn.test/p1.jpg', width: 1600, height: 900 },
  };

  it('keeps money as the string it arrived as', () => {
    const page = readProjectPage({ projects: [card], nextCursor: null });

    expect(page.projects[0]?.goal).toEqual({ amount: '10000.00', currency: 'AZN' });
    expect(typeof page.projects[0]?.pledged?.amount).toBe('string');
  });

  it('drops a row with nothing to link to and keeps the ones beside it', () => {
    const page = readProjectPage({
      projects: [{ id: 'p2', title: 'No slug', creatorSlug: 'ayan', state: 'LIVE' }, card],
    });

    expect(page.projects.map((project) => project.id)).toEqual(['p1']);
  });

  it('refuses a cover with no dimensions, because the box could not be reserved', () => {
    const page = readProjectPage({
      projects: [{ ...card, coverImage: { url: 'https://cdn.test/p1.jpg' } }],
    });

    expect(page.projects[0]?.coverImage).toBeNull();
  });

  it('answers an empty page for a body that is not one', () => {
    expect(readProjectPage('nope').projects).toEqual([]);
  });
});

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
