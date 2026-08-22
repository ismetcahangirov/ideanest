import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchCategories, fetchDiscoveryFeed } from '../api/server';
import { resolveCategoryLanding } from './landing';

/**
 * What a category landing page resolves to — §4.13 WS-05, issue #265.
 *
 * WHAT THESE COVER:
 *
 *   - a slug that names nothing is a 404, not an empty page. An empty landing page for
 *     `/categories/gmaes` is a 200 that gets indexed, linked to, and re-crawled forever.
 *   - a subcategory is resolved inside its own parent. `/categories/crafts/tabletop` is a 404
 *     even though `tabletop` exists, because a global lookup gives one page two URLs and a
 *     breadcrumb that contradicts its own address.
 *   - **a taxonomy that could not be read is also a 404**, which is the uncomfortable one.
 *     Without the tree there is no name for the heading and no way to confirm the slug is
 *     real, so the alternative is a page titled with a slug listing an unverifiable filter.
 *   - the feed is asked for the campaigns of that category, with the subcategory alongside
 *     rather than instead of it, so the request and the URL the page offers agree.
 */

vi.mock('../api/server', () => ({
  fetchCategories: vi.fn(),
  fetchDiscoveryFeed: vi.fn(),
}));

const categoriesMock = vi.mocked(fetchCategories);
const feedMock = vi.mocked(fetchDiscoveryFeed);

const TREE = [
  {
    id: '1',
    slug: 'games',
    name: 'Games',
    subcategories: [{ id: '1a', slug: 'tabletop', name: 'Tabletop' }],
  },
  { id: '2', slug: 'crafts', name: 'Crafts', subcategories: [] },
];

const CARD = {
  id: 'c1',
  slug: 'a-campaign',
  creatorSlug: 'aysel',
  title: 'A campaign',
  creator: { name: 'Aysel', slug: 'aysel' },
  pledged: { amount: '10.00', currency: 'AZN' },
  backersCount: 1,
  state: 'LIVE',
};

beforeEach(() => {
  categoriesMock.mockReset();
  feedMock.mockReset();
  categoriesMock.mockResolvedValue(TREE);
  feedMock.mockResolvedValue({ items: [CARD] });
});

describe('a category', () => {
  it('resolves and carries its campaigns', async () => {
    const result = await resolveCategoryLanding('games');

    expect(result.kind).toBe('found');
    if (result.kind !== 'found') return;
    expect(result.category.name).toBe('Games');
    expect(result.subcategory).toBeNull();
    expect(result.campaigns).toHaveLength(1);
  });

  it('asks the feed for that category', async () => {
    await resolveCategoryLanding('games');

    const query = new URLSearchParams(feedMock.mock.calls[0]?.[0]);
    expect(query.get('category')).toBe('games');
    expect(query.has('subcategory')).toBe(false);
  });

  it('is not found for a slug that names nothing', async () => {
    expect(await resolveCategoryLanding('gmaes')).toEqual({ kind: 'not-found' });
  });
});

describe('a subcategory', () => {
  it('resolves inside its own parent', async () => {
    const result = await resolveCategoryLanding('games', 'tabletop');

    expect(result.kind).toBe('found');
    if (result.kind !== 'found') return;
    expect(result.subcategory?.name).toBe('Tabletop');
  });

  it('is not found under a different parent', async () => {
    expect(await resolveCategoryLanding('crafts', 'tabletop')).toEqual({ kind: 'not-found' });
  });

  it('sends the category alongside it, so the request and the offered URL agree', async () => {
    await resolveCategoryLanding('games', 'tabletop');

    const query = new URLSearchParams(feedMock.mock.calls[0]?.[0]);
    expect(query.get('category')).toBe('games');
    expect(query.get('subcategory')).toBe('tabletop');
  });
});

describe('when a read fails', () => {
  it('is not found when the taxonomy could not be read', async () => {
    categoriesMock.mockResolvedValue(null);
    expect(await resolveCategoryLanding('games')).toEqual({ kind: 'not-found' });
  });

  it('is an empty page, not a 404, when only the feed could not be read', async () => {
    // The category exists and has a name; what is missing is a list, and a page that says
    // "nothing here" is honest while a 404 would claim the category does not exist.
    feedMock.mockResolvedValue(null);

    const result = await resolveCategoryLanding('games');
    expect(result.kind).toBe('found');
    if (result.kind !== 'found') return;
    expect(result.campaigns).toEqual([]);
    expect(result.hasMore).toBe(false);
  });
});

describe('hasMore', () => {
  it('is the presence of a cursor, never a short page', async () => {
    feedMock.mockResolvedValue({ items: [CARD], nextCursor: 'abc' });
    const result = await resolveCategoryLanding('games');

    expect(result.kind === 'found' && result.hasMore).toBe(true);
  });

  it('is false for an absent or empty cursor', async () => {
    feedMock.mockResolvedValue({ items: [CARD], nextCursor: '' });
    const result = await resolveCategoryLanding('games');

    expect(result.kind === 'found' && result.hasMore).toBe(false);
  });
});
