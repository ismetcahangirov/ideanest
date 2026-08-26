import { describe, expect, it } from 'vitest';

import { breadcrumbNode, discoverCrumb, homeCrumb, type TrailCopy } from './breadcrumb';

const env = { IDEANEST_SITE_URL: 'https://ideanest.az' } as const;

/** The English catalogue's words, which is what these assertions read back. */
const TRAIL: TrailCopy = {
  home: 'Home',
  discover: 'Discover',
  categories: 'Categories',
  collections: 'Collections',
};

const HOME_CRUMB = homeCrumb(TRAIL);
const DISCOVER_CRUMB = discoverCrumb(TRAIL);

interface ListItem {
  readonly '@type': string;
  readonly position: number;
  readonly name: string;
  readonly item: string;
}

function items(node: ReturnType<typeof breadcrumbNode>): readonly ListItem[] {
  return (node?.['itemListElement'] ?? []) as unknown as readonly ListItem[];
}

describe('breadcrumbNode', () => {
  it('numbers the trail from one, in the order it is walked', () => {
    const node = breadcrumbNode(
      [HOME_CRUMB, DISCOVER_CRUMB, { name: 'A ceramics studio', path: '/projects/ayan/studio' }],
      'en',
      env,
    );

    expect(node?.['@type']).toBe('BreadcrumbList');
    expect(items(node).map((item) => [item.position, item.name])).toEqual([
      [1, 'Home'],
      [2, 'Discover'],
      [3, 'A ceramics studio'],
    ]);
  });

  it('gives every step an absolute URL on the configured origin', () => {
    const node = breadcrumbNode(
      [HOME_CRUMB, DISCOVER_CRUMB, { name: 'A ceramics studio', path: '/projects/ayan/studio' }],
      'en',
      env,
    );

    expect(items(node).map((item) => item.item)).toEqual([
      'https://ideanest.az/en',
      'https://ideanest.az/en/discover',
      'https://ideanest.az/en/projects/ayan/studio',
    ]);
  });

  /**
   * #123. Every step used to name the un-prefixed address, which `middleware.ts` answers with
   * a 307 — a trail whose entire job is to name the pages a reader came through, naming three
   * redirects, and contradicting the canonical of the page it was emitted on.
   */
  it('walks the language the page is in, never the un-prefixed address', () => {
    const node = breadcrumbNode(
      [
        homeCrumb(TRAIL),
        discoverCrumb(TRAIL),
        { name: 'A ceramics studio', path: '/projects/ayan/studio' },
      ],
      'ru',
      env,
    );

    expect(items(node).map((item) => item.item)).toEqual([
      'https://ideanest.az/ru',
      'https://ideanest.az/ru/discover',
      'https://ideanest.az/ru/projects/ayan/studio',
    ]);
  });

  it('drops the query string a filter or a tracking parameter left on a path', () => {
    const node = breadcrumbNode([HOME_CRUMB, { name: 'Discover', path: '/discover?utm_source=x' }], 'en', env);
    expect(items(node)[1]?.item).toBe('https://ideanest.az/en/discover');
  });

  it('is null for a trail with nothing to walk', () => {
    expect(breadcrumbNode([], 'en', env)).toBeNull();
  });

  it('is null for a single step, which is a page rather than a trail', () => {
    expect(breadcrumbNode([HOME_CRUMB], 'en', env)).toBeNull();
  });

  it('drops a step with no name rather than numbering an empty one', () => {
    const node = breadcrumbNode(
      [HOME_CRUMB, DISCOVER_CRUMB, { name: '   ', path: '/projects/ayan/studio' }],
      'en',
      env,
    );

    expect(items(node).map((item) => item.name)).toEqual(['Home', 'Discover']);
    expect(items(node).map((item) => item.position)).toEqual([1, 2]);
  });

  it('collapses the whitespace a textarea put in a campaign title', () => {
    const node = breadcrumbNode(
      [HOME_CRUMB, DISCOVER_CRUMB, { name: 'A ceramics\n  studio ', path: '/projects/ayan/studio' }],
      'en',
      env,
    );

    expect(items(node)[2]?.name).toBe('A ceramics studio');
  });

  it('cannot be pointed at another host by a path that arrived as a URL', () => {
    const node = breadcrumbNode(
      [HOME_CRUMB, { name: 'Elsewhere', path: 'https://elsewhere.example/steal' }],
      'en',
      env,
    );

    expect(items(node)[1]?.item).toBe('https://ideanest.az/en/steal');
  });
});
