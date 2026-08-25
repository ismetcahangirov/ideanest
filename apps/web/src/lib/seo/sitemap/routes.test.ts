import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import robots from '../../../app/robots';
import sitemap, { generateSitemaps } from '../../../app/sitemap';
import { GET as sitemapIndex } from '../../../app/sitemap_index.xml/route';
import { clearIndexableProjectsCache } from './projects';
import { MAX_URLS_PER_SITEMAP } from './segments';

/**
 * The three routes, exercised end to end with the service stubbed.
 *
 * A sitemap route that throws is a build that fails and a crawl that gets a
 * 500, so these tests call the real route functions rather than the helpers
 * underneath them.
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
    slug: 'ceramics-for-the-old-town',
    creatorSlug: 'aysel',
    state: 'LIVE',
    launchedAt: '2026-05-01T09:00:00.000Z',
    ...overrides,
  };
}

function feed(items: readonly Card[]): Response {
  return new Response(JSON.stringify({ items }), {
    headers: { 'content-type': 'application/json' },
  });
}

function serve(items: readonly Card[]): void {
  vi.stubGlobal('fetch', vi.fn(async () => feed(items)));
}

beforeEach(() => {
  clearIndexableProjectsCache();
  vi.stubEnv('IDEANEST_SITE_URL', 'https://ideanest.az');
  vi.stubEnv('IDEANEST_API_ORIGIN', 'http://localhost:8080');
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  clearIndexableProjectsCache();
});

describe('robots.txt', () => {
  it('allows the public surface', () => {
    const { rules } = robots();
    const rule = Array.isArray(rules) ? rules[0] : rules;

    expect(rule?.allow).toBe('/');
  });

  it('disallows the pledge flow, the editor, the account, and the dashboard', () => {
    const { rules } = robots();
    const rule = Array.isArray(rules) ? rules[0] : rules;
    const disallow = [rule?.disallow ?? []].flat();

    expect(disallow).toContain('/projects/*/back');
    expect(disallow).toContain('/projects/new');
    expect(disallow).toContain('/projects/*/edit');
    expect(disallow).toContain('/projects/*/prelaunch');
    expect(disallow).toContain('/settings');
    expect(disallow).toContain('/dashboard');
    expect(disallow).toContain('/admin');
  });

  it('disallows every filtered permutation of the feed, which is a crawl trap', () => {
    const { rules } = robots();
    const rule = Array.isArray(rules) ? rules[0] : rules;
    const disallow = [rule?.disallow ?? []].flat();

    expect(disallow).toContain('/discover?');
  });

  it('disallows the proxied API, which serves JSON and not pages', () => {
    const { rules } = robots();
    const rule = Array.isArray(rules) ? rules[0] : rules;
    const disallow = [rule?.disallow ?? []].flat();

    expect(disallow).toContain('/v1/');
  });

  it('points at the sitemap index, at the configured base URL', () => {
    expect(robots().sitemap).toBe('https://ideanest.az/sitemap_index.xml');
  });

  it('takes the base URL from configuration and nowhere else', () => {
    vi.stubEnv('IDEANEST_SITE_URL', 'https://staging.ideanest.az');

    expect(robots().sitemap).toBe('https://staging.ideanest.az/sitemap_index.xml');
  });
});

describe('generateSitemaps', () => {
  it('shards by content type and by size', async () => {
    serve([card()]);

    expect(await generateSitemaps()).toEqual([
      { id: 'pages' },
      { id: 'discovery' },
      { id: 'projects-0' },
    ]);
  });

  it('keeps the segment list when the service cannot be reached', async () => {
    // Every segment 404s if its id is missing from this list, including the two
    // that need no data at all. A blink from the API must not take those down.
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new TypeError('fetch failed');
      }),
    );

    expect(await generateSitemaps()).toEqual([
      { id: 'pages' },
      { id: 'discovery' },
      { id: 'projects-0' },
    ]);
  });
});

describe('the sitemap segments', () => {
  it('serves the static pages with no call to the service at all', async () => {
    const fetchMock = vi.fn(async () => feed([]));
    vi.stubGlobal('fetch', fetchMock);

    const entries = await sitemap({ id: Promise.resolve('pages') });

    expect(entries.length).toBeGreaterThan(0);
    for (const entry of entries) expect(entry.url.startsWith('https://ideanest.az')).toBe(true);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('serves the discovery segment without a query string', async () => {
    serve([]);

    const entries = await sitemap({ id: Promise.resolve('discovery') });

    /* One address per language since #123 — `localised.ts` explains the shape. */
    for (const locale of ['az', 'en', 'ru', 'tr']) {
      expect(entries.map((entry) => entry.url)).toContain(`https://ideanest.az/${locale}/discover`);
    }
  });

  it('lists a live campaign with an absolute URL and a real lastModified', async () => {
    serve([card()]);

    const entries = await sitemap({ id: Promise.resolve('projects-0') });

    const path = '/projects/aysel/ceramics-for-the-old-town';
    const languages = {
      az: `https://ideanest.az/az${path}`,
      en: `https://ideanest.az/en${path}`,
      ru: `https://ideanest.az/ru${path}`,
      tr: `https://ideanest.az/tr${path}`,
      'x-default': `https://ideanest.az/en${path}`,
    };

    /*
     * The campaign's own facts — the timestamp and the change frequency — are copied onto
     * every language unchanged, because a deadline that passed passed it in all four.
     */
    expect(entries).toEqual(
      ['az', 'en', 'ru', 'tr'].map((locale) => ({
        url: `https://ideanest.az/${locale}${path}`,
        lastModified: new Date('2026-05-01T09:00:00.000Z'),
        changeFrequency: 'daily',
        alternates: { languages },
      })),
    );
  });

  it('leaves a draft, a suspended, and a rejected campaign out', async () => {
    serve([
      card({ slug: 'draft', state: 'DRAFT' }),
      card({ slug: 'suspended', state: 'SUSPENDED' }),
      card({ slug: 'rejected', state: 'REJECTED' }),
      card({ slug: 'live', state: 'LIVE' }),
    ]);

    const entries = await sitemap({ id: Promise.resolve('projects-0') });

    expect(entries.map((entry) => entry.url)).toEqual(
      ['az', 'en', 'ru', 'tr'].map((locale) => `https://ideanest.az/${locale}/projects/aysel/live`),
    );
  });

  it('never puts more than one shards worth of URLs in a segment', async () => {
    serve([card()]);

    const entries = await sitemap({ id: Promise.resolve('projects-0') });

    expect(entries.length).toBeLessThanOrEqual(MAX_URLS_PER_SITEMAP);
  });

  it('is empty for a segment id nobody generated', async () => {
    serve([card()]);

    expect(await sitemap({ id: Promise.resolve('nonsense') })).toEqual([]);
    expect(await sitemap({ id: Promise.resolve(undefined) })).toEqual([]);
  });
});

describe('the sitemap index route', () => {
  it('references exactly the segments generateSitemaps produced', async () => {
    serve([card()]);

    const response = await sitemapIndex();
    const xml = await response.text();

    expect(response.headers.get('content-type')).toContain('application/xml');
    for (const { id } of await generateSitemaps()) {
      expect(xml).toContain(`<loc>https://ideanest.az/sitemap/${id}.xml</loc>`);
    }
    expect((xml.match(/<sitemap>/g) ?? []).length).toBe(3);
  });
});
