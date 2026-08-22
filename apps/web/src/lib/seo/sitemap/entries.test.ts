import { beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchCategories } from '../../api/server';
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
 * `discoveryEntries` reads the taxonomy since #265, because §4.3 makes it data rather than
 * code. The read is stubbed here: what is under test is which URLs a tree becomes, not whether
 * the service answers.
 */
vi.mock('../../api/server', () => ({ fetchCategories: vi.fn() }));

const categoriesMock = vi.mocked(fetchCategories);

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

beforeEach(() => {
  categoriesMock.mockReset();
  categoriesMock.mockResolvedValue(TAXONOMY);
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

  it('lists a page per category and per subcategory', async () => {
    const urls = (await discoveryEntries(BASE)).map((entry) => entry.url);

    expect(urls).toEqual([
      'https://ideanest.az/discover',
      'https://ideanest.az/categories/games',
      'https://ideanest.az/categories/games/tabletop',
      'https://ideanest.az/categories/games/video',
      'https://ideanest.az/categories/crafts',
    ]);
  });

  it('is the feed alone when the taxonomy cannot be read', async () => {
    categoriesMock.mockResolvedValue(null);

    // A briefly shorter sitemap is a sitemap; one that throws is an error reported against the
    // whole site.
    expect((await discoveryEntries(BASE)).map((entry) => entry.url)).toEqual([
      'https://ideanest.az/discover',
    ]);
  });

  it('claims no lastModified for a page whose content it cannot date', async () => {
    for (const entry of [...pageEntries(BASE), ...(await discoveryEntries(BASE))]) {
      expect(entry.lastModified).toBeUndefined();
    }
  });
});
