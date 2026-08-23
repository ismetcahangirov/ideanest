import { beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchCategories, fetchCollections } from '../../api/server';
import type { Collection } from '../../collections/api';
import type { SitemapProject } from './projects';
import {
  DISCOVERY_PATHS,
  PAGE_PATHS,
  discoveryEntries,
  lastModifiedOf,
  pageEntries,
  projectEntries,
  projectPath,
} from './entries';

/**
 * `discoveryEntries` reads the taxonomy since #265 and the collections since #266, because
 * §4.3 makes both data rather than code. The reads are stubbed here: what is under test is
 * which URLs a tree and a collection list become, not whether the service answers.
 */
vi.mock('../../api/server', () => ({ fetchCategories: vi.fn(), fetchCollections: vi.fn() }));

const categoriesMock = vi.mocked(fetchCategories);
const collectionsMock = vi.mocked(fetchCollections);

const BASE = 'https://ideanest.az';
const NOW = new Date('2026-08-17T12:00:00.000Z');

const TAXONOMY = [
  {
    id: '1',
    slug: 'games',
    name: 'Games',
    subcategories: [
      { id: '1a', slug: 'tabletop', name: 'Tabletop' },
      { id: '1b', slug: 'video', name: 'Video games' },
    ],
  },
  { id: '2', slug: 'crafts', name: 'Crafts', subcategories: [] },
];

function collection(overrides: Partial<Collection> = {}): Collection {
  return {
    id: 'c1',
    slug: 'staff-picks',
    kind: 'staff_selection',
    title: 'Staff picks',
    description: null,
    image: null,
    grantsBadge: true,
    projectCount: 6,
    opensAt: null,
    closesAt: null,
    ...overrides,
  };
}

const COLLECTIONS = [
  collection(),
  collection({ id: 'c2', slug: 'spring-2026', kind: 'open_call', title: 'Spring 2026' }),
];

beforeEach(() => {
  categoriesMock.mockReset();
  collectionsMock.mockReset();
  categoriesMock.mockResolvedValue(TAXONOMY);
  collectionsMock.mockResolvedValue(COLLECTIONS);
});

function project(overrides: Partial<SitemapProject> = {}): SitemapProject {
  return {
    creatorSlug: 'aysel',
    slug: 'ceramics-for-the-old-town',
    state: 'LIVE',
    launchedAt: '2026-05-01T09:00:00.000Z',
    deadline: '2026-09-30T20:59:59.000Z',
    ...overrides,
  };
}

describe('projectPath', () => {
  it('is §10.2s canonical public project URL — creator slug, then project slug', () => {
    expect(projectPath('aysel', 'ceramics')).toBe('/projects/aysel/ceramics');
  });

  it('encodes a slug rather than pasting it in', () => {
    expect(projectPath('a b', 'c/d')).toBe('/projects/a%20b/c%2Fd');
  });
});

describe('projectEntries', () => {
  it('lists a live campaign at an absolute URL', () => {
    const entries = projectEntries([project()], BASE, NOW);

    expect(entries).toHaveLength(1);
    expect(entries[0]?.url).toBe('https://ideanest.az/projects/aysel/ceramics-for-the-old-town');
  });

  it('omits a draft, a submitted, a rejected, and a suspended campaign', () => {
    const entries = projectEntries(
      [
        project({ slug: 'a', state: 'DRAFT' }),
        project({ slug: 'b', state: 'SUBMITTED' }),
        project({ slug: 'c', state: 'CHANGES_REQUESTED' }),
        project({ slug: 'd', state: 'REJECTED' }),
        project({ slug: 'e', state: 'APPROVED' }),
        project({ slug: 'f', state: 'SCHEDULED' }),
        project({ slug: 'g', state: 'SUSPENDED' }),
      ],
      BASE,
      NOW,
    );

    expect(entries).toEqual([]);
  });

  it('omits a cancelled campaign and a pre-launch teaser', () => {
    const entries = projectEntries(
      [project({ slug: 'a', state: 'CANCELED' }), project({ slug: 'b', state: 'PRELAUNCH' })],
      BASE,
      NOW,
    );

    expect(entries).toEqual([]);
  });

  it('keeps only the indexable campaign out of a mixed page', () => {
    const entries = projectEntries(
      [
        project({ slug: 'hidden', state: 'DRAFT' }),
        project({ slug: 'shown', state: 'SUCCESSFUL' }),
        project({ slug: 'withdrawn', state: 'CANCELED' }),
      ],
      BASE,
      NOW,
    );

    expect(entries.map((entry) => entry.url)).toEqual([
      'https://ideanest.az/projects/aysel/shown',
    ]);
  });

  it('takes lastModified from the campaigns own timestamps', () => {
    const entries = projectEntries([project()], BASE, NOW);

    // The deadline is in the future, so the launch is the last real change.
    expect(entries[0]?.lastModified).toEqual(new Date('2026-05-01T09:00:00.000Z'));
  });

  it('says a live campaign changes daily and a finished one does not', () => {
    const live = projectEntries([project({ state: 'LIVE' })], BASE, NOW);
    const done = projectEntries(
      [project({ state: 'COMPLETED', deadline: '2026-01-01T00:00:00.000Z' })],
      BASE,
      NOW,
    );

    expect(live[0]?.changeFrequency).toBe('daily');
    expect(done[0]?.changeFrequency).toBe('yearly');
  });

  it('states no priority at all, because there is no honest number to state', async () => {
    for (const entry of [
      ...projectEntries([project()], BASE, NOW),
      ...pageEntries(BASE),
      ...(await discoveryEntries(BASE)),
    ]) {
      expect(entry.priority).toBeUndefined();
    }
  });
});

describe('lastModifiedOf', () => {
  it('is the launch while the campaign is still running', () => {
    expect(
      lastModifiedOf(
        project({ launchedAt: '2026-05-01T09:00:00.000Z', deadline: '2026-09-30T00:00:00.000Z' }),
        NOW,
      ),
    ).toEqual(new Date('2026-05-01T09:00:00.000Z'));
  });

  it('is the deadline once it has passed, because the page changed then', () => {
    expect(
      lastModifiedOf(
        project({ launchedAt: '2026-01-01T00:00:00.000Z', deadline: '2026-06-01T00:00:00.000Z' }),
        NOW,
      ),
    ).toEqual(new Date('2026-06-01T00:00:00.000Z'));
  });

  it('is absent rather than invented when the listing carries no timestamp', () => {
    expect(lastModifiedOf(project({ launchedAt: null, deadline: null }), NOW)).toBeUndefined();
    expect(lastModifiedOf(project({ launchedAt: undefined, deadline: undefined }), NOW)).toBeUndefined();
  });

  it('ignores a timestamp that is not a date', () => {
    expect(lastModifiedOf(project({ launchedAt: 'soon', deadline: null }), NOW)).toBeUndefined();
  });
});

describe('the content-type segments', () => {
  it('lists the static pages absolutely', () => {
    expect(pageEntries(BASE).map((entry) => entry.url)).toEqual(
      PAGE_PATHS.map((path) => `https://ideanest.az${path}`),
    );
  });

  it('lists the unfiltered feed and nothing with a query string', async () => {
    const urls = (await discoveryEntries(BASE)).map((entry) => entry.url);

    expect(urls).toContain('https://ideanest.az/discover');
    for (const path of DISCOVERY_PATHS) expect(urls).toContain(`https://ideanest.az${path}`);

    /*
     * The whole reason WS-05's routes exist: a category reachable only as `?category=games` is
     * a URL robots.txt disallows, and a sitemap must never advertise one of those.
     */
    for (const url of urls) expect(url).not.toContain('?');
  });

  it('lists a page per category, per subcategory, and per collection', async () => {
    const urls = (await discoveryEntries(BASE)).map((entry) => entry.url);

    expect(urls).toEqual([
      'https://ideanest.az/discover',
      'https://ideanest.az/categories/games',
      'https://ideanest.az/categories/games/tabletop',
      'https://ideanest.az/categories/games/video',
      'https://ideanest.az/categories/crafts',
      'https://ideanest.az/collections/staff-picks',
      'https://ideanest.az/collections/spring-2026',
    ]);
  });

  /**
   * A sitemap entry for a URL that answers 404 is an error reported against the whole file,
   * and here it would additionally announce a collection the platform has not published —
   * which is what `CollectionController`'s 404-not-403 rule exists to prevent. The index
   * endpoint already filters, so what is asserted is that nothing here adds a URL of its own.
   */
  it('lists only the collections the service actually published', async () => {
    collectionsMock.mockResolvedValue([collection({ slug: 'only-this-one' })]);

    const urls = (await discoveryEntries(BASE)).map((entry) => entry.url);
    const collectionUrls = urls.filter((url) => url.includes('/collections/'));

    expect(collectionUrls).toEqual(['https://ideanest.az/collections/only-this-one']);
  });

  it('is the feed alone when neither the taxonomy nor the collections can be read', async () => {
    categoriesMock.mockResolvedValue(null);
    collectionsMock.mockResolvedValue(null);

    // A briefly shorter sitemap is a sitemap; one that throws is an error reported against the
    // whole site.
    expect((await discoveryEntries(BASE)).map((entry) => entry.url)).toEqual([
      'https://ideanest.az/discover',
    ]);
  });

  /** One read failing must not take the other's URLs with it. They are independent. */
  it('still lists the taxonomy when only the collections could not be read', async () => {
    collectionsMock.mockResolvedValue(null);

    const urls = (await discoveryEntries(BASE)).map((entry) => entry.url);

    expect(urls).toContain('https://ideanest.az/categories/games');
    expect(urls.some((url) => url.includes('/collections/'))).toBe(false);
  });

  it('lists the collections index among the static pages, as the crawl path to them', () => {
    expect(pageEntries(BASE).map((entry) => entry.url)).toContain(
      'https://ideanest.az/collections',
    );
  });

  it('claims no lastModified for a page whose content it cannot date', async () => {
    for (const entry of [...pageEntries(BASE), ...(await discoveryEntries(BASE))]) {
      expect(entry.lastModified).toBeUndefined();
    }
  });
});
