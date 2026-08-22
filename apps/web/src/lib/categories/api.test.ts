import { describe, expect, it } from 'vitest';
import {
  categoryPath,
  findCategory,
  findSubcategory,
  subcategoryPath,
  type Category,
} from './api';

const GAMES: Category = {
  id: '1',
  slug: 'games',
  name: 'Games',
  subcategories: [
    { id: '1a', slug: 'tabletop', name: 'Tabletop' },
    { id: '1b', slug: 'prints', name: 'Game prints' },
  ],
};

const CRAFTS: Category = {
  id: '2',
  slug: 'crafts',
  name: 'Crafts',
  // A slug that also exists under Games. This is the case `findSubcategory` is scoped for.
  subcategories: [{ id: '2a', slug: 'prints', name: 'Printmaking' }],
};

const TREE = [GAMES, CRAFTS];

describe('the browse URLs', () => {
  it('are paths, not filters — which is the whole point of WS-05', () => {
    expect(categoryPath('games')).toBe('/categories/games');
    expect(subcategoryPath('games', 'tabletop')).toBe('/categories/games/tabletop');
  });

  it('encode a slug, because the taxonomy is data and data changes without a deployment', () => {
    expect(categoryPath('film & video')).toBe('/categories/film%20%26%20video');
  });
});

describe('findCategory', () => {
  it('finds by slug, case-insensitively, as the service folds them', () => {
    expect(findCategory(TREE, 'games')).toBe(GAMES);
    expect(findCategory(TREE, 'GAMES')).toBe(GAMES);
  });

  it('is null for a slug that names nothing', () => {
    expect(findCategory(TREE, 'gmaes')).toBeNull();
  });
});

describe('findSubcategory', () => {
  it('searches inside one category and never across the tree', () => {
    expect(findSubcategory(GAMES, 'prints')?.name).toBe('Game prints');
    expect(findSubcategory(CRAFTS, 'prints')?.name).toBe('Printmaking');
  });

  it('is null for a subcategory belonging to a different parent', () => {
    // `/categories/games/tabletop` is a page; `/categories/crafts/tabletop` is a 404. A global
    // lookup would render a page whose breadcrumb contradicts its own URL.
    expect(findSubcategory(CRAFTS, 'tabletop')).toBeNull();
  });
});
