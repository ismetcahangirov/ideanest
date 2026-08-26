import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import type { Collection } from '../../lib/collections/api';
import { CollectionIndex } from './CollectionIndex';

/** The English catalogue's `discovery.collections.window`. */
const WINDOW_COPY = { closes: 'Closes', openSince: 'Open since' };

/**
 * The body of `/collections` — D-08, issue #266.
 *
 * WHAT THESE COVER:
 *
 *   - every visible collection is an ordinary link. This page is the ONLY path a crawler has
 *     to a collection landing page: the feed's `programme` filter is not exposed and
 *     `/discover?` is disallowed wholesale, so an index that failed to link one would leave
 *     that page an orphan in the sitemap.
 *   - the kind is a word, not a colour. docs/ui-kit.md §9.2 forbids colour alone from carrying
 *     meaning, and "open call" versus "staff selection" is the difference between something a
 *     creator can act on and something they can only read.
 *   - an open call's closing date is on the card, because it is the one fact on the page with a
 *     deadline attached to it.
 *   - the curator's order survives. Regrouping by kind would overrule, invisibly, a decision a
 *     person made row by row.
 *   - a refused read and an empty platform render the same honest thing, and the way out is the
 *     feed rather than a filter the reader never applied.
 *
 * Accessibility is asserted by role and by accessible name throughout, which is what this
 * application's tests use — there is no axe helper in `src/test-setup.ts`, and the checks that
 * would matter here are the ones a role query already makes: a named list, one heading level
 * per rank, a link with a real name.
 */

function collection(overrides: Partial<Collection> = {}): Collection {
  return {
    id: 'c1',
    slug: 'staff-picks',
    kind: 'staff_selection',
    title: 'Staff picks',
    description: 'What we are reading this month.',
    image: null,
    grantsBadge: false,
    projectCount: 6,
    opensAt: null,
    closesAt: null,
    ...overrides,
  };
}

const OPEN_CALL = collection({
  id: 'c2',
  slug: 'spring-2026',
  kind: 'open_call',
  title: 'Spring 2026',
  description: 'Applications for the spring programme.',
  projectCount: 3,
  opensAt: '2026-03-01T00:00:00Z',
  closesAt: '2026-05-31T20:59:59Z',
});

afterEach(cleanup);

describe('the collections index', () => {
  it('names itself', () => {
    render(<CollectionIndex locale="en" windowCopy={WINDOW_COPY} collections={[collection()]} />);

    expect(screen.getByRole('heading', { level: 1, name: 'Collections' })).toBeInTheDocument();
  });

  it('links every collection, which is the only path a crawler has to them', () => {
    render(<CollectionIndex locale="en" windowCopy={WINDOW_COPY} collections={[collection(), OPEN_CALL]} />);

    const list = screen.getByRole('list', { name: 'Collections' });
    expect(within(list).getByRole('link', { name: 'Staff picks' })).toHaveAttribute('href', '/en/collections/staff-picks');
    expect(within(list).getByRole('link', { name: 'Spring 2026' })).toHaveAttribute('href', '/en/collections/spring-2026');
  });

  it('keeps the order the curator arranged', () => {
    render(<CollectionIndex locale="en" windowCopy={WINDOW_COPY} collections={[OPEN_CALL, collection()]} />);

    const headings = screen.getAllByRole('heading', { level: 3 }).map((node) => node.textContent);
    expect(headings).toEqual(['Spring 2026', 'Staff picks']);
  });

  it('says what kind of list each one is, in words', () => {
    render(<CollectionIndex locale="en" windowCopy={WINDOW_COPY} collections={[collection(), OPEN_CALL]} />);

    expect(screen.getByText('Staff selection')).toBeInTheDocument();
    expect(screen.getByText('Open call')).toBeInTheDocument();
  });

  it('prints no kind at all for one this build does not know', () => {
    render(<CollectionIndex locale="en" windowCopy={WINDOW_COPY} collections={[collection({ kind: 'mystery' })]} />);

    expect(screen.queryByText('mystery')).toBeNull();
    // The collection itself is still listed. An unfamiliar kind costs a label, never a page.
    expect(screen.getByRole('link', { name: 'Staff picks' })).toBeInTheDocument();
  });

  it('states when an open call closes, as a date a machine can read too', () => {
    render(<CollectionIndex locale="en" windowCopy={WINDOW_COPY} collections={[OPEN_CALL]} />);

    expect(screen.getByText('Closes')).toBeInTheDocument();

    const closing = screen.getByText('31 May 2026');
    expect(closing.tagName).toBe('TIME');
    expect(closing).toHaveAttribute('datetime', '2026-05-31T20:59:59Z');
  });

  it('says nothing about a window a standing collection does not have', () => {
    render(<CollectionIndex locale="en" windowCopy={WINDOW_COPY} collections={[collection()]} />);

    expect(screen.queryByText('Closes')).toBeNull();
    expect(screen.queryByText('Open since')).toBeNull();
  });

  it('states the size of each collection rather than leaving it to be counted', () => {
    render(<CollectionIndex locale="en" windowCopy={WINDOW_COPY} collections={[collection({ projectCount: 6 })]} />);

    expect(screen.getByText('Campaigns')).toBeInTheDocument();
    expect(screen.getByText('6')).toBeInTheDocument();
  });
});

describe('with nothing to list', () => {
  it('offers the feed rather than telling the reader to clear a filter', () => {
    render(<CollectionIndex locale="en" windowCopy={WINDOW_COPY} collections={[]} />);

    expect(
      screen.getByRole('heading', { level: 2, name: 'No collections just now' }),
    ).toBeInTheDocument();
    expect(screen.queryByText(/clear/iu)).toBeNull();
    expect(screen.getByRole('link', { name: 'Browse the feed' })).toHaveAttribute('href', '/en/discover');
  });

  it('says the same thing when the read was refused, because it cannot honestly say more', () => {
    render(<CollectionIndex locale="en" windowCopy={WINDOW_COPY} collections={null} />);

    expect(
      screen.getByRole('heading', { level: 2, name: 'No collections just now' }),
    ).toBeInTheDocument();
  });
});
