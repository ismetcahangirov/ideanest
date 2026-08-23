import { describe, expect, it, vi } from 'vitest';
import { fetchProjectUpdates, readUpdatePage, UPDATE_PAGE_SIZE } from './updates';

/**
 * §4.4's Updates tab, over §4.9's public read — #284.
 *
 * WHAT THESE COVER:
 *
 *   - **nothing is filtered here.** A `BACKERS_ONLY` update that the service chose to send is
 *     rendered, because the service is the only thing that knows whether the caller is
 *     entitled to it. A client-side filter would be a second, weaker copy of an entitlement
 *     rule, and the day the two disagreed the weaker one would decide whether a private
 *     update reached public HTML.
 *   - **the number is the service's.** §4.9 allocates it once, at insert, and never
 *     recomputes it, because "update 7 said the moulds were late" is a thing somebody says to
 *     support six months later. A reader that numbered by position would renumber every
 *     earlier update the first time one was withheld.
 *   - **an unusable row is dropped and its neighbours survive.** An update with no date is not
 *     a shorter update; it is a row this page cannot describe.
 *   - **a refused read is `null`, not an empty list.** "This campaign has posted no updates"
 *     printed over a restarting service is a claim about the creator that happens to be false.
 */

describe('reading a page of updates', () => {
  it('keeps the number the service allocated rather than the position in the array', () => {
    const page = readUpdatePage({
      updates: [
        { number: 9, title: 'Nine', body: 'b', visibility: 'PUBLIC', publishedAt: '2026-08-01T00:00:00Z' },
        { number: 4, title: 'Four', body: 'b', visibility: 'PUBLIC', publishedAt: '2026-07-01T00:00:00Z' },
      ],
      nextCursor: 3,
    });

    expect(page.updates.map((update) => update.number)).toEqual([9, 4]);
    expect(page.nextCursor).toBe(3);
  });

  it('keeps a backers-only update exactly as the service sent it', () => {
    const page = readUpdatePage({
      updates: [
        {
          number: 2,
          title: 'For backers',
          body: 'The moulds were late.',
          visibility: 'BACKERS_ONLY',
          publishedAt: '2026-08-01T00:00:00Z',
        },
      ],
    });

    expect(page.updates).toHaveLength(1);
    expect(page.updates[0]?.visibility).toBe('BACKERS_ONLY');
  });

  /**
   * The safe default is for the READER, not for the platform: the list has already been
   * filtered by the service, so an unrecognised value is a row it chose to send. Marking it
   * backers-only on a guess would print "Backers only" beside an update everybody can see,
   * which is a claim about who else is reading.
   */
  it('treats an unrecognised visibility as public', () => {
    const page = readUpdatePage({
      updates: [
        { number: 1, title: 'A', body: 'b', visibility: 'SOMETHING_NEW', publishedAt: '2026-08-01T00:00:00Z' },
      ],
    });

    expect(page.updates[0]?.visibility).toBe('PUBLIC');
  });

  it('drops a row with no date or no number and keeps the ones beside it', () => {
    const page = readUpdatePage({
      updates: [
        { number: 3, title: 'Good', body: 'b', publishedAt: '2026-08-01T00:00:00Z' },
        { title: 'No number', body: 'b', publishedAt: '2026-08-01T00:00:00Z' },
        { number: 2, title: 'No date', body: 'b' },
        { number: 1, title: 'Also good', body: 'b', publishedAt: '2026-07-01T00:00:00Z' },
      ],
    });

    expect(page.updates.map((update) => update.number)).toEqual([3, 1]);
  });

  /** A one-line announcement is a title and nothing else, which is a real update. */
  it('keeps an update whose body is empty', () => {
    const page = readUpdatePage({
      updates: [{ number: 1, title: 'It shipped', publishedAt: '2026-08-01T00:00:00Z' }],
    });

    expect(page.updates[0]?.body).toBe('');
  });

  it('answers an empty page for a body that is not one', () => {
    expect(readUpdatePage(null).updates).toEqual([]);
    expect(readUpdatePage('nope').nextCursor).toBeNull();
  });

  it('treats an absent cursor as the last page', () => {
    expect(readUpdatePage({ updates: [] }).nextCursor).toBeNull();
  });
});

describe('fetching a page of updates', () => {
  function respondWith(body: unknown, status = 200): typeof fetch {
    return vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), {
        status,
        headers: { 'content-type': 'application/json' },
      }),
    ) as unknown as typeof fetch;
  }

  it('asks the public endpoint for a page and reads what comes back', async () => {
    const fetchImpl = respondWith({
      updates: [{ number: 1, title: 'One', body: 'b', publishedAt: '2026-08-01T00:00:00Z' }],
      nextCursor: null,
    });

    const page = await fetchProjectUpdates('p1', null, {
      fetchImpl,
      env: { IDEANEST_API_ORIGIN: 'https://api.test' },
    });

    expect(page?.updates).toHaveLength(1);

    const [url] = vi.mocked(fetchImpl).mock.calls[0] as [string];
    expect(url).toContain('https://api.test/v1/projects/p1/updates');
    expect(url).toContain(`limit=${UPDATE_PAGE_SIZE}`);
    // No cursor on the first page — an empty parameter is not the same request.
    expect(url).not.toContain('cursor=');
  });

  it('carries the cursor the previous page returned', async () => {
    const fetchImpl = respondWith({ updates: [], nextCursor: null });

    await fetchProjectUpdates('p1', 7, {
      fetchImpl,
      env: { IDEANEST_API_ORIGIN: 'https://api.test' },
    });

    const [url] = vi.mocked(fetchImpl).mock.calls[0] as [string];
    expect(url).toContain('cursor=7');
  });

  it('answers null when the service refuses, so the tab can tell that from an empty campaign', async () => {
    const page = await fetchProjectUpdates('p1', null, {
      fetchImpl: respondWith({ title: 'Not found' }, 404),
      env: { IDEANEST_API_ORIGIN: 'https://api.test' },
    });

    expect(page).toBeNull();
  });

  it('answers null when the service cannot be reached at all', async () => {
    const page = await fetchProjectUpdates('p1', null, {
      fetchImpl: vi.fn().mockRejectedValue(new TypeError('fetch failed')) as unknown as typeof fetch,
      env: { IDEANEST_API_ORIGIN: 'https://api.test' },
    });

    expect(page).toBeNull();
  });
});
