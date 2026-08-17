import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../api/problem';
import {
  DISCOVERY_PAGE_LIMIT,
  MAX_DISCOVERY_PAGES,
  clearIndexableProjectsCache,
  fetchIndexableProjects,
  indexableProjects,
} from './projects';

/**
 * The walk over `GET /v1/discover`.
 *
 * The endpoint already refuses to return the seven hidden states, and this walk
 * applies the predicate again anyway. Defence in depth is the point: the
 * sitemap is the one surface where the cost of a leak is a draft campaign in
 * somebody's search results, which cannot be recalled.
 */

interface Card {
  readonly slug: string;
  readonly creatorSlug: string;
  readonly state: string;
  readonly launchedAt?: string;
  readonly deadline?: string;
}

function card(overrides: Partial<Card> = {}): Card {
  return {
    slug: 'ceramics',
    creatorSlug: 'aysel',
    state: 'LIVE',
    launchedAt: '2026-05-01T09:00:00.000Z',
    ...overrides,
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

/** The requested URLs, in order. */
let requested: string[] = [];

function respondWith(pages: readonly unknown[]): void {
  let call = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      requested.push(String(input));
      const page = pages[Math.min(call, pages.length - 1)];
      call += 1;
      return jsonResponse(page);
    }),
  );
}

beforeEach(() => {
  requested = [];
  clearIndexableProjectsCache();
});

afterEach(() => {
  vi.unstubAllGlobals();
  clearIndexableProjectsCache();
});

describe('fetchIndexableProjects', () => {
  it('reads the unfiltered feed at the configured API origin', async () => {
    respondWith([{ items: [card()] }]);

    await fetchIndexableProjects({ env: { IDEANEST_API_ORIGIN: 'http://api.internal:8080' } });

    expect(requested).toHaveLength(1);
    expect(requested[0]).toBe(
      `http://api.internal:8080/v1/discover?limit=${DISCOVERY_PAGE_LIMIT}`,
    );
  });

  it('asks for the largest page the service will serve', () => {
    // DiscoveryQuery.MAX_LIMIT. A smaller page is more round trips for the same
    // rows; a larger one is silently clamped.
    expect(DISCOVERY_PAGE_LIMIT).toBe(100);
  });

  it('follows the cursor to the end of the feed', async () => {
    respondWith([
      { items: [card({ slug: 'one' })], nextCursor: 'c1' },
      { items: [card({ slug: 'two' })], nextCursor: 'c2' },
      { items: [card({ slug: 'three' })] },
    ]);

    const projects = await fetchIndexableProjects();

    expect(projects.map((entry) => entry.slug)).toEqual(['one', 'two', 'three']);
    expect(requested[1]).toContain('cursor=c1');
    expect(requested[2]).toContain('cursor=c2');
  });

  it('drops every state that must not be indexed', async () => {
    respondWith([
      {
        items: [
          card({ slug: 'draft', state: 'DRAFT' }),
          card({ slug: 'suspended', state: 'SUSPENDED' }),
          card({ slug: 'rejected', state: 'REJECTED' }),
          card({ slug: 'cancelled', state: 'CANCELED' }),
          card({ slug: 'teaser', state: 'PRELAUNCH' }),
          card({ slug: 'live', state: 'LIVE' }),
        ],
      },
    ]);

    const projects = await fetchIndexableProjects();

    expect(projects.map((entry) => entry.slug)).toEqual(['live']);
  });

  it('drops a card with no slug rather than emitting half a URL', async () => {
    respondWith([{ items: [{ state: 'LIVE', slug: '', creatorSlug: 'aysel' }, card()] }]);

    const projects = await fetchIndexableProjects();

    expect(projects.map((entry) => entry.slug)).toEqual(['ceramics']);
  });

  it('carries the timestamps through unchanged', async () => {
    respondWith([
      {
        items: [card({ launchedAt: '2026-05-01T09:00:00.000Z', deadline: '2026-09-01T00:00:00Z' })],
      },
    ]);

    const [project] = await fetchIndexableProjects();

    expect(project?.launchedAt).toBe('2026-05-01T09:00:00.000Z');
    expect(project?.deadline).toBe('2026-09-01T00:00:00Z');
  });

  it('raises the services refusal rather than serving an empty sitemap', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ title: 'Too many requests' }, 429)),
    );

    await expect(fetchIndexableProjects()).rejects.toBeInstanceOf(ApiError);
  });

  it('stops rather than looping when the service repeats a cursor', async () => {
    respondWith([{ items: [card()], nextCursor: 'stuck' }]);

    const projects = await fetchIndexableProjects();

    expect(requested).toHaveLength(2);
    expect(projects).toHaveLength(1);
  });

  it('never emits the same URL twice, even if two pages overlap', async () => {
    respondWith([
      { items: [card({ slug: 'one' }), card({ slug: 'two' })], nextCursor: 'c1' },
      { items: [card({ slug: 'two' }), card({ slug: 'three' })] },
    ]);

    const projects = await fetchIndexableProjects();

    expect(projects.map((entry) => entry.slug)).toEqual(['one', 'two', 'three']);
  });

  it('bounds the walk', () => {
    expect(MAX_DISCOVERY_PAGES * DISCOVERY_PAGE_LIMIT).toBeGreaterThanOrEqual(50_000);
  });
});

describe('indexableProjects', () => {
  it('reads the feed once for the segment list and the segment itself', async () => {
    respondWith([{ items: [card()] }]);

    const first = await indexableProjects();
    const second = await indexableProjects();

    expect(second).toBe(first);
    expect(requested).toHaveLength(1);
  });

  it('does not cache a failure', async () => {
    let calls = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        calls += 1;
        return calls === 1 ? jsonResponse({}, 503) : jsonResponse({ items: [card()] });
      }),
    );

    await expect(indexableProjects()).rejects.toBeInstanceOf(ApiError);
    expect(await indexableProjects()).toHaveLength(1);
  });
});
