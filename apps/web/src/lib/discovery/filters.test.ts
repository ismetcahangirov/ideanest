import { describe, expect, it } from 'vitest';
import {
  NO_FILTERS,
  activeFilters,
  boundsAreOrdered,
  clearFilters,
  filterKey,
  isValidBound,
  parseFilters,
  removeFilter,
  toHref,
  toSearchParams,
  toggleTag,
} from './filters';

/**
 * The URL is the state, so this is the file that decides whether a shared
 * filter link means the same thing to the person who receives it (D-12).
 *
 * The tests that carry the design are the round trip, the refusal to put a
 * cursor in the URL, and the money bounds — the last because a filter bound is
 * money and a bound that has been through a JavaScript number cannot be trusted
 * again.
 */

const params = (query: string): URLSearchParams => new URLSearchParams(query);

describe('parseFilters', () => {
  it('reads every dimension the service implements', () => {
    const filters = parseFilters(
      params(
        'status=live&category=games&subcategory=tabletop&tag=handmade&completion=under_25' +
          '&goalBand=1000_to_5000&goalMin=1000&goalMax=5000&raisedBand=under_1000&sort=most_funded',
      ),
    );

    expect(filters.statuses).toEqual(['live']);
    expect(filters.categories).toEqual(['games']);
    expect(filters.subcategories).toEqual(['tabletop']);
    expect(filters.tags).toEqual(['handmade']);
    expect(filters.completion).toEqual(['under_25']);
    expect(filters.goal).toEqual({ bands: ['1000_to_5000'], min: '1000', max: '5000' });
    expect(filters.raised.bands).toEqual(['under_1000']);
    expect(filters.sort).toBe('most_funded');
  });

  it('takes both the repeated form and the comma form', () => {
    // A form produces `?tag=a&tag=b`; a shared link is `?tag=a,b`. The binder
    // accepts either, so a link copied out of the address bar must not come
    // back meaning something else.
    expect(parseFilters(params('tag=a&tag=b')).tags).toEqual(['a', 'b']);
    expect(parseFilters(params('tag=a,b')).tags).toEqual(['a', 'b']);
    expect(parseFilters(params('tag=a,b&tag=c')).tags).toEqual(['a', 'b', 'c']);
  });

  it('drops a value that is not one of a closed vocabulary', () => {
    // `?status=finished` is a 400 from the service. Keeping it would turn a
    // mistyped link into an error page; dropping it shows the rest of the link.
    expect(parseFilters(params('status=finished&status=live')).statuses).toEqual(['live']);
    expect(parseFilters(params('sort=cheapest')).sort).toBe('newest');
    expect(parseFilters(params('completion=nearly')).completion).toEqual([]);
  });

  it('keeps a slug that names nothing', () => {
    // Categories and tags are data and change without a deployment. The service
    // answers an unknown slug with an empty feed rather than an error, so a link
    // shared before a rename says "nothing here" rather than widening to
    // everything.
    expect(parseFilters(params('category=underwater-basketweaving')).categories).toEqual([
      'underwater-basketweaving',
    ]);
  });

  it('folds a slug to lower case, as the service does', () => {
    expect(parseFilters(params('category=Games')).categories).toEqual(['games']);
  });

  it('defaults the sort to newest', () => {
    // The service's own default, and the only order that is meaningful with no
    // filter applied.
    expect(parseFilters(params('')).sort).toBe('newest');
  });
});

describe('toSearchParams', () => {
  it('round trips a filter set', () => {
    const original = parseFilters(
      params('status=live,upcoming&category=games&tag=a,b&goalMin=100&sort=ending_soon'),
    );

    expect(parseFilters(toSearchParams(original))).toEqual(original);
  });

  it('omits the default sort', () => {
    // `/discover` and `/discover?sort=newest` are the same feed. Printing the
    // default into every link makes the most-shared URL the noisiest one.
    expect(toHref(NO_FILTERS)).toBe('/discover');
    expect(toHref({ ...NO_FILTERS, sort: 'popularity' })).toBe('/discover?sort=popularity');
  });

  it('never writes a cursor', () => {
    // A shared link opens a fresh first page, not page seven of somebody else's
    // scroll — and a cursor is bound to the query it was issued for, so one in
    // the URL would 400 the moment the recipient touched a filter.
    const href = toHref(parseFilters(params('status=live&cursor=abc123')));

    expect(href).not.toContain('cursor');
    expect(href).toBe('/discover?status=live');
  });

  it('keys a filter set by everything that decides the results', () => {
    const a = parseFilters(params('status=live&sort=newest'));
    const b = parseFilters(params('status=live'));
    const c = parseFilters(params('status=upcoming'));

    expect(filterKey(a)).toBe(filterKey(b));
    expect(filterKey(a)).not.toBe(filterKey(c));
  });
});

describe('activeFilters', () => {
  const names = new Map([
    ['games', 'Games'],
    ['tabletop', 'Tabletop games'],
    ['handmade', 'Handmade'],
  ]);

  it('names every applied filter with its group and its translated label', () => {
    const filters = parseFilters(
      params('status=live&category=games&subcategory=tabletop&tag=handmade&completion=over_100'),
    );

    expect(activeFilters(filters, names).map((f) => [f.group, f.label])).toEqual([
      ['Status', 'Live'],
      ['Category', 'Games'],
      ['Subcategory', 'Tabletop games'],
      ['Completion', 'Funded — 100% or more'],
      ['Tag', 'Handmade'],
    ]);
  });

  it('falls back to the slug when the panel has not named it', () => {
    expect(activeFilters(parseFilters(params('tag=ceramics'))).at(0)?.label).toBe('ceramics');
  });

  it('treats a custom range as one filter, not two', () => {
    // A minimum and a maximum are one thought. Removing half of "2,500 to
    // 20,000" leaves a filter nobody asked for.
    const active = activeFilters(parseFilters(params('goalMin=2500&goalMax=20000')));

    expect(active).toHaveLength(1);
    expect(active.at(0)?.label).toBe('2500 to 20000 AZN');
  });

  it('is empty with no filters, whatever the sort', () => {
    expect(activeFilters(parseFilters(params('sort=most_backed')))).toEqual([]);
  });
});

describe('removeFilter', () => {
  it('removes exactly the one named, from every dimension', () => {
    const filters = parseFilters(params('status=live,upcoming&tag=a,b&goalBand=under_1000'));
    const active = activeFilters(filters);

    const withoutLive = removeFilter(filters, active.find((f) => f.value === 'live')!);
    expect(withoutLive.statuses).toEqual(['upcoming']);
    expect(withoutLive.tags).toEqual(['a', 'b']);

    const withoutTag = removeFilter(filters, active.find((f) => f.value === 'a')!);
    expect(withoutTag.tags).toEqual(['b']);

    const withoutBand = removeFilter(filters, active.find((f) => f.dimension === 'goalBand')!);
    expect(withoutBand.goal.bands).toEqual([]);
  });

  it('clears both ends of a custom range together', () => {
    const filters = parseFilters(params('raisedMin=100&raisedMax=900'));
    const cleared = removeFilter(filters, activeFilters(filters)[0]!);

    expect(cleared.raised).toEqual({ bands: [], min: null, max: null });
  });
});

describe('clearFilters', () => {
  it('drops every filter and keeps the order the reader chose', () => {
    // "Clear all" is about the narrowing, not the order. Resetting the sort too
    // would be a second, unasked-for change hidden behind one control.
    const cleared = clearFilters(parseFilters(params('status=live&tag=a&sort=ending_soon')));

    expect(activeFilters(cleared)).toEqual([]);
    expect(cleared.sort).toBe('ending_soon');
  });
});

describe('toggleTag', () => {
  it('adds and removes without disturbing the rest', () => {
    const once = toggleTag(NO_FILTERS, 'handmade');
    expect(once.tags).toEqual(['handmade']);
    expect(toggleTag(once, 'handmade').tags).toEqual([]);
  });
});

describe('money bounds', () => {
  it('accepts the amounts the API accepts', () => {
    expect(isValidBound('2500')).toBe(true);
    expect(isValidBound('2500.00')).toBe(true);
    // Zero is a real bound here, unlike a goal or a reward price: "raised up to
    // 0" asks for the campaigns nobody has backed yet.
    expect(isValidBound('0')).toBe(true);
    expect(isValidBound(null)).toBe(true);
    expect(isValidBound('')).toBe(true);
  });

  it('refuses what a JavaScript number would silently swallow', () => {
    // `Number()` accepts every one of these and loses the value. A bound that
    // has been through one can never be trusted again.
    expect(isValidBound('1e5')).toBe(false);
    expect(isValidBound('0x10')).toBe(false);
    expect(isValidBound('12abc')).toBe(false);
    expect(isValidBound('Infinity')).toBe(false);
    expect(isValidBound('-100')).toBe(false);
    expect(isValidBound('1,50')).toBe(false);
  });

  it('orders bounds as decimals, not as strings or floats', () => {
    expect(boundsAreOrdered('1000', '5000')).toBe(true);
    expect(boundsAreOrdered('5000', '1000')).toBe(false);
    // '100' sorts below '99' as a string, and '9.99' below '10' only by
    // accident. Both are compared as decimals here.
    expect(boundsAreOrdered('99', '100')).toBe(true);
    expect(boundsAreOrdered('9.99', '10')).toBe(true);
    expect(boundsAreOrdered('10', '9.99')).toBe(false);
    // Equal ends are a range of exactly one amount, which is legitimate.
    expect(boundsAreOrdered('5000.00', '5000')).toBe(true);
  });
});
